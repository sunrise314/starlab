package com.starlab.handover;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.OrbitalElements;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * v1 贪心选站策略 —— 最高仰角优先
 * <p>
 * 算法：当前时刻遍历所有地面站，选仰角最高的可见站
 * 触发切换条件：最高仰角站 ≠ 当前服务站
 * <p>
 * 优点：实现简单、开销小（每 tick 只需 12 星 × 5 站 = 60 次链路计算）
 * 缺点：可能在临界区频繁抖动（仰角 10°±2° 内反复切换）
 */
@Component
@Primary
public class GreedyHandoverStrategy implements HandoverStrategy {

    private static final double PROCESSING_MS = 5.0;

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;

    public GreedyHandoverStrategy(Constellation constellation,
                                  OrbitPropagator propagator,
                                  LinkCalculator linkCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
    }

    @Override
    public String algorithmId() {
        return "greedy";
    }

    @Override
    public String algorithmName() {
        return "贪心最高仰角";
    }

    @Override
    public StationSelection selectBestStation(OrbitalElements sat, Instant time, String currentStationId) {
        SatellitePosition pos = propagator.propagate(sat, time);
        List<GroundStation> stations = constellation.getGroundStations();

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
