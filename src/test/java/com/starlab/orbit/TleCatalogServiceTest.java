package com.starlab.orbit;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TLE 目录批量导入：真实 Starlink 文件 + 人造脏数据 + 万级规模吞吐。
 */
class TleCatalogServiceTest {

    private static String resource(String path) throws Exception {
        try (InputStream in = TleCatalogServiceTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void negativeBstarAndCompactNotationParse() {
        // 负 BSTAR：Starlink 机动卫星的真实现象，-43656-4 = -0.43656e-4
        assertEquals(-4.3656e-5, TleParser.parseExponential("-43656-4"), 1e-12);
        assertEquals(-2.3701e-6, TleParser.parseExponential("-23701-5"), 1e-12);
        // 常规紧凑记法
        assertEquals(2.7039e-4, TleParser.parseExponential(" 27039-3"), 1e-12);
        assertEquals(0.0, TleParser.parseExponential("00000+0"), 1e-12);
        assertEquals(1.2345e-3, TleParser.parseExponential(".12345-2"), 1e-12);
    }

    @Test
    void starlinkCatalogParsesClean() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService svc = new TleCatalogService();
        TleCatalogService.ParsedCatalog cat = svc.parse(text);

        TleCatalogService.CatalogStats s = cat.stats();
        System.out.printf("groups=%d parsed=%d rejected=%d dup=%d parseMs=%d heap=%dMB%n",
                s.totalGroups(), s.parsed(), s.rejected(), s.duplicates(), s.parseMs(),
                (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20);

        assertEquals(10681, s.totalGroups());
        assertEquals(0, s.rejected());
        assertEquals(0, s.duplicates());
        assertEquals(10681, cat.satellites().size());
        assertTrue(s.sampleErrors().isEmpty());

        // 负 BSTAR 回归：1285 颗机动卫星，缺负号支持则这里直接炸
        long negBstar = cat.satellites().stream().filter(el -> el.bstar() < 0).count();
        System.out.println("negative bstar: " + negBstar);
        assertEquals(1285, negBstar);
    }

    @Test
    void corruptGroupsAreRejectedIndividually() {
        List<String> goodLines = good().lines().toList();
        String l1 = goodLines.get(1);
        String l2 = goodLines.get(2);
        // 1) 平均运动越界（n=25.49）：'1'→'2' 使数字和 +1，校验位 4→5 补偿，确保死于范围检查而非校验和
        String badN = l2.replace("15.487256605", "25.487256605");
        badN = badN.substring(0, 68) + "5";
        // 2) 尾随空格被吃掉（68 列）
        String shortL1 = l1.substring(0, 68);
        // 3) 校验和错误
        String badCs = l1.substring(0, 68) + "0";

        String text = String.join("\n",
                "N-OVER", l1, badN,
                "SHORT LINE", shortL1, l2,
                "BAD CS", badCs, l2,
                good());
        TleCatalogService svc = new TleCatalogService();
        TleCatalogService.ParsedCatalog cat = svc.parse(text);

        System.out.println("errors: " + cat.stats().sampleErrors());
        assertEquals(1, cat.satellites().size());
        assertEquals(3, cat.stats().rejected());
        List<String> errs = cat.stats().sampleErrors();
        assertTrue(errs.get(0).contains("平均运动越界"));
        assertTrue(errs.get(1).contains("不足 69 列"));
        assertTrue(errs.get(2).contains("校验和错误"));
    }

    private static String good() {
        return String.join("\n",
                "ISS (ZARYA)",
                "1 25544U 98067A   26276.49792087  .00005083  00000+0  10128-3 0  9999",
                "2 25544  51.6313 124.0722 0006914 218.1010 141.9490 15.48725660588544");
    }

    @Test
    @Disabled("万级吞吐基准：按需本地运行（mvn test -Dtest=TleCatalogServiceTest#propagationThroughput）")
    void propagationThroughput() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);
        List<OrbitalElements> sats = cat.satellites();
        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());

        // SGP4 路径（全部带 BSTAR → 策略路由到 SGP4-lite）
        Instant t = Instant.ofEpochSecond((long) sats.getFirst().epochSeconds()).plusSeconds(600);
        long t0 = System.nanoTime();
        for (int step = 0; step < 10; step++) {
            for (OrbitalElements el : sats) {
                prop.propagate(el, t.plusSeconds(step * 60L));
            }
        }
        long sgp4Ms = (System.nanoTime() - t0) / 1_000_000;

        // Kepler 路径（剥掉阻力项 → 策略路由到二体解析解）
        List<OrbitalElements> keplerized = sats.stream().map(el -> new OrbitalElements(
                el.satelliteId(), el.name(), el.catalogNumber(), el.classification(),
                el.inclination(), el.raan(), el.eccentricity(),
                el.argumentOfPerigee(), el.meanAnomaly(), el.meanMotion(),
                el.epochSeconds())).toList();
        t0 = System.nanoTime();
        for (int step = 0; step < 10; step++) {
            for (OrbitalElements el : keplerized) {
                prop.propagate(el, t.plusSeconds(step * 60L));
            }
        }
        long keplerMs = (System.nanoTime() - t0) / 1_000_000;

        System.out.printf("SGP4-lite: %d sats x 10 steps = %d ms (%.1f us/sat)%n",
                sats.size(), sgp4Ms, sgp4Ms * 1000.0 / (sats.size() * 10));
        System.out.printf("Kepler   : %d sats x 10 steps = %d ms (%.1f us/sat)%n",
                keplerized.size(), keplerMs, keplerMs * 1000.0 / (keplerized.size() * 10));
        assertTrue(sgp4Ms < 120_000);
    }
}
