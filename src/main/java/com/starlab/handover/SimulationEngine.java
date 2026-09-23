package com.starlab.handover;

import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.InterferenceCalculator;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * STCN 仿真引擎 —— 策略可注入
 * <p>
 * 构造函数接收默认 HandoverStrategy，simulate 方法也可显式传入策略实现。
 * 这使得 compare 端点可以同参数跑 v1 贪心 + v2 预测式，返回对比统计。
 */
@Component
public class SimulationEngine {

    private static final int DEFAULT_RING_CAPACITY = 10_000;

    /** 断链后冷却期（秒），防止选站振荡 */
    static final long RECOVERY_COOLDOWN_SEC = 60;

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;
    private final InterferenceCalculator interferenceCalculator;

    /** 默认策略（Spring 注入的 GreedyHandoverStrategy） */
    private final HandoverStrategy defaultStrategy;

    /** 环形缓冲 */
    private final Deque<HandoverEvent> events = new ConcurrentLinkedDeque<>();

    public SimulationEngine(Constellation constellation,
                            OrbitPropagator propagator,
                            LinkCalculator linkCalculator,
                            InterferenceCalculator interferenceCalculator,
                            HandoverStrategy defaultStrategy) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
        this.interferenceCalculator = interferenceCalculator;
        this.defaultStrategy = defaultStrategy;
    }

    /** 使用默认策略跑仿真 */
    public SimulationResult simulate(Instant start, int duration, int tickSec) {
        return simulate(defaultStrategy, start, duration, tickSec);
    }

    /** 指定策略跑仿真（对比端点用） */
    public SimulationResult simulate(HandoverStrategy strategy, Instant start, int duration, int tickSec) {
        Instant simStart = start != null ? start : Instant.now();
        Instant simEnd = simStart.plusSeconds(duration);

        Map<String, String> currentStationBySat = new HashMap<>();
        // 断链冷却期：断链后 RECOVERY_COOLDOWN_SEC 内不允许重新选站（防止振荡）
        Map<String, Instant> brokenUntil = new HashMap<>();
        List<HandoverEvent> localEvents = new ArrayList<>();

        int ticks = 0;
        for (Instant t = simStart; t.isBefore(simEnd); t = t.plusSeconds(tickSec)) {
            ticks++;

            for (OrbitalElements sat : constellation.getSatellites()) {
                SatellitePosition pos = propagator.propagate(sat, t);
                String satId = sat.satelliteId();
                String curStationId = currentStationBySat.get(satId);

                // 冷却期内：断链后 RECOVERY_COOLDOWN_SEC 秒内不再尝试选站
                Instant cooldownUntil = brokenUntil.get(satId);
                if (cooldownUntil != null && t.isBefore(cooldownUntil)) {
                    continue;  // 还在冷却，跳过
                }

                StationSelection selection = strategy.selectBestStation(sat, t, curStationId);

                // 无可见站：断开
                if (selection.reason() == StationSelection.Reason.NO_VISIBLE) {
                    if (curStationId != null) {
                        GroundStation from = constellation.getGroundStation(curStationId);
                        if (from != null) {
                            LinkResult oldLink = linkCalculator.calculate(pos, from);
                            HandoverEvent evt = new HandoverEvent(
                                    satId, sat.name(),
                                    from.id(), from.name(),
                                    oldLink.elevationDeg(),
                                    null, null,
                                    0, t,
                                    HandoverEvent.TriggerType.LINK_BREAK,
                                    0, 0, 0
                            );
                            localEvents.add(evt);
                            events.add(evt);
                            trimRing();
                        }
                        currentStationBySat.remove(satId);
                        brokenUntil.put(satId, t.plusSeconds(RECOVERY_COOLDOWN_SEC));
                    }
                    continue;
                }

                // 选站成功：清除冷却
                brokenUntil.remove(satId);

                // 需要切换
                StationSelection.Reason r = selection.reason();
                if (r == StationSelection.Reason.ELEVATION_SWITCH
                        || r == StationSelection.Reason.PREDICTIVE_SWITCH) {
                    GroundStation from = constellation.getGroundStation(curStationId);
                    GroundStation to = selection.station();
                    if (from != null && to != null) {
                        SatellitePosition oldPos = pos;
                        LinkResult oldLink = linkCalculator.calculate(oldPos, from);

                        HandoverEvent.TriggerType tt = (r == StationSelection.Reason.PREDICTIVE_SWITCH)
                                ? HandoverEvent.TriggerType.PREDICTIVE
                                : HandoverEvent.TriggerType.ELEVATION_FALL;

                        HandoverEvent evt = new HandoverEvent(
                                sat.satelliteId(), sat.name(),
                                from.id(), from.name(),
                                oldLink.elevationDeg(),
                                to.id(), to.name(),
                                selection.link().elevationDeg(),
                                t, tt,
                                selection.propagationMs(),
                                selection.totalLatencyMs(),
                                selection.totalLatencyMs()
                        );
                        localEvents.add(evt);
                        events.add(evt);
                        trimRing();
                    }
                }

                // 更新当前服务站
                currentStationBySat.put(sat.satelliteId(), selection.station().id());
            }
        }

        return new SimulationResult(
                simStart, simEnd, duration, tickSec,
                ticks, localEvents.size(), localEvents,
                strategy.algorithmId()
        );
    }

    /**
     * 跑仿真并记录每个 tick 的快照（用于前端实时回放）
     * <p>
     * 与 {@link #simulate} 区别：simulate 只返回 events，本方法额外返回每个 tick
     * 每颗卫星当前的服务站 ID、仰角、时延、链路状态、卫星地理位置。
     * 前端拿到 timeline 后可以按时间轴驱动 Cesium 回放动画。
     * <p>
     * 内存：durationSec=3600, tickSec=10 → 360 ticks × 12 sats = 4320 条 StationState，可接受。
     */
    public TimelineResult simulateWithTimeline(HandoverStrategy strategy, Instant start, int duration, int tickSec) {
        Instant simStart = start != null ? start : Instant.now();
        Instant simEnd = simStart.plusSeconds(duration);

        Map<String, String> currentStationBySat = new HashMap<>();
        Map<String, Instant> brokenUntil = new HashMap<>();
        List<HandoverEvent> localEvents = new ArrayList<>();
        List<TickSnapshot> timeline = new ArrayList<>();
        // v3 干扰模型：记录所有卫星当前 tick 位置，供干扰分析复用
        List<OrbitalElements> sats = constellation.getSatellites();
        List<GroundStation> stations = constellation.getGroundStations();

        int ticks = 0;
        for (Instant t = simStart; t.isBefore(simEnd); t = t.plusSeconds(tickSec)) {
            ticks++;
            int simSec = (int) java.time.Duration.between(simStart, t).getSeconds();

            // ① 批量算所有卫星位置（一次传播，避免重复）
            Map<String, SatellitePosition> posMap = new HashMap<>();
            for (OrbitalElements sat : sats) {
                posMap.put(sat.satelliteId(), propagator.propagate(sat, t));
            }
            List<SatellitePosition> allPositions = new ArrayList<>(posMap.values());

            // ② 选站 + 记录快照
            List<StationState> states = new ArrayList<>(sats.size());

            for (OrbitalElements sat : sats) {
                SatellitePosition pos = posMap.get(sat.satelliteId());
                String satId = sat.satelliteId();
                String curStationId = currentStationBySat.get(satId);

                // 冷却期内：直接记 COOLDOWN 状态（不参与选站）
                Instant cooldownUntil = brokenUntil.get(satId);
                if (cooldownUntil != null && t.isBefore(cooldownUntil)) {
                    states.add(new StationState(
                            satId, sat.name(),
                            null, null,
                            0, 0, 0,
                            pos.latitude(), pos.longitude(), pos.altitude(),
                            LinkStatus.COOLDOWN
                    ));
                    continue;
                }

                StationSelection selection = strategy.selectBestStation(sat, t, curStationId);

                // 无可见站：断开
                if (selection.reason() == StationSelection.Reason.NO_VISIBLE) {
                    if (curStationId != null) {
                        GroundStation from = constellation.getGroundStation(curStationId);
                        if (from != null) {
                            LinkResult oldLink = linkCalculator.calculate(pos, from);
                            HandoverEvent evt = new HandoverEvent(
                                    satId, sat.name(),
                                    from.id(), from.name(),
                                    oldLink.elevationDeg(),
                                    null, null,
                                    0, t,
                                    HandoverEvent.TriggerType.LINK_BREAK,
                                    0, 0, 0
                            );
                            localEvents.add(evt);
                            events.add(evt);
                            trimRing();
                        }
                        currentStationBySat.remove(satId);
                        brokenUntil.put(satId, t.plusSeconds(RECOVERY_COOLDOWN_SEC));
                    }
                    states.add(new StationState(
                            satId, sat.name(),
                            null, null,
                            0, 0, 0,
                            pos.latitude(), pos.longitude(), pos.altitude(),
                            LinkStatus.BROKEN
                    ));
                    continue;
                }

                // 选站成功：清除冷却
                brokenUntil.remove(satId);

                // 需要切换
                StationSelection.Reason r = selection.reason();
                if (r == StationSelection.Reason.ELEVATION_SWITCH
                        || r == StationSelection.Reason.PREDICTIVE_SWITCH) {
                    GroundStation from = constellation.getGroundStation(curStationId);
                    GroundStation to = selection.station();
                    if (from != null && to != null) {
                        LinkResult oldLink = linkCalculator.calculate(pos, from);
                        HandoverEvent.TriggerType tt = (r == StationSelection.Reason.PREDICTIVE_SWITCH)
                                ? HandoverEvent.TriggerType.PREDICTIVE
                                : HandoverEvent.TriggerType.ELEVATION_FALL;
                        HandoverEvent evt = new HandoverEvent(
                                sat.satelliteId(), sat.name(),
                                from.id(), from.name(),
                                oldLink.elevationDeg(),
                                to.id(), to.name(),
                                selection.link().elevationDeg(),
                                t, tt,
                                selection.propagationMs(),
                                selection.totalLatencyMs(),
                                selection.totalLatencyMs()
                        );
                        localEvents.add(evt);
                        events.add(evt);
                        trimRing();
                    }
                }

                // 更新当前服务站
                GroundStation cur = selection.station();
                currentStationBySat.put(sat.satelliteId(), cur.id());

                // ③ 干扰分析（每个 tick 对所有卫星一次性批量算）
                LinkResult link = selection.link();
                LinkResult interfered = interferenceCalculator.analyze(pos, cur, allPositions);

                // 记录快照（含干扰字段）
                states.add(new StationState(
                        satId, sat.name(),
                        cur.id(), cur.name(),
                        link.elevationDeg(),
                        selection.totalLatencyMs(),
                        link.rangeKm(),
                        pos.latitude(), pos.longitude(), pos.altitude(),
                        LinkStatus.CONNECTED,
                        interfered.cirDb(),
                        interfered.coChannelCount(),
                        interfered.linkAvailable()
                ));
            }

            timeline.add(new TickSnapshot(t, simSec, states));
        }

        return new TimelineResult(
                simStart, simEnd, duration, tickSec,
                ticks, localEvents.size(), localEvents,
                timeline,
                strategy.algorithmId()
        );
    }

    /** 批量跑两种策略，返回对比结果 */
    public CompareResult compare(HandoverStrategy s1, HandoverStrategy s2,
                                  Instant start, int duration, int tickSec) {
        SimulationResult r1 = simulate(s1, start, duration, tickSec);
        SimulationResult r2 = simulate(s2, start, duration, tickSec);

        // 统计平均时延（只统计 ELEVATION_FALL / PREDICTIVE 类型，排除 LINK_BREAK）
        double avgLat1 = avgLatency(r1.events());
        double avgLat2 = avgLatency(r2.events());

        // 统计 LINK_BREAK 数量
        long breakCount1 = r1.events().stream().filter(e -> e.triggerType() == HandoverEvent.TriggerType.LINK_BREAK).count();
        long breakCount2 = r2.events().stream().filter(e -> e.triggerType() == HandoverEvent.TriggerType.LINK_BREAK).count();

        return new CompareResult(
                s1.algorithmId(), s1.algorithmName(), r1, avgLat1, breakCount1,
                s2.algorithmId(), s2.algorithmName(), r2, avgLat2, breakCount2
        );
    }

    private double avgLatency(List<HandoverEvent> events) {
        double sum = 0;
        int count = 0;
        for (HandoverEvent e : events) {
            if (e.totalLatencyMs() > 0) {
                sum += e.totalLatencyMs();
                count++;
            }
        }
        return count > 0 ? Math.round(sum / count * 10.0) / 10.0 : 0;
    }

    private void trimRing() {
        while (events.size() > DEFAULT_RING_CAPACITY) events.pollFirst();
    }

    public List<HandoverEvent> getRecentEvents(int limit) {
        return events.stream()
                .sorted(Comparator.comparing(HandoverEvent::triggerTime).reversed())
                .limit(limit)
                .toList();
    }

    public void clear() {
        events.clear();
    }

    /** 仿真结果 DTO */
    public record SimulationResult(
            Instant simStart, Instant simEnd,
            int durationSec, int tickSec,
            int totalTicks, int handoverCount,
            List<HandoverEvent> events,
            String algorithmId
    ) {
        public double handoverRatePerMin() {
            return durationSec <= 0 ? 0 : handoverCount * 60.0 / durationSec;
        }
    }

    /** 双策略对比结果 DTO */
    public record CompareResult(
            String algo1Id, String algo1Name,
            SimulationResult result1, double avgLatency1, long linkBreakCount1,
            String algo2Id, String algo2Name,
            SimulationResult result2, double avgLatency2, long linkBreakCount2
    ) {}

    /** 三策略对比结果 DTO */
    public record TripleCompareResult(
            String algo1Id, String algo1Name, SimulationResult result1, double avgLatency1, long linkBreakCount1,
            String algo2Id, String algo2Name, SimulationResult result2, double avgLatency2, long linkBreakCount2,
            String algo3Id, String algo3Name, SimulationResult result3, double avgLatency3, long linkBreakCount3
    ) {}

    /** 批量跑三种策略，返回对比结果（v1 贪心 / v2 预测 / v3 干扰感知） */
    public TripleCompareResult compareThree(HandoverStrategy s1, HandoverStrategy s2, HandoverStrategy s3,
                                             Instant start, int duration, int tickSec) {
        SimulationResult r1 = simulate(s1, start, duration, tickSec);
        SimulationResult r2 = simulate(s2, start, duration, tickSec);
        SimulationResult r3 = simulate(s3, start, duration, tickSec);

        double avgLat1 = avgLatency(r1.events());
        double avgLat2 = avgLatency(r2.events());
        double avgLat3 = avgLatency(r3.events());

        long breakCount1 = r1.events().stream().filter(e -> e.triggerType() == HandoverEvent.TriggerType.LINK_BREAK).count();
        long breakCount2 = r2.events().stream().filter(e -> e.triggerType() == HandoverEvent.TriggerType.LINK_BREAK).count();
        long breakCount3 = r3.events().stream().filter(e -> e.triggerType() == HandoverEvent.TriggerType.LINK_BREAK).count();

        return new TripleCompareResult(
                s1.algorithmId(), s1.algorithmName(), r1, avgLat1, breakCount1,
                s2.algorithmId(), s2.algorithmName(), r2, avgLat2, breakCount2,
                s3.algorithmId(), s3.algorithmName(), r3, avgLat3, breakCount3
        );
    }

    /**
     * 时间轴回放结果 DTO —— 包含每个 tick 的快照
     * <p>
     * 前端拿到 timeline 后可以按时间轴驱动 Cesium 回放动画：
     * - 卫星位置（lat/lon/alt）→ 更新 sat Entity
     * - 当前服务站 ID → 重建链路 Polyline
     * - 链路状态 → 颜色高亮（CONNECTED 绿、BROKEN 红、COOLDOWN 灰）
     * - 切换事件按 triggerTime 与 simSec 匹配高亮
     */
    public record TimelineResult(
            Instant simStart, Instant simEnd,
            int durationSec, int tickSec,
            int totalTicks, int handoverCount,
            List<HandoverEvent> events,
            List<TickSnapshot> timeline,
            String algorithmId
    ) {
        public double handoverRatePerMin() {
            return durationSec <= 0 ? 0 : handoverCount * 60.0 / durationSec;
        }
    }

    /** 单个 tick 的快照 */
    public record TickSnapshot(
            Instant time,      // tick 的绝对时间
            int simSec,        // 从仿真开始的秒数（0, 10, 20, ...）
            List<StationState> stations  // 每颗卫星的状态
    ) {}

    /** 单颗卫星在某 tick 的状态 */
    public record StationState(
            String satId,
            String satName,
            String stationId,        // null if broken/cooldown
            String stationName,      // null if broken/cooldown
            double elevationDeg,    // 0 if broken
            double latencyMs,       // 0 if broken
            double rangeKm,         // 0 if broken
            double latitude,        // 卫星纬度（前端回放位置）
            double longitude,
            double altitude,
            LinkStatus linkStatus,
            // v3 干扰模型字段（null 表示未计算，节省内存）
            Double cirDb,              // 载干比 C/I (dB)
            Integer coChannelCount,    // 同频干扰卫星数
            Boolean linkAvailable      // 干扰下链路是否可用
    ) {
        /**
         * 无干扰版本的构造（向后兼容 simulate 方法内部调用点）
         */
        public StationState(
                String satId, String satName,
                String stationId, String stationName,
                double elevationDeg, double latencyMs, double rangeKm,
                double latitude, double longitude, double altitude,
                LinkStatus linkStatus) {
            this(satId, satName, stationId, stationName,
                    elevationDeg, latencyMs, rangeKm,
                    latitude, longitude, altitude, linkStatus,
                    null, null, null);
        }
    }

    /** 链路状态枚举 */
    public enum LinkStatus {
        CONNECTED,  // 已选站，链路通
        BROKEN,     // 当前无可见站（断链中）
        COOLDOWN    // 断链冷却期内（RECOVERY_COOLDOWN_SEC 秒内不再选站）
    }
}
