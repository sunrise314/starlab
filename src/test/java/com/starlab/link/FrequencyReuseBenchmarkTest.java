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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 复用第 06 章基准的分布统计类（同包嵌套类） */
import com.starlab.link.InterferenceBenchmarkTest.Dist;

/**
 * 第 07 章基准：频率复用阶梯实验。
 * <p>
 * 第 06 章证明单信道假设下 C/I 无解（中位 -25.2dB，需 2624 个正交信道）。
 * 但"均匀摊薄"是数学死路——真实频率规划的收益来自干扰源的几何聚集结构。
 * 本章在完全相同的口径下（T0/时长/tick/星库/5 站，与 InterferenceBenchmarkTest 对齐）
 * 对比四档信道分配方案的 C/I 分布：
 * <pre>
 *   档0  单信道（第 06 章基线，复现校验用）
 *   档1  按倾角壳分信道 —— 壳 = 倾角聚类（0.1° 容差）
 *   档2  按轨道面分信道 —— 面 = 壳内 RAAN 聚类（0.5° 容差）
 *   档3  面 × 极化      —— 面内按平近点角排序奇偶分双极化
 *   档4  随机均匀 200   —— 对照组，真实频谱预算上限（Ku 下行 2GHz×双极化÷20MHz）
 *   档5  随机均匀 nPlanes   —— 对照组（与档2 同信道数）
 *   档6  随机均匀 nPlanes×2 —— 对照组（与档3 同信道数）
 * </pre>
 * 信道隔离模型：两星的信道指数差 d → 功率隔离度 isol(d)：
 * 同信道 0dB；相邻信道 ACIR=30dB（沿用第 6 章）；相隔 ≥2 信道忽略。
 * 正交极化按 XPD=20dB 单独一档。
 * 档0 与第 6 章公式的唯一差别是第 6 章给每颗干扰源同时记了 co+aci 两笔
 * （aci 仅占功率 0.1%），对 C/I 影响 <0.01dB，数字可直接对比。
 * <p>
 * 手动跑：surefire fork 出的 JVM 不认 -Djunit.jupiter.conditions.deactivate，
 * 临时注释掉下方 @Disabled 执行，跑完记得恢复。
 */
@Disabled("手动基准：真实星库频率复用全量扫描，本地按需运行")
class FrequencyReuseBenchmarkTest {

    /** 与第 05/06 章基准对齐的仿真参考时刻 */
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final int DURATION_SEC = 7200;
    private static final int TICK_SEC = 30;

    private static final double ACIR_ADJ_DB = 30.0;   // 相邻信道隔离度（对齐第 06 章）
    private static final double XPD_DB = 20.0;        // 正交极化隔离度
    private static final double CIR_THRESHOLD_DB = 9.0;

