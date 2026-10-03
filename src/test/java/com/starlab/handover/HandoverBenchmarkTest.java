package com.starlab.handover;

import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 05 章基准：真实星链星库（10681 颗）下的地面站可见性普查 + 切换策略对比。
 * <p>
 * 三个问题：
 *   A. 一个轨道周期内，每颗卫星对中国 5 站的可见时长（按倾角壳分桶）——
 *      43° 壳到底能不能看到漠河（53.5°N）？
 *   B. 单一时刻全星座有多少颗"正在中国上空"（10° 以上）？
 *   C. Greedy vs Predictive 在真实拓扑下的切换次数 / 断链数 / 平均时延。
 * <p>
 * 手动跑（约 20-40s）：
 *   surefire fork 出的 JVM 不认 -Djunit.jupiter.conditions.deactivate，
 *   直接临时注释掉下方 @Disabled 执行，跑完记得恢复。
 */
// @Disabled("手动基准：真实星库全量仿真，本地按需运行")
class HandoverBenchmarkTest {

    /** 仿真参考时刻（TLE 历元 2026-10 初，取邻近时刻减小外推误差） */
    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");

    /** 可见性扫描窗口：100 分钟覆盖任一 Starlink 卫星的完整轨道周期 */
    private static final int SCAN_SEC = 6000;

    /** 扫描采样步长（秒） */
    private static final int SCAN_STEP = 30;

    /** 可见门限（度）——与 SimConstants.MIN_ELEVATION_RAD 对齐 */
    private static final double MIN_ELEV_DEG = 10.0;

