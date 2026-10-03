package com.starlab.twin;

import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.Sgp4Propagator;
import com.starlab.orbit.TleCatalogService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 孪生闭环测试：TLE 快照对表
 * <p>
 * self：快照对自身对表，误差必须为 0（管道正确性：解析→匹配→传播→比对）。
 * live：从 CelesTrak 拉取当前星历作为现实源，与仓库内置星库（starlink-2026-10.tle）对表，
 *       产出真实的"龄期-误差"分布与刷新策略建议。网络依赖 + 分钟级，按需手动运行。
 */
class TwinLoopReconciliationTest {

    @Test
    void selfReconciliationIsZero() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);

        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        TleReconciliationService svc = new TleReconciliationService(prop);

        TleReconciliationService.ReconciliationReport r =
                svc.reconcile(cat.satellites(), cat.satellites(), "self", "self-test");

        assertEquals(cat.satellites().size(), r.matched(), "全部卫星都应命中");
        assertEquals(0, r.onlyInSnapshot());
        assertEquals(0, r.onlyInFresh());
        assertTrue(r.errKmMax() < 1e-6, "同源对表误差必须为 0，实测 max=" + r.errKmMax());
        assertTrue(r.errKmP50() < 1e-6);
        System.out.printf("[self] matched=%d maxErr=%.9f km policy=%s%n",
                r.matched(), r.errKmMax(), r.policy());
    }

    @Test
    @Disabled("手动跑：依赖 CelesTrak 网络，产出真实龄期-误差报告")
    void liveCelesTrakReconciliation() throws Exception {
        String snapshotText = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog snapshot = new TleCatalogService().parse(snapshotText);

        // 现实源两种入口：环境变量指定本地 TLE 文件（服务器代拉），或直连 CelesTrak
        String realityFile = System.getenv("TWIN_REALITY_FILE");
        String freshText;
        String realitySource;
        if (realityFile != null && !realityFile.isBlank()) {
            freshText = java.nio.file.Files.readString(java.nio.file.Path.of(realityFile));
            realitySource = "file:" + realityFile;
        } else {
            freshText = new CelesTrakClient().fetchGroupTle("starlink");
            realitySource = "celestrak:starlink";
        }
        TleCatalogService.ParsedCatalog fresh = new TleCatalogService().parse(freshText);

        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        TleReconciliationService svc = new TleReconciliationService(prop);
        TleReconciliationService.ReconciliationReport r = svc.reconcile(
                snapshot.satellites(), fresh.satellites(), "bundled-starlink-2026-10", realitySource);

        System.out.printf("[live] snapshot=%d fresh=%d matched=%d onlySnap=%d onlyFresh=%d 历元异常=%d%n",
                r.snapshotCount(), r.freshCount(), r.matched(), r.onlyInSnapshot(),
                r.onlyInFresh(), r.ageAnomalies());
        System.out.printf("[live] TLE 龄期 med=%.2fd max=%.2fd%n", r.ageDaysMedian(), r.ageDaysMax());
        System.out.printf("[live] 误差(km): mean=%.2f p50=%.2f p90=%.2f p99=%.2f max=%.2f | ≥25km=%d ≥100km=%d%n",
                r.errKmMean(), r.errKmP50(), r.errKmP90(), r.errKmP99(), r.errKmMax(),
                r.over25km(), r.over100km());
        System.out.println("[live] 龄期分桶:");
        r.ageBuckets().forEach(b -> System.out.printf(
                "  %-6s n=%-6d p50=%.2f p90=%.2f max=%.2f%n",
                b.range(), b.n(), b.p50Km(), b.p90Km(), b.maxKm()));
        System.out.println("[live] 离群星（机动嫌疑）:");
        r.outliers().forEach(o -> System.out.printf(
                "  %-10s %-18s 龄期 %.2fd  误差 %.1f km%n",
                o.catalogNumber(), o.name(), o.ageDays(), o.errKm()));
        System.out.println("[live] 刷新策略: " + r.policy());

        assertTrue(r.matched() > 10_000, "命中卫星数异常");
        assertTrue(r.errKmMax() > 0, "跨源对表误差不应为 0");
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = TwinLoopReconciliationTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
