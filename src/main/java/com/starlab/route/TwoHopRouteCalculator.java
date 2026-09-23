package com.starlab.route;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 多用户终端两跳路由计算器
 * <p>
 * 场景：地面用户终端 → 卫星（第一跳）→ 信关站（第二跳）→ 地面网
 * <p>
 * 算法：
 *   1. 用户终端视为虚拟 GroundStation（id=UT-USER）
 *   2. 遍历所有卫星，计算 user→sat 链路（仰角<10° 跳过）
 *   3. 对每颗可见卫星，遍历所有信关站，选仰角最高的可见 gateway
 *   4. 输出端到端指标：总传播时延、瓶颈吞吐量（两跳取 min）
 *   5. 按瓶颈吞吐量降序排序
 * <p>
 * 物理层指标复用 LinkCalculator 的新增强字段（SNR / Shannon 吞吐量）。
 */
@Component
public class TwoHopRouteCalculator {

    /** 每跳处理时延（卫星 onboard processing + gateway），毫秒 */
    private static final double PROCESSING_MS_PER_HOP = 5.0;

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;

    public TwoHopRouteCalculator(Constellation constellation,
                                 OrbitPropagator propagator,
                                 LinkCalculator linkCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
    }

    /**
     * 计算用户终端到所有可见卫星的两跳路由
     *
     * @param userLat 用户终端纬度
     * @param userLon 用户终端经度
     * @param time    仿真时刻（null = now）
     */
    public TwoHopResult calculate(double userLat, double userLon, Instant time) {
        Instant t = time != null ? time : Instant.now();
        // 虚拟用户终端（alt=0：地表）
        GroundStation userTerm = new GroundStation("UT-USER", "用户终端", userLat, userLon, 0.0);
        List<TwoHopRoute> routes = new ArrayList<>();

        for (OrbitalElements sat : constellation.getSatellites()) {
            SatellitePosition satPos = propagator.propagate(sat, t);

            // 第一跳：user → sat
            LinkResult uplink = linkCalculator.calculate(satPos, userTerm);
            if (!uplink.visible()) continue;

            // 第二跳：sat → 最佳 gateway（选仰角最高的可见信关站）
            LinkResult bestDownlink = null;
            for (GroundStation gw : constellation.getGroundStations()) {
                LinkResult dl = linkCalculator.calculate(satPos, gw);
                if (dl.visible() && (bestDownlink == null
                        || dl.elevationDeg() > bestDownlink.elevationDeg())) {
                    bestDownlink = dl;
                }
            }
            if (bestDownlink == null) continue;  // 这颗卫星当前无 gateway 可达

            // 端到端指标
            double propTotalMs = (uplink.rangeKm() + bestDownlink.rangeKm())
                    / SimConstants.SPEED_OF_LIGHT * 2 * 1000;  // 双向传播
            double totalLatencyMs = propTotalMs + PROCESSING_MS_PER_HOP * 2;
            double bottleneckThroughputMbps = Math.min(
                    uplink.throughputMbps(), bestDownlink.throughputMbps());

            routes.add(new TwoHopRoute(
                    sat.satelliteId(), sat.name(),
                    round(uplink.elevationDeg()), round(uplink.rangeKm()),
                    round(uplink.snrDb()), round(uplink.throughputMbps()),
                    bestDownlink.stationId(), bestDownlink.stationName(),
                    round(bestDownlink.elevationDeg()), round(bestDownlink.rangeKm()),
                    round(bestDownlink.snrDb()), round(bestDownlink.throughputMbps()),
                    round(totalLatencyMs), round(bottleneckThroughputMbps)
            ));
        }

        // 按瓶颈吞吐量降序排
        routes.sort(Comparator.comparingDouble(TwoHopRoute::bottleneckThroughputMbps).reversed());

        return new TwoHopResult(userLat, userLon, t, routes);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** 两跳路由计算结果 */
    public record TwoHopResult(
            double userLat, double userLon,
            Instant time,
            List<TwoHopRoute> routes
    ) {}

    /** 单条两跳路由 */
    public record TwoHopRoute(
            String satelliteId, String satelliteName,
            // 第一跳：用户终端 → 卫星
            double userToSatElevationDeg,
            double userToSatRangeKm,
            double userToSatSnrDb,
            double userToSatThroughputMbps,
            // 第二跳：卫星 → 信关站
            String gatewayStationId,
            String gatewayStationName,
            double satToGatewayElevationDeg,
            double satToGatewayRangeKm,
            double satToGatewaySnrDb,
            double satToGatewayThroughputMbps,
            // 端到端指标
            double totalLatencyMs,           // 总时延（双跳双向传播 + 处理）
            double bottleneckThroughputMbps  // 瓶颈吞吐量 = min(两跳吞吐量)
    ) {}
}
