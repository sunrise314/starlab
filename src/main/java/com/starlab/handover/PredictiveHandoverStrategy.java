package com.starlab.handover;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.OrbitalElements;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * v2 预测式选站策略 —— 未来可见窗口最长优先
 * <p>
 * 核心思想：
 *   v1 贪心只看"此时此刻"最高仰角——在临界区（仰角 10°±2°）容易频繁抖动切换。
 *   v2 预测式向前看 60s，选未来可见窗口最长的站，一次切换稳很久。
 * <p>
 * 触发条件（性能关键——不是每 tick 都预测）：
 *   正常飞行：当前服务站仰角 ≥ 15° → 用贪心（和 v1 开销一样）
 *   临界区：当前服务站仰角 < 15° → 触发预测
 * <p>
 * 预测算法（60s 窗口，5s 采样 = 12 个时刻）：
 *   1. 对每个候选站，统计 12 个采样点中可见的数量
 *   2. 可见窗口时长 ≈ visibleCount × 5 秒
 *   3. 如果当前站的窗口 ≥ 30s（阈值）→ 继续用当前站（不急切）
 *   4. 否则 → 选窗口最长的站
 * <p>
 * 性能开销：
 *   正常飞行：每 tick 12 星 × 5 站 = 60 次链路计算（和 v1 相同）
 *   临界区触发预测：每 tick 12 星 × 5 站 × 12 采样点 = 720 次链路计算
 *   但临界区只占过境窗口的后 ~2min（过境 11min 中），≈ 18% 触发率
 */
@Component
public class PredictiveHandoverStrategy implements HandoverStrategy {

    /** 信关站处理时延（固定，毫秒） */
    private static final double PROCESSING_MS = 5.0;

    /** 预测窗口（秒）：向前看多远 */
    private static final int PREDICT_WINDOW_SEC = 90;

    /** 预测采样步长（秒） */
    private static final int PREDICT_STEP_SEC = 5;

    /** 临界区阈值：仰角低于此值触发预测 */
    private static final double CRITICAL_ZONE_DEG = 20.0;

    /** 当前站可见窗口 ≥ 此值时继续用（秒）——越大越保守 */
    private static final double HOLD_WINDOW_SEC = 45.0;

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;

    public PredictiveHandoverStrategy(Constellation constellation,
                                      OrbitPropagator propagator,
                                      LinkCalculator linkCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
    }

    @Override
    public String algorithmId() {
        return "predictive";
    }

    @Override
    public String algorithmName() {
        return "预测式窗口优先";
    }

    @Override
    public StationSelection selectBestStation(OrbitalElements sat, Instant time, String currentStationId) {
        SatellitePosition pos = propagator.propagate(sat, time);
        List<GroundStation> stations = constellation.getGroundStations();

        // 当前站（如果有）的当前仰角
        LinkResult curLink = null;
        if (currentStationId != null) {
            GroundStation curStation = constellation.getGroundStation(currentStationId);
            if (curStation != null) {
                curLink = linkCalculator.calculate(pos, curStation);
            }
        }

        // ── 分支 1：临界区触发预测 ──
        if (curLink != null && curLink.elevationDeg() < CRITICAL_ZONE_DEG) {
            return predictiveSelect(sat, time, stations, currentStationId, curLink);
        }

        // ── 分支 2：正常飞行 / 无当前站 → 贪心 ──
        return greedySelect(sat, time, stations, currentStationId);
    }

