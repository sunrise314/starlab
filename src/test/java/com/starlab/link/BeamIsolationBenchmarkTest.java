package com.starlab.link;

import com.starlab.constellation.Constellation;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.Sgp4Propagator;
import com.starlab.orbit.TleCatalogService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复用第 06 章基准的分布统计类（同包嵌套类） */
import com.starlab.link.InterferenceBenchmarkTest.Dist;

/**
 * 第 08 章基准：相控阵空分隔离。
 * <p>
 * 第 07 章证明频率维度在 200 信道预算下天花板 = 36% 过线，剩下的分贝必须从空间维度拿。
 * 本章给接收端装上指向服务星的相控阵方向图，对偏轴干扰按角距加权：
 * <pre>
 *   G_rel(θ) = max(−12·(θ/θ_B)², 旁瓣封底)     —— 抛物线主瓣 + 旁瓣地板
 *   C/I_beam = C − 10·log10(Σ P_i·10^(G_rel(θ_i)/10) + N)
 * </pre>
 * 主瓣增益在 C 与 N 中同增同减，比值中消掉——方向图只改干扰项，这本身就是结论：
 * 空分救的是 C/I，SNR 本来就健康。
 * <p>
 * 三档实验（同一份 24.7 万条可见链路样本，口径对齐第 06/07 章）：
 *   T1  单信道 × 波束（θ_B=4°，旁瓣 −30dB）—— 空分单独能救多少
 *   T2  随机200 信道 × 波束（θ_B=4°）       —— 频率 × 空分相乘
 *   T3  单信道 × 窄波束（θ_B=2°）           —— 波束宽度这一旋钮值多少分贝
 * 三种统计口径：
 *   A 全链路普查：每条链路自带波束指向自己（可比第 07 章全链路分布）
 *   B 贪心指向：波束打仰角最高星（第 05 章 greedy 口径，每采样 1 条）
 *   C 允许重指：每采样挑方向性 C/I 最优的指向
 * <p>
 * 手动跑：临时注释 @Disabled，跑完恢复。
 */
@Disabled("手动基准：真实星库全量空分扫描，本地按需运行")
class BeamIsolationBenchmarkTest {

    /** 与第 05/06/07 章基准对齐的仿真参考时刻 */
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final int DURATION_SEC = 7200;
    private static final int TICK_SEC = 30;

    private static final double ACIR_ADJ_DB = 30.0;
    private static final double CIR_THRESHOLD_DB = 9.0;
    /** 3dB 全波束宽度（度）—— Starlink 平板相控阵量级 */
    private static final double BEAM_W_DEG = 4.0;
    private static final double BEAM_W_NARROW_DEG = 2.0;
    /** 旁瓣封底电平（相对主瓣，dB） */
    private static final double SL_FLOOR_DB = -30.0;

