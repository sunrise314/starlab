package com.starlab.link;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.handover.GreedyHandoverStrategy;
import com.starlab.handover.HandoverEvent;
import com.starlab.handover.HandoverStrategy;
import com.starlab.handover.InterferenceAwareHandoverStrategy;
import com.starlab.handover.PredictiveHandoverStrategy;
import com.starlab.handover.SimulationEngine;
import com.starlab.handover.StationSelection;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 06 章基准：真实星链星库（10681 颗）下的同频干扰普查 + 三策略对决。
 * <p>
 * 三个问题：
 *   A. 干扰普查：每个(时刻×站)有多少颗可见卫星构成同频干扰源？可见链路的 C/I 分布如何？
 *      噪声底与干扰合计差多少（噪声受限 vs 干扰受限）？
 *   B. 黄金校验：快速版干扰感知策略 ≡ 生产版 InterferenceAwareHandoverStrategy（逐决策对齐），
 *      并实测生产版单次决策耗时，外推全量仿真的不可行性（O(N²) 计算爆炸）。
 *   C. 三方对决：greedy / predictive / interference-aware 在同一份星库上的 2h 仿真对比。
 *      注意引擎事件流只记录 ELEVATION_FALL/PREDICTIVE/LINK_BREAK，v3 的 INTERFERENCE_SWITCH
 *      是静默切换——真实站间倒手次数靠策略内置计数器记账。
 * <p>
 * 手动跑（约 1-2 分钟）：surefire fork 出的 JVM 不认 -Djunit.jupiter.conditions.deactivate，
 * 直接临时注释掉下方 @Disabled 执行，跑完记得恢复。
 */
@Disabled("手动基准：真实星库全量干扰仿真，本地按需运行")
class InterferenceBenchmarkTest {

    /** 仿真参考时刻（与第 05 章基准对齐，TLE 历元邻近） */
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    private static final int DURATION_SEC = 7200;
    private static final int TICK_SEC = 30;

    /** 与 InterferenceCalculator / InterferenceAwareHandoverStrategy 对齐的门限 */
    private static final double ACI_ISOLATION_DB = 30.0;
    private static final double CIR_THRESHOLD_DB = 9.0;

