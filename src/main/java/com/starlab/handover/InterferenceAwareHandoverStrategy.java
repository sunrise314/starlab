package com.starlab.handover;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.InterferenceCalculator;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.OrbitalElements;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;

/**
 * v3 干扰感知选站策略 —— 最高 C/I 优先
 * <p>
 * 算法：
 *   1. 先算所有卫星当前位置（供干扰分析枚举同频干扰源）
 *   2. 遍历所有地面站，对每颗可见站的链路做干扰分析得到 C/I
 *   3. 选 C/I 最高的可见站（C/I > CIR_THRESHOLD = 9 dB）
 *   4. 触发切换条件：
 *      a) 贪心条件：最高 C/I 站 ≠ 当前服务站
 *      b) 干扰恶化：当前服务站 C/I < CIR_THRESHOLD，强制切换
 *      c) 更优干扰：当前服务站 C/I 比最优站低 ≥ IMPROVEMENT_HYSTERESIS dB
 * <p>
 * 与 v1 贪心最高仰角的区别：
 *   - 目标函数从 elevationDeg 改为 cirDb
 *   - 触发条件增加 C/I 恶化判定
 *   - 计算开销更大（每 tick 每颗卫星需算 5 站 × 11 颗干扰卫星 = 55 次 LinkCalculator）
 */
@Component
public class InterferenceAwareHandoverStrategy implements HandoverStrategy {

    private static final double PROCESSING_MS = 5.0;
    /** 载干比阈值：低于此值视为不可用（QPSK 1/2 解调下限） */
    private static final double CIR_THRESHOLD_DB = 9.0;
    /** 改善滞后：只有新站 C/I 比旧站高 ≥ 此值才切换，防抖动 */
    private static final double IMPROVEMENT_HYSTERESIS_DB = 2.0;

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;
    private final InterferenceCalculator interferenceCalculator;

    public InterferenceAwareHandoverStrategy(Constellation constellation,
                                              OrbitPropagator propagator,
                                              LinkCalculator linkCalculator,
                                              InterferenceCalculator interferenceCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
        this.interferenceCalculator = interferenceCalculator;
    }

    @Override
    public String algorithmId() {
        return "interference-aware";
    }

    @Override
    public String algorithmName() {
        return "干扰感知 C/I 优先";
    }

    @Override
    public StationSelection selectBestStation(OrbitalElements sat, Instant time, String currentStationId) {
        // 0. 先算所有卫星位置（干扰分析需要枚举同频干扰源）
        List<SatellitePosition> allPositions = constellation.getSatellites().stream()
                .map(el -> propagator.propagate(el, time))
                .toList();
        SatellitePosition pos = propagator.propagate(sat, time);
        List<GroundStation> stations = constellation.getGroundStations();

        // 1. 遍历所有地面站，做干扰分析
        record StationCIR(GroundStation station, LinkResult link, LinkResult interfered) {}
        List<StationCIR> candidates = new ArrayList<>();
        for (GroundStation st : stations) {
            LinkResult baseLink = linkCalculator.calculate(pos, st);
            if (!baseLink.visible()) continue;
            LinkResult interfered = interferenceCalculator.analyze(pos, st, allPositions);
            candidates.add(new StationCIR(st, baseLink, interfered));
        }

        if (candidates.isEmpty()) {
            return new StationSelection(null, null, false, 0, 0, StationSelection.Reason.NO_VISIBLE);
        }

        // 2. 按 C/I 降序排，最高优先
        StationCIR best = candidates.stream()
                .max(Comparator.comparingDouble(c -> c.interfered.cirDb()))
                .orElse(candidates.get(0));

        double propMs = best.link.rangeKm() / SimConstants.SPEED_OF_LIGHT * 2 * 1000;
        double totalMs = PROCESSING_MS + propMs;

        // 3. 判定切换
        boolean handoverNeeded = false;
        StationSelection.Reason reason = StationSelection.Reason.NONE;

        if (currentStationId == null) {
            reason = StationSelection.Reason.INITIAL;
            handoverNeeded = true;
        } else if (!best.station.id().equals(currentStationId)) {
            // 找当前服务站的 C/I
            StationCIR cur = candidates.stream()
                    .filter(c -> c.station.id().equals(currentStationId))
                    .findFirst().orElse(null);

            // 切换原因判定
            if (cur != null && cur.interfered.cirDb() < CIR_THRESHOLD_DB) {
                reason = StationSelection.Reason.INTERFERENCE_SWITCH;  // 当前站干扰恶化
                handoverNeeded = true;
            } else if (cur == null) {
                reason = StationSelection.Reason.LINK_BREAK;  // 当前站已不可见
                handoverNeeded = true;
            } else if (best.interfered.cirDb() - cur.interfered.cirDb() >= IMPROVEMENT_HYSTERESIS_DB) {
                reason = StationSelection.Reason.INTERFERENCE_SWITCH;  // 新站 C/I 显著更好
                handoverNeeded = true;
            } else if (best.interfered.cirDb() >= CIR_THRESHOLD_DB && cur.interfered.cirDb() >= CIR_THRESHOLD_DB) {
                reason = StationSelection.Reason.ELEVATION_SWITCH;  // 正常最高 C/I 切换
                handoverNeeded = true;
            } else {
                // 两边 C/I 都 < 阈值且差距不大，保持当前站（防抖动）
                reason = StationSelection.Reason.NONE;
                handoverNeeded = false;
            }
        }

        return new StationSelection(best.station, best.link, handoverNeeded, propMs, totalMs, reason);
    }
}