    /**
     * 预测式选站：遍历未来 60s 每个候选站的可见窗口，选最长的
     */
    private StationSelection predictiveSelect(OrbitalElements sat, Instant time,
                                              List<GroundStation> stations,
                                              String currentStationId,
                                              LinkResult curLink) {
        record StationScore(GroundStation station, LinkResult currentLink, double visibleWindowSec) {}

        // 采样点列表：t+5s, t+10s, ..., t+60s
        int sampleCount = PREDICT_WINDOW_SEC / PREDICT_STEP_SEC; // 12
        Instant[] sampleTimes = new Instant[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            sampleTimes[i] = time.plusSeconds((long) (i + 1) * PREDICT_STEP_SEC);
        }

        // 计算每个站的可见窗口时长（可见采样点数 × 步长）
        StationScore best = null;
        for (GroundStation s : stations) {
            LinkResult cur = linkCalculator.calculate(
                    propagator.propagate(sat, time), s);
            int visibleCount = 0;
            for (Instant st : sampleTimes) {
                SatellitePosition futurePos = propagator.propagate(sat, st);
                LinkResult futureLink = linkCalculator.calculate(futurePos, s);
                if (futureLink.visible()) visibleCount++;
            }
            double windowSec = visibleCount * PREDICT_STEP_SEC; // 秒
            StationScore score = new StationScore(s, cur, windowSec);
            if (best == null || score.visibleWindowSec() > best.visibleWindowSec()) {
                best = score;
            }
        }

        if (best == null) {
            // 所有站都不可见
            return new StationSelection(null, null, false, 0, 0, StationSelection.Reason.NO_VISIBLE);
        }

        // 当前站的可见窗口
        double curWindowSec = 0;
        GroundStation curStation = null;
        if (currentStationId != null) {
            curStation = constellation.getGroundStation(currentStationId);
            if (curStation != null) {
                int curVisibleCount = 0;
                for (Instant st : sampleTimes) {
                    SatellitePosition futurePos = propagator.propagate(sat, st);
                    LinkResult futureLink = linkCalculator.calculate(futurePos, curStation);
                    if (futureLink.visible()) curVisibleCount++;
                }
                curWindowSec = curVisibleCount * PREDICT_STEP_SEC;
            }
        }

        // 决策逻辑（核心：窗口够就守，窗口不够就切）
        if (currentStationId != null && curStation != null) {
            // 情况 1：当前站窗口还够 → 不急切（比贪心更稳，避免临界区抖动）
            if (curWindowSec >= HOLD_WINDOW_SEC) {
                double propMs = curLink.rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
                return new StationSelection(curStation, curLink, false, propMs, PROCESSING_MS + propMs,
                        StationSelection.Reason.NONE);
            }

            // 情况 2：最佳候选站和当前站相同 → 继续用（没得选）
            if (best.station().id().equals(currentStationId)) {
                double propMs = curLink.rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
                return new StationSelection(curStation, curLink, false, propMs, PROCESSING_MS + propMs,
                        StationSelection.Reason.NONE);
            }

            // 情况 3：当前站窗口 < HOLD → 必须切到窗口最长的候选站
            // （不管新站比旧站好多少——当前站撑不住了）
            // 继续往下走切换逻辑
        }

        // 无可见站 → NO_VISIBLE
        if (!best.currentLink().visible()) {
            return new StationSelection(null, null, false, 0, 0, StationSelection.Reason.NO_VISIBLE);
        }

        // 需要切换（当前站 ≠ 最佳预测站）
        boolean handoverNeeded = currentStationId == null
                || !best.station().id().equals(currentStationId);

        double propMs = best.currentLink().rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
        double totalMs = PROCESSING_MS + propMs;

        StationSelection.Reason reason = StationSelection.Reason.NONE;
        if (currentStationId == null) {
            reason = StationSelection.Reason.INITIAL;
        } else if (handoverNeeded) {
            reason = StationSelection.Reason.PREDICTIVE_SWITCH;
        }

        return new StationSelection(best.station(), best.currentLink(), handoverNeeded, propMs, totalMs, reason);
    }

    /**
     * 贪心选站（和 v1 相同逻辑，避免重复依赖 GreedyHandoverStrategy）
     */
    private StationSelection greedySelect(OrbitalElements sat, Instant time,
                                          List<GroundStation> stations,
                                          String currentStationId) {
        SatellitePosition pos = propagator.propagate(sat, time);

        record StationLink(GroundStation station, LinkResult link) {}

        StationLink best = stations.stream()
                .map(s -> new StationLink(s, linkCalculator.calculate(pos, s)))
                .filter(sl -> sl.link.visible())
                .max(Comparator.comparingDouble(sl -> sl.link.elevationDeg()))
                .orElse(null);

        if (best == null) {
            return new StationSelection(null, null, false, 0, 0, StationSelection.Reason.NO_VISIBLE);
        }

        boolean handoverNeeded = currentStationId == null
                || !best.station.id().equals(currentStationId);

        double propMs = best.link.rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
        double totalMs = PROCESSING_MS + propMs;

        StationSelection.Reason reason = StationSelection.Reason.NONE;
        if (currentStationId == null) {
            reason = StationSelection.Reason.INITIAL;
        } else if (handoverNeeded) {
            reason = StationSelection.Reason.ELEVATION_SWITCH;
        }

        return new StationSelection(best.station, best.link, handoverNeeded, propMs, totalMs, reason);
    }
}