    @Test
    void realStarlinkInterference() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);
        List<OrbitalElements> sats = cat.satellites();
        System.out.printf("[catalog] parsed=%d rejected=%d dup=%d%n",
                cat.stats().parsed(), cat.stats().rejected(), cat.stats().duplicates());

        Constellation constellation = new Constellation();
        constellation.replaceSatellites(sats);
        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        LinkCalculator links = new LinkCalculator();
        List<GroundStation> stations = constellation.getGroundStations();
        InterferenceCalculator prodCalc = new InterferenceCalculator(links);

        double noiseDbm = links.noiseFloorDbm();
        double noiseMw = dbmToMw(noiseDbm);
        System.out.printf("[noise] 接收机噪声底（20MHz + NF3dB）= %.2f dBm%n", noiseDbm);

        // ══ Part A：干扰普查（240 tick × 5 站 × 10681 星）══
        int ticks = DURATION_SEC / TICK_SEC;
        Dist kDist = new Dist();        // 每(时刻×站)同频邻居数
        Dist cirDist = new Dist();      // 全部可见链路的 C/I
        Dist bestCirDist = new Dist();  // 每(时刻×站)最好一颗的 C/I
        Dist inOverNoise = new Dist();  // 干扰合计(不含噪声)高出噪声底 dB
        Dist snrDist = new Dist();      // 可见链路 SNR（无干扰视角对照）
        Map<String, long[]> kByStation = new LinkedHashMap<>();
        for (GroundStation st : stations) kByStation.put(st.id(), new long[2]); // [sumK, n]

        long scanStart = System.currentTimeMillis();
        for (int i = 0; i < ticks; i++) {
            Instant t = T0.plusSeconds((long) i * TICK_SEC);
            List<SatellitePosition> positions = new ArrayList<>(sats.size());
            for (OrbitalElements sat : sats) positions.add(prop.propagate(sat, t));

            for (GroundStation st : stations) {
                List<LinkResult> vis = new ArrayList<>();
                double sCo = 0, sAci = 0;
                for (SatellitePosition p : positions) {
                    LinkResult lr = links.calculate(p, st);
                    if (!lr.visible()) continue;
                    vis.add(lr);
                    double pw = links.receivedPowerDbm(lr);
                    sCo += dbmToMw(pw);
                    sAci += dbmToMw(pw - ACI_ISOLATION_DB);
                }
                kDist.add(vis.size());
                kByStation.get(st.id())[0] += vis.size();
                kByStation.get(st.id())[1] += 1;
                if (vis.isEmpty()) continue;

                double bestCir = -999;
                for (LinkResult lr : vis) {
                    double c = links.receivedPowerDbm(lr);
                    // 与 InterferenceCalculator.analyze 同公式：扣除自身后的干扰合计
                    double iMw = (sCo - dbmToMw(c)) + (sAci - dbmToMw(c - ACI_ISOLATION_DB)) + noiseMw;
                    double cir = round2(c - 10 * Math.log10(iMw));
                    cirDist.add(cir);
                    snrDist.add(lr.snrDb());
                    if (cir > bestCir) bestCir = cir;
                }
                bestCirDist.add(bestCir);
                inOverNoise.add(10 * Math.log10(sCo + sAci) - noiseDbm);
            }
        }
        long scanMs = System.currentTimeMillis() - scanStart;

        System.out.println();
        System.out.printf("== Part A: 干扰普查（%dh, tick=%ds, 全星座扫描耗时 %.1fs）==%n",
                DURATION_SEC / 3600, TICK_SEC, scanMs / 1000.0);
        System.out.print("同频邻居数 K（每 时刻×站）: "); kDist.print();
        System.out.print("  各站平均 K: ");
        for (Map.Entry<String, long[]> e : kByStation.entrySet()) {
            System.out.printf("%s=%.0f ", e.getKey(), e.getValue()[1] == 0 ? 0 : (double) e.getValue()[0] / e.getValue()[1]);
        }
        System.out.println();
        System.out.print("可见链路 C/I (dB)        : "); cirDist.print();
        System.out.printf("  C/I ≥ %.0f dB（可解调）比例 = %.4f%%%n",
                CIR_THRESHOLD_DB, 100.0 * cirDist.countAbove(CIR_THRESHOLD_DB) / cirDist.n);
        System.out.print("每(时刻×站)最好一颗的 C/I: "); bestCirDist.print();
        System.out.printf("  最好一颗 ≥ %.0f dB 比例 = %.4f%%%n",
                CIR_THRESHOLD_DB, 100.0 * bestCirDist.countAbove(CIR_THRESHOLD_DB) / bestCirDist.n);
        System.out.print("干扰合计高出噪声底 (dB)  : "); inOverNoise.print();
        System.out.print("对照：可见链路 SNR (dB)  : "); snrDist.print();
        double medCir = cirDist.median();
        System.out.printf("若要中位链路 C/I ≥ %.0f dB，需要的正交信道数 M ≈ 10^((9-(%.1f))/10) = %.0f%n",
                CIR_THRESHOLD_DB, medCir, Math.pow(10, (CIR_THRESHOLD_DB - medCir) / 10));

        // ══ Part B：快速版 ≡ 生产版 黄金校验 + O(N²) 外推 ══
        FastInterferenceAwareStrategy fast = new FastInterferenceAwareStrategy(
                sats, prop, links, stations, noiseMw);
        InterferenceAwareHandoverStrategy prodStrategy = new InterferenceAwareHandoverStrategy(
                constellation, prop, links, prodCalc);

        // T0 全星座位置（生产 analyze 的干扰源枚举输入）
        List<SatellitePosition> posT0 = new ArrayList<>(sats.size());
        for (OrbitalElements sat : sats) posT0.add(prop.propagate(sat, T0));

        System.out.println();
        System.out.println("== Part B: 快速版 ≡ 生产版 黄金校验 ==");
        double maxCirDiff = 0;
        int agree = 0, checked = 0;
        long prodCostNs = 0;
        for (int i = 0; i < sats.size() && checked < 12; i += 883) {
            OrbitalElements sat = sats.get(i);
            SatellitePosition pos = prop.propagate(sat, T0);
            // 数值对齐：可见 (星,站) 组合的生产 analyze C/I vs 快速缓存 C/I
            for (GroundStation st : stations) {
                LinkResult base = links.calculate(pos, st);
                if (!base.visible()) continue;
                double expect = prodCalc.analyze(pos, st, posT0).cirDb();
                double actual = fast.cirAt(T0, sat.satelliteId(), st.id());
                maxCirDiff = Math.max(maxCirDiff, Math.abs(expect - actual));
            }
            // 决策对齐：三种 cur 状态下选站与切换原因必须逐位一致
            for (String cur : new String[]{null, "BJO", "SHO"}) {
                StationSelection fs = fast.selectBestStation(sat, T0, cur);
                long bt = System.nanoTime();
                StationSelection ps = prodStrategy.selectBestStation(sat, T0, cur);
                prodCostNs += System.nanoTime() - bt;
                String fst = fs.station() == null ? null : fs.station().id();
                String pst = ps.station() == null ? null : ps.station().id();
                assertEquals(pst, fst, "选站不一致 sat=" + sat.satelliteId() + " cur=" + cur);
                assertEquals(ps.reason(), fs.reason(), "切换原因不一致 sat=" + sat.satelliteId() + " cur=" + cur);
                agree++;
            }
            checked++;
        }
        double avgProdMs = prodCostNs / 1e6 / agree;
        long estCalls = (long) ticks * sats.size();
        System.out.printf("抽样 %d 星 × 3 种 cur = %d 次决策：一致率 %d/%d，C/I 最大偏差 %.4f dB%n",
                checked, agree, agree, agree, maxCirDiff);
        System.out.printf("生产版单次决策平均 %.1f ms（全星座重传播+全量干扰扫描）", avgProdMs);
        System.out.printf(" → 全量 %dh 仿真需调用于 %d 次 ≈ %.1f 小时（不可行，故 Part C 用快速等价版）%n",
                DURATION_SEC / 3600, estCalls, avgProdMs * estCalls / 3.6e6);
        System.out.printf("快速版每 tick 摊平一次全星座扫描，实测 %.0f ms/tick（黄金校验阶段 build %d 次）%n",
                fast.avgBuildMs(), fast.buildCount);

        // ══ Part C：三方对决（greedy / predictive / interference-aware）══
        GreedyHandoverStrategy greedy = new GreedyHandoverStrategy(constellation, prop, links);
        PredictiveHandoverStrategy predictive = new PredictiveHandoverStrategy(constellation, prop, links);
        SimulationEngine engine = new SimulationEngine(constellation, prop, links, prodCalc, greedy);

        long bt = System.currentTimeMillis();
        SimulationEngine.TripleCompareResult triple =
                engine.compareThree(greedy, predictive, fast, T0, DURATION_SEC, TICK_SEC);
        long simMs = System.currentTimeMillis() - bt;

        System.out.println();
        System.out.printf("== Part C: 三方对决（%dh, tick=%ds, 仿真耗时 %.0fs）==%n",
                DURATION_SEC / 3600, TICK_SEC, simMs / 1000.0);
        for (SimulationEngine.SimulationResult r : List.of(triple.result1(), triple.result2(), triple.result3())) {
            Map<HandoverEvent.TriggerType, Integer> byType = new LinkedHashMap<>();
            Map<String, Integer> loadByStation = new LinkedHashMap<>();
            for (HandoverEvent ev : r.events()) {
                byType.merge(ev.triggerType(), 1, Integer::sum);
                if (ev.toStationId() != null) loadByStation.merge(ev.toStationId(), 1, Integer::sum);
            }
            System.out.printf("[%s] 事件总数=%d  %s%n", r.algorithmId(), r.handoverCount(), byType);
            System.out.println("  事件流每站承接(不含静默切换/INITIAL): " + loadByStation);
        }
        System.out.printf("[对比] 平均切换时延: greedy=%.1fms predictive=%.1fms interference=%.1fms | LINK_BREAK: %d/%d/%d%n",
                triple.avgLatency1(), triple.avgLatency2(), triple.avgLatency3(),
                triple.linkBreakCount1(), triple.linkBreakCount2(), triple.linkBreakCount3());

        System.out.println();
        System.out.println("[interference-aware] 决策账本（引擎事件流看不到的静默切换在这里）:");
        System.out.println("  reason 分布: " + fast.reasonCount);
        int silent = fast.reasonCount.getOrDefault("INTERFERENCE_SWITCH", 0)
                + fast.reasonCount.getOrDefault("LINK_BREAK", 0)
                + fast.reasonCount.getOrDefault("ELEVATION_SWITCH", 0);
        System.out.printf("  真实站间倒手=%d（事件流只记了 ELEVATION_SWITCH，静默切换=%d）%n",
                silent, fast.reasonCount.getOrDefault("INTERFERENCE_SWITCH", 0)
                        + fast.reasonCount.getOrDefault("LINK_BREAK", 0));
        System.out.println("  真实每站承接(切换,不含INITIAL): " + fast.loadSwitchByStation);
        System.out.println("  首次接入(INITIAL)每站分布: " + fast.loadInitialByStation);

        assertEquals(0, maxCirDiff > 0.011 ? 1 : 0, "快速版与生产版 C/I 偏差超限");
        assertTrue(cat.stats().parsed() > 10_000);
    }

    // ──────────────────────────────────────────────────────────────

    /** 快速等价版 v3 策略：每 tick 摊平一次全星座扫描，决策逻辑与生产版逐位一致 */
    static final class FastInterferenceAwareStrategy implements HandoverStrategy {

        private static final double PROCESSING_MS = 5.0;
        private static final double CIR_THRESHOLD_DB = 9.0;
        private static final double IMPROVEMENT_HYSTERESIS_DB = 2.0;

        private final List<OrbitalElements> sats;
        private final OrbitPropagator prop;
        private final LinkCalculator links;
        private final List<GroundStation> stations;
        private final double noiseMw;
        private final Map<String, Integer> idxById = new HashMap<>();
        private final Map<Instant, TickData> cache = new HashMap<>();

        // 引擎事件流只记录 ELEVATION_SWITCH，v3 的静默切换靠这里记账
        final Map<String, Integer> reasonCount = new LinkedHashMap<>();
        final Map<String, Integer> loadSwitchByStation = new LinkedHashMap<>();
        final Map<String, Integer> loadInitialByStation = new LinkedHashMap<>();

        private long totalBuildNs;
        private int buildCount;

        FastInterferenceAwareStrategy(List<OrbitalElements> sats, OrbitPropagator prop,
                                      LinkCalculator links, List<GroundStation> stations,
                                      double noiseMw) {
            this.sats = sats;
            this.prop = prop;
            this.links = links;
            this.stations = stations;
            this.noiseMw = noiseMw;
            for (int i = 0; i < sats.size(); i++) idxById.put(sats.get(i).satelliteId(), i);
        }

        @Override public String algorithmId() { return "interference-aware"; }
        @Override public String algorithmName() { return "干扰感知 C/I 优先（快速等价版）"; }

        record LinkData(LinkResult link, double cir) {}
        record TickData(Map<String, Map<Integer, LinkData>> byStation) {}

        /** 该星在该站该时刻的缓存 C/I（黄金校验用） */
        double cirAt(Instant t, String satId, String stationId) {
            TickData td = cache.computeIfAbsent(t, this::buildTick);
            LinkData ld = td.byStation.get(stationId).get(idxById.get(satId));
            return ld == null ? -999 : ld.cir;
        }

        @Override
        public StationSelection selectBestStation(OrbitalElements sat, Instant time, String currentStationId) {
            TickData td = cache.computeIfAbsent(time, this::buildTick);
            int me = idxById.get(sat.satelliteId());

            GroundStation bestSt = null;
            LinkResult bestLink = null;
            double bestCir = -999;
            LinkData curData = null;
            for (GroundStation st : stations) {
                LinkData ld = td.byStation.get(st.id()).get(me);
                if (ld == null) continue;
                if (ld.cir > bestCir) { bestCir = ld.cir; bestSt = st; bestLink = ld.link; }
                if (st.id().equals(currentStationId)) curData = ld;
            }
            if (bestSt == null) {
                reasonCount.merge("NO_VISIBLE", 1, Integer::sum);
                return new StationSelection(null, null, false, 0, 0, StationSelection.Reason.NO_VISIBLE);
            }

            boolean handoverNeeded;
            StationSelection.Reason reason;
            if (currentStationId == null) {
                reason = StationSelection.Reason.INITIAL;
                handoverNeeded = true;
            } else if (!bestSt.id().equals(currentStationId)) {
                if (curData != null && curData.cir < CIR_THRESHOLD_DB) {
                    reason = StationSelection.Reason.INTERFERENCE_SWITCH;
                    handoverNeeded = true;
                } else if (curData == null) {
                    reason = StationSelection.Reason.LINK_BREAK;
                    handoverNeeded = true;
                } else if (bestCir - curData.cir >= IMPROVEMENT_HYSTERESIS_DB) {
                    reason = StationSelection.Reason.INTERFERENCE_SWITCH;
                    handoverNeeded = true;
                } else if (bestCir >= CIR_THRESHOLD_DB && curData.cir >= CIR_THRESHOLD_DB) {
                    reason = StationSelection.Reason.ELEVATION_SWITCH;
                    handoverNeeded = true;
                } else {
                    reason = StationSelection.Reason.NONE;
                    handoverNeeded = false;
                }
            } else {
                reason = StationSelection.Reason.NONE;
                handoverNeeded = false;
            }

            reasonCount.merge(reason.name(), 1, Integer::sum);
            if (handoverNeeded) {
                if (reason == StationSelection.Reason.INITIAL) {
                    loadInitialByStation.merge(bestSt.id(), 1, Integer::sum);
                } else {
                    loadSwitchByStation.merge(bestSt.id(), 1, Integer::sum);
                }
            }

            double propMs = bestLink.rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
            return new StationSelection(bestSt, bestLink, handoverNeeded, propMs,
                    PROCESSING_MS + propMs, reason);
        }

        /** 每 tick 一次的全星座扫描：位置传播 + 每站可见集合 + 干扰功率合计（含自身，用时再扣） */
        private TickData buildTick(Instant t) {
            long b = System.nanoTime();
            List<SatellitePosition> positions = new ArrayList<>(sats.size());
            for (OrbitalElements el : sats) positions.add(prop.propagate(el, t));

            Map<String, Map<Integer, LinkData>> byStation = new LinkedHashMap<>();
            for (GroundStation st : stations) {
                List<LinkResult> vis = new ArrayList<>();
                double sCo = 0, sAci = 0;
                for (SatellitePosition p : positions) {
                    LinkResult lr = links.calculate(p, st);
                    if (!lr.visible()) continue;
                    vis.add(lr);
                    double pw = links.receivedPowerDbm(lr);
                    sCo += dbmToMw(pw);
                    sAci += dbmToMw(pw - ACI_ISOLATION_DB);
                }
                Map<Integer, LinkData> m = new HashMap<>(vis.size() * 2);
                for (LinkResult lr : vis) {
                    double c = links.receivedPowerDbm(lr);
                    double iMw = (sCo - dbmToMw(c)) + (sAci - dbmToMw(c - ACI_ISOLATION_DB)) + noiseMw;
                    m.put(idxById.get(lr.satelliteId()), new LinkData(lr, round2(c - 10 * Math.log10(iMw))));
                }
                byStation.put(st.id(), m);
            }
            totalBuildNs += System.nanoTime() - b;
            buildCount++;
            return new TickData(byStation);
        }

        double avgBuildMs() { return buildCount == 0 ? 0 : totalBuildNs / 1e6 / buildCount; }
        double totalBuildMs() { return totalBuildNs / 1e6; }
    }

    /** 单变量分布统计：n/min/p10/med/mean/p90/p99/max + 超阈计数 */
    static final class Dist {
        final List<Double> v = new ArrayList<>();
        int n;
        double sum, min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        int aboveCount;
        double aboveThreshold = Double.NaN;

        void add(double x) {
            v.add(x);
            n++;
            sum += x;
            if (x < min) min = x;
            if (x > max) max = x;
        }

        int countAbove(double threshold) {
            if (aboveThreshold != threshold) {
                aboveCount = 0;
                for (double x : v) if (x >= threshold) aboveCount++;
                aboveThreshold = threshold;
            }
            return aboveCount;
        }

        double percentile(double p) {
            if (v.isEmpty()) return 0;
            List<Double> s = new ArrayList<>(v);
            s.sort(Comparator.comparingDouble(Double::doubleValue));
            int idx = (int) Math.floor(p * (s.size() - 1));
            return s.get(idx);
        }

        double median() { return percentile(0.5); }

        void print() {
            System.out.printf("n=%d  min=%.1f  p10=%.1f  med=%.1f  mean=%.1f  p90=%.1f  p99=%.1f  max=%.1f%n",
                    n, min, percentile(0.1), median(), sum / n, percentile(0.9), percentile(0.99), max);
        }
    }

    private static double dbmToMw(double dbm) {
        return Math.pow(10, dbm / 10.0);
    }

    private static double round2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = InterferenceBenchmarkTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