    private static String resource(String path) throws Exception {
        try (InputStream in = HandoverBenchmarkTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 倾角壳分桶：真实 Starlink 四壳 43/53/70/97.6，±2° 内归桶，其余 other */
    private static String shellOf(OrbitalElements sat) {
        double inc = sat.inclination();
        if (Math.abs(inc - 43.0) <= 2.0) return "43";
        if (Math.abs(inc - 53.0) <= 2.0) return "53";
        if (Math.abs(inc - 70.0) <= 2.0) return "70";
        if (Math.abs(inc - 97.6) <= 2.0) return "97.6";
        return "other";
    }

    @Test
    void realStarlinkVisibilityAndHandover() throws Exception {
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

        // ══ Part A：按壳可见性普查（一轨道周期，30s 采样）══
        Map<String, ShellStat> byShell = new LinkedHashMap<>();
        for (OrbitalElements sat : sats) {
            ShellStat st = byShell.computeIfAbsent(shellOf(sat), k -> new ShellStat(stations.size()));

            double[] maxElev = new double[stations.size()];
            int visTicks = 0;
            for (int s = 0; s <= SCAN_SEC; s += SCAN_STEP) {
                SatellitePosition pos = prop.propagate(sat, T0.plusSeconds(s));
                boolean anyVisible = false;
                for (int k = 0; k < stations.size(); k++) {
                    LinkResult lr = links.calculate(pos, stations.get(k));
                    if (lr.elevationDeg() > maxElev[k]) maxElev[k] = lr.elevationDeg();
                    if (lr.visible()) anyVisible = true;
                }
                if (anyVisible) visTicks++;
            }

            st.total++;
            st.visSeconds.add(visTicks * SCAN_STEP);
            if (visTicks == 0) st.neverVisible++;
            for (int k = 0; k < stations.size(); k++) {
                if (maxElev[k] >= MIN_ELEV_DEG) st.perStationVisible[k]++;
            }
        }

        System.out.println();
        System.out.println("== Part A: 壳 x 站 可见性普查（窗口 100min, 步长 30s, 门限 10°）==");
        StringBuilder header = new StringBuilder("shell  sats   neverVis  avgVisSec  medVisSec  maxVisSec");
        for (GroundStation gs : stations) header.append(String.format("  %s", gs.id()));
        System.out.println(header);
        List<ShellStat> ordered = new ArrayList<>(byShell.values());
        ordered.sort((a, b) -> b.total - a.total);
        for (Map.Entry<String, ShellStat> e : byShell.entrySet()) {
            ShellStat st = e.getValue();
            List<Integer> sorted = new ArrayList<>(st.visSeconds);
            sorted.sort(Integer::compareTo);
            int med = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
            int max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
            double avg = st.visSeconds.stream().mapToInt(Integer::intValue).average().orElse(0);
            StringBuilder row = new StringBuilder(String.format(
                    "%-5s  %5d  %8d  %9.0f  %9d  %9d",
                    e.getKey() + "°", st.total, st.neverVisible, avg, med, max));
            for (int k = 0; k < stations.size(); k++) {
                row.append(String.format("  %3d", st.perStationVisible[k]));
            }
            System.out.println(row);
        }

        // ══ Part B：T0 时刻快照——"此刻"有多少颗在中国上空 ══
        int connectedAtT0 = 0;
        int[] perStation = new int[stations.size()];
        double bestElev = 0;
        for (OrbitalElements sat : sats) {
            SatellitePosition pos = prop.propagate(sat, T0);
            boolean any = false;
            for (int k = 0; k < stations.size(); k++) {
                LinkResult lr = links.calculate(pos, stations.get(k));
                if (lr.visible()) {
                    perStation[k] = 1;   // 该站此刻至少有一颗可用卫星
                    if (lr.elevationDeg() > bestElev) bestElev = lr.elevationDeg();
                    any = true;
                }
            }
            if (any) connectedAtT0++;
        }
        System.out.println();
        System.out.println("== Part B: T0 时刻快照（" + T0 + "）==");
        System.out.printf("可见卫星（对任一站仰角>=10°）: %d / %d (%.2f%%)%n",
                connectedAtT0, sats.size(), 100.0 * connectedAtT0 / sats.size());
        System.out.printf("各站此刻是否可用: ");
        for (int k = 0; k < stations.size(); k++) {
            System.out.printf("%s=%s ", stations.get(k).id(), perStation[k] == 1 ? "Y" : "N");
        }
        System.out.printf("%n当前最高仰角: %.1f°%n", bestElev);

        // ══ Part C：Greedy vs Predictive 切换对比（2h, 30s tick）══
        GreedyHandoverStrategy greedy = new GreedyHandoverStrategy(constellation, prop, links);
        PredictiveHandoverStrategy predictive = new PredictiveHandoverStrategy(constellation, prop, links);
        SimulationEngine engine = new SimulationEngine(constellation, prop, links, null, greedy);

        int duration = 7200;
        int tick = 30;
        long bt = System.currentTimeMillis();
        SimulationEngine.CompareResult cmp = engine.compare(greedy, predictive, T0, duration, tick);
        long benchMs = System.currentTimeMillis() - bt;

        System.out.println();
        System.out.printf("== Part C: 切换策略对比（%dh, tick=%ds, 仿真耗时 %ds）==%n",
                duration / 3600, tick, benchMs / 1000);
        for (SimulationEngine.SimulationResult r : List.of(cmp.result1(), cmp.result2())) {
            Map<HandoverEvent.TriggerType, Integer> byType = new LinkedHashMap<>();
            Map<String, Integer> loadByStation = new LinkedHashMap<>();
            int preemptive = 0;   // 仰角还很充裕（>=12°）就被抢走的切换
            for (HandoverEvent ev : r.events()) {
                byType.merge(ev.triggerType(), 1, Integer::sum);
                if (ev.toStationId() != null) loadByStation.merge(ev.toStationId(), 1, Integer::sum);
                if (ev.triggerType() == HandoverEvent.TriggerType.ELEVATION_FALL
                        && ev.fromElevationDeg() >= 12.0) preemptive++;
            }
            System.out.printf("[%s] 事件总数=%d  %s  抢占切换(仰角>=12°被抢)=%d%n",
                    r.algorithmId(), r.handoverCount(), byType, preemptive);
            System.out.println("  每站承接切换(含INITIAL后的重新选站): " + loadByStation);
            System.out.printf("  切换率=%.1f 次/分钟%n", r.handoverRatePerMin());
        }
        System.out.printf("[对比] 平均切换时延: greedy=%.1fms predictive=%.1fms | LINK_BREAK: greedy=%d predictive=%d%n",
                cmp.avgLatency1(), cmp.avgLatency2(), cmp.linkBreakCount1(), cmp.linkBreakCount2());

        assertTrue(cat.stats().parsed() > 10_000);
    }

    /** 单个倾角壳的统计聚合 */
    private static final class ShellStat {
        int total;
        int neverVisible;
        final int[] perStationVisible;
        final List<Integer> visSeconds = new ArrayList<>();

        ShellStat(int stationCount) {
            this.perStationVisible = new int[stationCount];
        }
    }
}