    @Test
    void reuseLadder() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);
        List<OrbitalElements> sats = cat.satellites();
        int n = sats.size();
        System.out.printf("[catalog] parsed=%d rejected=%d dup=%d%n",
                cat.stats().parsed(), cat.stats().rejected(), cat.stats().duplicates());

        // ══ 信道分配：壳（倾角聚类）→ 面（壳内 RAAN 聚类）→ 极化（面内奇偶）══
        Map<String, Integer> idxById = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) idxById.put(sats.get(i).satelliteId(), i);

        // 1) 壳：按倾角排序，相邻间隔 > 0.1° 即新壳
        List<OrbitalElements> byInc = new ArrayList<>(sats);
        byInc.sort(Comparator.comparingDouble(OrbitalElements::inclination));
        List<Integer> shellStarts = new ArrayList<>();
        for (int i = 0; i < n; i++)
            if (i == 0 || byInc.get(i).inclination() - byInc.get(i - 1).inclination() > 0.1)
                shellStarts.add(i);
        int nShells = shellStarts.size();
        int[] shellOf = new int[n];
        double[] shellInc = new double[nShells];
        int[] shellCnt = new int[nShells];
        for (int s = 0; s < nShells; s++) {
            int from = shellStarts.get(s);
            int to = (s + 1 < nShells) ? shellStarts.get(s + 1) : n;
            for (int i = from; i < to; i++) {
                int gi = idxById.get(byInc.get(i).satelliteId());
                shellOf[gi] = s;
                shellInc[s] += byInc.get(i).inclination();
                shellCnt[s]++;
            }
            shellInc[s] /= shellCnt[s];
        }
        System.out.printf("%n[shells] 倾角聚类（容差 0.1°）→ %d 个壳：%n", nShells);
        for (int s = 0; s < nShells; s++)
            System.out.printf("  shell#%d  inc≈%.2f°  sats=%d%n", s, shellInc[s], shellCnt[s]);

        // 2) 面：壳内按 RAAN（环绕修正后）聚类，间隔 > 0.5° 即新面
        int[] planeOf = new int[n];
        int[] polOf = new int[n];
        List<List<OrbitalElements>> byShell = new ArrayList<>();
        for (int s = 0; s < nShells; s++) byShell.add(new ArrayList<>());
        for (OrbitalElements e : sats) byShell.get(shellOf[idxById.get(e.satelliteId())]).add(e);

        List<Integer> planeShell = new ArrayList<>();
        List<int[]> planeAdj = new ArrayList<>();     // 每面 {prev, next}（壳内 RAAN 序）
        List<Integer> planeSize = new ArrayList<>();
        int planeId = 0;
        List<Integer> planesPerShell = new ArrayList<>();
        for (int s = 0; s < nShells; s++) {
            List<OrbitalElements> members = byShell.get(s);
            members.sort(Comparator.comparingDouble(FrequencyReuseBenchmarkTest::raanWrapped));
            List<Integer> planeStarts = new ArrayList<>();
            for (int i = 0; i < members.size(); i++)
                if (i == 0 || raanWrapped(members.get(i)) - raanWrapped(members.get(i - 1)) > 0.5)
                    planeStarts.add(i);
            List<Integer> thisShellPlanes = new ArrayList<>();
            for (int ps = 0; ps < planeStarts.size(); ps++) {
                int from = planeStarts.get(ps);
                int to = (ps + 1 < planeStarts.size()) ? planeStarts.get(ps + 1) : members.size();
                List<OrbitalElements> pm = new ArrayList<>(members.subList(from, to));
                pm.sort(Comparator.comparingDouble(OrbitalElements::meanAnomaly));
                for (int j = 0; j < pm.size(); j++) {
                    int gi = idxById.get(pm.get(j).satelliteId());
                    planeOf[gi] = planeId;
                    polOf[gi] = j % 2;
                }
                planeShell.add(s);
                planeSize.add(pm.size());
                thisShellPlanes.add(planeId);
                planeId++;
            }
            for (int a = 0; a < thisShellPlanes.size(); a++)
                planeAdj.add(new int[]{
                        a > 0 ? thisShellPlanes.get(a - 1) : -1,
                        a < thisShellPlanes.size() - 1 ? thisShellPlanes.get(a + 1) : -1});
            planesPerShell.add(thisShellPlanes.size());
        }
        int nPlanes = planeId;
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < nShells; s++) sb.append(String.format("%.0f°:%d面 ", shellInc[s], planesPerShell.get(s)));
        System.out.printf("[planes] RAAN 聚类（容差 0.5°）→ 共 %d 个轨道面（%s）极化×2 = %d 组%n%n",
                nPlanes, sb.toString().trim(), nPlanes * 2);

        // 对照组：随机均匀分配（每颗卫星永久占用一个随机信道，固定种子可复现）
        // 200 ≈ Ku 用户下行 2GHz × 双极化 ÷ 20MHz —— 现实世界的频谱预算上限
        Random rnd = new Random(42);
        int[] randCounts = {200, nPlanes, nPlanes * 2};
        int[][] randCh = new int[3][];
        for (int k = 0; k < 3; k++) {
            randCh[k] = new int[n];
            for (int i = 0; i < n; i++) randCh[k][i] = rnd.nextInt(randCounts[k]);
        }

        // ══ 七档阶梯：同一份可见链路样本上算七套 C/I ══
        Constellation constellation = new Constellation();
        constellation.replaceSatellites(sats);
        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        LinkCalculator links = new LinkCalculator();
        List<GroundStation> stations = constellation.getGroundStations();
        double noiseMw = dbmToMw(links.noiseFloorDbm());
        double aciMw = dbmToMw(-ACIR_ADJ_DB);   // 相邻信道泄漏系数 0.001
        double xpdMw = dbmToMw(-XPD_DB);        // 正交极化泄漏系数 0.01

        int ticks = DURATION_SEC / TICK_SEC;
        Dist[] cir = new Dist[7], best = new Dist[7], kEff = new Dist[7];
        for (int t = 0; t < 7; t++) { cir[t] = new Dist(); best[t] = new Dist(); kEff[t] = new Dist(); }

        long scanStart = System.currentTimeMillis();
        for (int i = 0; i < ticks; i++) {
            Instant t = T0.plusSeconds((long) i * TICK_SEC);
            List<SatellitePosition> positions = new ArrayList<>(n);
            for (OrbitalElements sat : sats) positions.add(prop.propagate(sat, t));

            for (GroundStation st : stations) {
                // 单次遍历：可见集合 + 各分组功率/计数合计
                List<LinkResult> vis = new ArrayList<>(256);
                List<Integer> visIdx = new ArrayList<>(256);
                List<Double> visMw = new ArrayList<>(256);
                double totalMw = 0;
                double[] sumShell = new double[nShells], sumPlane = new double[nPlanes], sumPP = new double[nPlanes * 2];
                int[] cntShell = new int[nShells], cntPlane = new int[nPlanes], cntPP = new int[nPlanes * 2];
                double[][] sumR = {new double[200], new double[nPlanes], new double[nPlanes * 2]};
                int[][] cntR = {new int[200], new int[nPlanes], new int[nPlanes * 2]};
                for (SatellitePosition p : positions) {
                    LinkResult lr = links.calculate(p, st);
                    if (!lr.visible()) continue;
                    int gi = idxById.get(lr.satelliteId());
                    double mw = dbmToMw(links.receivedPowerDbm(lr));
                    vis.add(lr); visIdx.add(gi); visMw.add(mw);
                    totalMw += mw;
                    sumShell[shellOf[gi]] += mw; cntShell[shellOf[gi]]++;
                    sumPlane[planeOf[gi]] += mw; cntPlane[planeOf[gi]]++;
                    int pp = planeOf[gi] * 2 + polOf[gi];
                    sumPP[pp] += mw; cntPP[pp]++;
                    for (int k = 0; k < 3; k++) {
                        int rc = randCh[k][gi];
                        sumR[k][rc] += mw; cntR[k][rc]++;
                    }
                }
                if (vis.isEmpty()) continue;
                kEff[0].add(vis.size() - 1);

                double best0 = -999, best1 = -999, best2 = -999, best3 = -999;
                double[] bestR = {-999, -999, -999};
                for (int v = 0; v < vis.size(); v++) {
                    LinkResult lr = vis.get(v);
                    int gi = visIdx.get(v);
                    double c = links.receivedPowerDbm(lr);
                    double own = visMw.get(v);
                    int sh = shellOf[gi], pl = planeOf[gi], pol = polOf[gi];

                    // 档0 单信道：所有可见卫星同频
                    double i0 = (totalMw - own) + noiseMw;
                    // 档1 按壳：同壳 0dB，相邻壳（倾角序）ACIR
                    double i1 = (sumShell[sh] - own) + noiseMw;
                    if (sh > 0) i1 += sumShell[sh - 1] * aciMw;
                    if (sh < nShells - 1) i1 += sumShell[sh + 1] * aciMw;
                    // 档2 按面：同面 0dB，相邻面（壳内 RAAN 序）ACIR
                    double i2 = (sumPlane[pl] - own) + noiseMw;
                    int[] adj = planeAdj.get(pl);
                    if (adj[0] >= 0) i2 += sumPlane[adj[0]] * aciMw;
                    if (adj[1] >= 0) i2 += sumPlane[adj[1]] * aciMw;
                    // 档3 面×极化：同面同极化 0dB，同面对极化 XPD，相邻面 ACIR
                    int pp = pl * 2 + pol;
                    double otherPol = sumPlane[pl] - sumPP[pp];           // 同面对极化合计（不含自身）
                    double i3 = (sumPP[pp] - own) + otherPol * xpdMw + noiseMw;
                    if (adj[0] >= 0) i3 += sumPlane[adj[0]] * aciMw;
                    if (adj[1] >= 0) i3 += sumPlane[adj[1]] * aciMw;
                    // 档4/5/6 随机均匀对照：同随机信道 0dB，±1 信道 ACIR
                    for (int k = 0; k < 3; k++) {
                        int rc = randCh[k][gi];
                        double ik = (sumR[k][rc] - own) + noiseMw;
                        if (rc > 0) ik += sumR[k][rc - 1] * aciMw;
                        if (rc < sumR[k].length - 1) ik += sumR[k][rc + 1] * aciMw;
                        double ck = round2(c - 10 * Math.log10(ik));
                        cir[4 + k].add(ck);
                        bestR[k] = Math.max(bestR[k], ck);
                        kEff[4 + k].add(cntR[k][rc] - 1);
                    }

                    double c0 = round2(c - 10 * Math.log10(i0));
                    double c1 = round2(c - 10 * Math.log10(i1));
                    double c2 = round2(c - 10 * Math.log10(i2));
                    double c3 = round2(c - 10 * Math.log10(i3));
                    cir[0].add(c0); cir[1].add(c1); cir[2].add(c2); cir[3].add(c3);
                    best0 = Math.max(best0, c0); best1 = Math.max(best1, c1);
                    best2 = Math.max(best2, c2); best3 = Math.max(best3, c3);
                    kEff[1].add(cntShell[sh] - 1);
                    kEff[2].add(cntPlane[pl] - 1);
                    kEff[3].add(cntPP[pp] - 1);
                }
                best[0].add(best0); best[1].add(best1); best[2].add(best2); best[3].add(best3);
                for (int k = 0; k < 3; k++) best[4 + k].add(bestR[k]);
            }
        }
        long scanMs = System.currentTimeMillis() - scanStart;
        System.out.printf("== 频率复用阶梯（%dh, tick=%ds, 全星座扫描耗时 %.1fs）==%n%n",
                DURATION_SEC / 3600, TICK_SEC, scanMs / 1000.0);

        String[] names = {"档0 单信道（第06章基线）", "档1 按倾角壳分信道", "档2 按轨道面分信道",
                "档3 面 × 极化", "档4 随机均匀200（频谱预算上限）", "档5 随机均匀383", "档6 随机均匀766"};
        double[] med = new double[7];
        for (int t = 0; t < 7; t++) {
            System.out.printf("== %s ==%n", names[t]);
            System.out.print("  C/I 全部可见链路 (dB)   : "); cir[t].print();
            System.out.printf("  C/I ≥ %.0f dB 比例       = %.4f%%%n",
                    CIR_THRESHOLD_DB, 100.0 * cir[t].countAbove(CIR_THRESHOLD_DB) / cir[t].n);
            System.out.print("  每采样最好一颗 C/I (dB) : "); best[t].print();
            System.out.printf("  最好一颗 ≥ %.0f dB 比例  = %.4f%%%n",
                    CIR_THRESHOLD_DB, 100.0 * best[t].countAbove(CIR_THRESHOLD_DB) / best[t].n);
            System.out.print("  同信道邻居 K (按链路)   : "); kEff[t].print();
            System.out.println();
            med[t] = cir[t].median();
        }

        System.out.println("== 复用阶梯汇总 ==");
        System.out.println("档位                      信道数   C/I中位   ≥9dB比例   同信道K中位   等效正交信道数");
        System.out.printf("档0 单信道                %-7d %7.1f   %6.3f%%   %10.0f   %s%n",
                1, med[0], 100.0 * cir[0].countAbove(9.0) / cir[0].n, kEff[0].median(), "1（基准）");
        int[] chCount = {1, nShells, nPlanes, nPlanes * 2, 200, nPlanes, nPlanes * 2};
        String[] tiers = {"档1 按壳     ", "档2 按面     ", "档3 面×极化  ",
                "档4 随机200  ", "档5 随机383  ", "档6 随机766  "};
        for (int t = 1; t < 7; t++) {
            double mEff = Math.pow(10, (med[t] - med[0]) / 10);
            System.out.printf("%s   %-7d %7.1f   %6.3f%%   %10.0f   %.0f%n",
                    tiers[t - 1], chCount[t], med[t], 100.0 * cir[t].countAbove(9.0) / cir[t].n,
                    kEff[t].median(), mEff);
        }
        System.out.println("(等效正交信道数 = 10^((该档C/I中位 − 档0)/10)；干扰压到噪声底以下后 C/I 被 SNR≈30dB 封顶，该指标饱和失真)");

        assertTrue(cat.stats().parsed() > 10_000);
    }

    /** RAAN 环绕修正：>359° 折到负半轴，避免 0° 面的成员被排到队列尾部 */
    private static double raanWrapped(OrbitalElements e) {
        double r = e.raan();
        return r > 359.0 ? r - 360.0 : r;
    }

    private static double dbmToMw(double dbm) {
        return Math.pow(10, dbm / 10.0);
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = FrequencyReuseBenchmarkTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