    @Test
    void beamIsolation() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);
        List<OrbitalElements> sats = cat.satellites();
        int n = sats.size();
        System.out.printf("[catalog] parsed=%d rejected=%d dup=%d%n",
                cat.stats().parsed(), cat.stats().rejected(), cat.stats().duplicates());

        // 随机 200 信道（第 07 章档 4：真实频谱预算上限，固定种子）
        Random rnd = new Random(42);
        int[] randCh = new int[n];
        for (int i = 0; i < n; i++) randCh[i] = rnd.nextInt(200);
        Map<String, Integer> idxById = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idxById.put(sats.get(i).satelliteId(), i);

        Constellation constellation = new Constellation();
        constellation.replaceSatellites(sats);
        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        LinkCalculator links = new LinkCalculator();
        List<GroundStation> stations = constellation.getGroundStations();
        double noiseMw = dbmToMw(links.noiseFloorDbm());
        double aciMw = dbmToMw(-ACIR_ADJ_DB);

        int ticks = DURATION_SEC / TICK_SEC;
        Dist[] aDist = {new Dist(), new Dist(), new Dist()};   // 口径A：全链路
        Dist[] bDist = {new Dist(), new Dist(), new Dist()};   // 口径B：贪心指向
        Dist[] cDist = {new Dist(), new Dist(), new Dist()};   // 口径C：允许重指
        Dist mainlobeHits = new Dist();                        // 每链路主瓣内(θ<θ_B)干扰源数

        long scanStart = System.currentTimeMillis();
        for (int i = 0; i < ticks; i++) {
            Instant t = T0.plusSeconds((long) i * TICK_SEC);
            List<SatellitePosition> positions = new ArrayList<>(n);
            for (OrbitalElements sat : sats) positions.add(prop.propagate(sat, t));

            for (GroundStation st : stations) {
                List<LinkResult> vis = new ArrayList<>(256);
                List<Double> visMw = new ArrayList<>(256);
                List<Integer> visCh = new ArrayList<>(256);
                for (SatellitePosition p : positions) {
                    LinkResult lr = links.calculate(p, st);
                    if (!lr.visible()) continue;
                    vis.add(lr);
                    visMw.add(dbmToMw(links.receivedPowerDbm(lr)));
                    visCh.add(randCh[idxById.get(lr.satelliteId())]);
                }
                if (vis.isEmpty()) continue;
                int K = vis.size();

                // 服务星 = 仰角最高（第 05 章 greedy 口径）
                int srv = 0;
                for (int j = 1; j < K; j++)
                    if (vis.get(j).elevationDeg() > vis.get(srv).elevationDeg()) srv = j;

                double[] best = {-999, -999, -999};
                for (int j = 0; j < K; j++) {
                    LinkResult tj = vis.get(j);
                    double cj = visMw.get(j);
                    int chj = visCh.get(j);
                    double sWide = 0, sNarrow = 0, sFreq = 0, sFreqAci = 0;
                    int ml = 0;
                    for (int q = 0; q < K; q++) {
                        if (q == j) continue;
                        LinkResult ti = vis.get(q);
                        double theta = angularSep(ti.azimuthDeg(), ti.elevationDeg(),
                                tj.azimuthDeg(), tj.elevationDeg());
                        if (theta < BEAM_W_DEG) ml++;
                        double wWide = linDb(beamW(theta, BEAM_W_DEG));
                        double wNarrow = linDb(beamW(theta, BEAM_W_NARROW_DEG));
                        double pi = visMw.get(q);
                        sWide += pi * wWide;
                        sNarrow += pi * wNarrow;
                        int dch = Math.abs(visCh.get(q) - chj);
                        if (dch == 0) sFreq += pi * wWide;
                        else if (dch == 1) sFreqAci += pi * wWide;
                    }
                    mainlobeHits.add(ml);
                    double c1 = round2(10 * Math.log10(cj)
                            - 10 * Math.log10(sWide + noiseMw));
                    double c2 = round2(10 * Math.log10(cj)
                            - 10 * Math.log10(sFreq + sFreqAci + noiseMw));
                    double c3 = round2(10 * Math.log10(cj)
                            - 10 * Math.log10(sNarrow + noiseMw));
                    aDist[0].add(c1); aDist[1].add(c2); aDist[2].add(c3);
                    best[0] = Math.max(best[0], c1);
                    best[1] = Math.max(best[1], c2);
                    best[2] = Math.max(best[2], c3);
                    if (j == srv) { bDist[0].add(c1); bDist[1].add(c2); bDist[2].add(c3); }
                }
                for (int k = 0; k < 3; k++) cDist[k].add(best[k]);
            }
        }
        System.out.printf("== 相控阵空分基准（%dh, tick=%ds, 全星座扫描耗时 %.1fs）==%n%n",
                DURATION_SEC / 3600, TICK_SEC, (System.currentTimeMillis() - scanStart) / 1000.0);

        System.out.print("每链路主瓣内(θ<4°)干扰源数: "); mainlobeHits.print();
        System.out.println();
        String[] tiers = {"T1 单信道 × 波束(4°/−30dB)", "T2 随机200信道 × 波束(4°)", "T3 单信道 × 窄波束(2°)"};
        double[][] pass = new double[3][3];
        for (int k = 0; k < 3; k++) {
            System.out.printf("== %s ==%n", tiers[k]);
            System.out.print("  口径A 全链路普查 (dB)   : "); aDist[k].print();
            pass[k][0] = 100.0 * aDist[k].countAbove(CIR_THRESHOLD_DB) / aDist[k].n;
            System.out.printf("  口径A C/I ≥ %.0f dB      = %.4f%%%n", CIR_THRESHOLD_DB, pass[k][0]);
            System.out.print("  口径B 贪心指向 (dB)     : "); bDist[k].print();
            pass[k][1] = 100.0 * bDist[k].countAbove(CIR_THRESHOLD_DB) / bDist[k].n;
            System.out.printf("  口径B C/I ≥ %.0f dB      = %.4f%%%n", CIR_THRESHOLD_DB, pass[k][1]);
            System.out.print("  口径C 允许重指 (dB)     : "); cDist[k].print();
            pass[k][2] = 100.0 * cDist[k].countAbove(CIR_THRESHOLD_DB) / cDist[k].n;
            System.out.printf("  口径C C/I ≥ %.0f dB      = %.4f%%%n", CIR_THRESHOLD_DB, pass[k][2]);
            System.out.println();
        }

        System.out.println("== 空分阶梯汇总（过线比例 %）==");
        System.out.println("档位                        口径A全链路   口径B贪心   口径C重指");
        System.out.printf("第07章基线 单信道(无波束)        0.000      —         —%n");
        System.out.printf("第07章     随机200(无波束)       36.371      —         —%n");
        for (int k = 0; k < 3; k++)
            System.out.printf("%s   %8.3f  %8.3f  %8.3f%n", tiers[k], pass[k][0], pass[k][1], pass[k][2]);

        assertTrue(cat.stats().parsed() > 10_000);
    }

    /** 相控阵相对方向图：抛物线主瓣 + 旁瓣封底（dB，相对指向方向） */
    private static double beamW(double thetaDeg, double beamwidthDeg) {
        return Math.max(-12.0 * Math.pow(thetaDeg / beamwidthDeg, 2), SL_FLOOR_DB);
    }

    /** 两方向角距（度）：球面余弦定理，az 环绕由 cos(Δaz) 自然处理 */
    private static double angularSep(double az1, double el1, double az2, double el2) {
        double e1 = Math.toRadians(el1), e2 = Math.toRadians(el2);
        double dAz = Math.toRadians(az1 - az2);
        double cos = Math.sin(e1) * Math.sin(e2) + Math.cos(e1) * Math.cos(e2) * Math.cos(dAz);
        return Math.toDegrees(Math.acos(Math.min(1, Math.max(-1, cos))));
    }

    private static double linDb(double db) {
        return Math.pow(10, db / 10.0);
    }

    private static double dbmToMw(double dbm) {
        return Math.pow(10, dbm / 10.0);
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = BeamIsolationBenchmarkTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
