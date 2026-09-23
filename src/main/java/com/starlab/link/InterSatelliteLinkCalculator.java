package com.starlab.link;

import com.starlab.config.SimConstants;
import com.starlab.constellation.Constellation;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 星间链路 (ISL) 计算器 + 拓扑发现 + 多跳路由（方向6）
 * <p>
 * 物理模型：
 *   - 星间距离：ECEF 笛卡尔距离（无大气）
 *   - 单向传播时延：d / c（c = 299792.458 km/s）
 *   - 地球遮挡判定：两卫星对地心夹角 θ 与各自水平面角 α_i 比较
 *     α_i = arccos(R_⊕ / |v_i|) — 卫星 i 处地球水平线对地心的角度
 *     当 θ < α_1 + α_2，两星互相在对方水平面之上 → 可见
 *   - linkType：通过 RAAN 差判定同面/跨面（差 < 5° 视为同面）
 * <p>
 * 路由：Dijkstra 最短时延路径。地面站起点是虚拟节点，
 *   通过星地链路接入可见卫星，再走 ISL 多跳到目的卫星。
 */
@Component
public class InterSatelliteLinkCalculator {

    /** ISL 最大作用距离 (km) — 激光 ISL 典型 ~5000 km */
    private static final double DEFAULT_MAX_ISL_DISTANCE_KM = 5000.0;
    /** 同面 ISL 最大距离 (km) — 同轨道半周长 ~21700 km，取保守值 20000 */
    private static final double INTRA_PLANE_MAX_DISTANCE_KM = 20000.0;
    /** RAAN 差阈值 (度) — 小于该值视为同轨道面 */
    private static final double SAME_PLANE_RAAN_THRESHOLD_DEG = 5.0;

    private final Constellation constellation;

    public InterSatelliteLinkCalculator(Constellation constellation) {
        this.constellation = constellation;
    }

    // ── 单条 ISL 计算 ──

    /**
     * 计算两颗卫星之间的 ISL 状态
     */
    public InterSatelliteLink computeLink(SatellitePosition sat1, SatellitePosition sat2) {
        double dx = sat1.ecefX() - sat2.ecefX();
        double dy = sat1.ecefY() - sat2.ecefY();
        double dz = sat1.ecefZ() - sat2.ecefZ();
        double distanceKm = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 时延 (ms) = 距离 / 光速 (km/s) × 1000
        double latencyMs = Math.round(distanceKm / SimConstants.SPEED_OF_LIGHT * 1000.0 * 1000.0) / 1000.0;

        // 同面/跨面分类（先算，影响可见性判定策略）
        String linkType = classifyLinkType(sat1.satelliteId(), sat2.satelliteId());

        // 可见性判定：
        //   - 同面卫星（intra-plane）：跳过 LOS 遮挡检查
        //     理由：实际 LEO 星座中同面前后卫星的 ISL 是预规划的，
        //     通过星上相控阵天线定向锁定，即使 meanAnomaly 差较大，
        //     工程上仍维持链路（距离越远时延越大，但链路本身是通的）
        //   - 跨面卫星（cross-plane）：严格 LOS 遮挡判定
        boolean visible;
        if ("intra-plane".equals(linkType)) {
            visible = true;  // 同面默认可见（距离上限由调用方在 discoverTopology 中控制）
        } else {
            visible = isLineOfSightClear(sat1, sat2);
        }

        return new InterSatelliteLink(
                sat1.satelliteId(), sat2.satelliteId(),
                Math.round(distanceKm * 100.0) / 100.0,
                latencyMs, visible, linkType
        );
    }

    /**
     * 地球遮挡判定（cone 法）
     * <p>
     * α_i = arccos(R_⊕ / r_i) — 卫星 i 的水平面角（对地心）
     * θ   = arccos(v1·v2 / (|v1|·|v2|)) — 两星对地心夹角
     * 可见 ⟺ θ < α_1 + α_2
     */
    private boolean isLineOfSightClear(SatellitePosition sat1, SatellitePosition sat2) {
        double r1 = Math.sqrt(sat1.ecefX() * sat1.ecefX()
                + sat1.ecefY() * sat1.ecefY()
                + sat1.ecefZ() * sat1.ecefZ());
        double r2 = Math.sqrt(sat2.ecefX() * sat2.ecefX()
                + sat2.ecefY() * sat2.ecefY()
                + sat2.ecefZ() * sat2.ecefZ());
        if (r1 <= SimConstants.EARTH_RADIUS || r2 <= SimConstants.EARTH_RADIUS) return false;

        double alpha1 = Math.acos(SimConstants.EARTH_RADIUS / r1);
        double alpha2 = Math.acos(SimConstants.EARTH_RADIUS / r2);

        double dot = sat1.ecefX() * sat2.ecefX()
                + sat1.ecefY() * sat2.ecefY()
                + sat1.ecefZ() * sat2.ecefZ();
        double cosTheta = dot / (r1 * r2);
        cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
        double theta = Math.acos(cosTheta);

        return theta < (alpha1 + alpha2);
    }

    /**
     * 通过 RAAN 差判断 linkType
     */
    private String classifyLinkType(String sat1Id, String sat2Id) {
        OrbitalElements e1 = constellation.getSatellite(sat1Id);
        OrbitalElements e2 = constellation.getSatellite(sat2Id);
        if (e1 == null || e2 == null) return "unknown";
        double raanDiff = Math.abs(e1.raan() - e2.raan());
        // 处理 0~360 跨界
        if (raanDiff > 180) raanDiff = 360 - raanDiff;
        return raanDiff < SAME_PLANE_RAAN_THRESHOLD_DEG ? "intra-plane" : "cross-plane";
    }

    // ── 拓扑发现 ──

    /**
     * 发现当前时刻所有可见的 ISL
     * <p>
     * 距离上限策略：
     *   - 同面（intra-plane）：INTRA_PLANE_MAX_DISTANCE_KM（默认 20000 km，轨道半周长）
     *     理由：同面卫星在同一圆形轨道上，距离上限取轨道直径量级，
     *     实际激光 ISL 作用距离 ~5000 km 但同面 ISL 是预规划好的，放宽上限。
     *   - 跨面（cross-plane）：maxDistanceKm 由调用方指定（默认 5000 km）
     */
    public List<InterSatelliteLink> discoverTopology(List<SatellitePosition> allSats,
                                                      double maxDistanceKm) {
        List<InterSatelliteLink> links = new ArrayList<>();
        for (int i = 0; i < allSats.size(); i++) {
            for (int j = i + 1; j < allSats.size(); j++) {
                InterSatelliteLink link = computeLink(allSats.get(i), allSats.get(j));
                if (!link.visible()) continue;
                // 同面用大上限，跨面用调用方给的上限
                double limit = "intra-plane".equals(link.linkType())
                        ? INTRA_PLANE_MAX_DISTANCE_KM
                        : maxDistanceKm;
                if (link.distanceKm() <= limit) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /** 使用默认最大距离 5000 km */
    public List<InterSatelliteLink> discoverTopology(List<SatellitePosition> allSats) {
        return discoverTopology(allSats, DEFAULT_MAX_ISL_DISTANCE_KM);
    }

    // ── 多跳路由 ──

    /**
     * 卫星→卫星 最短时延路由（Dijkstra）
     */
    public ISLRouteResult findRouteSatToSat(String sourceSatId,
                                            String destSatId,
                                            List<SatellitePosition> allSats,
                                            double maxDistanceKm) {
        Map<String, SatellitePosition> satById = new HashMap<>();
        for (SatellitePosition s : allSats) satById.put(s.satelliteId(), s);

        if (!satById.containsKey(sourceSatId)) {
            return new ISLRouteResult(sourceSatId, destSatId, List.of(), 0, 0, false, "source satellite not found");
        }
        if (!satById.containsKey(destSatId)) {
            return new ISLRouteResult(sourceSatId, destSatId, List.of(), 0, 0, false, "destination satellite not found");
        }
        if (sourceSatId.equals(destSatId)) {
            return new ISLRouteResult(sourceSatId, destSatId, List.of(sourceSatId), 0, 0, true, "same node");
        }

        // 构图：节点=卫星，边=可见 ISL
        Map<String, List<Edge>> graph = buildISLGraph(allSats, maxDistanceKm);
        return dijkstra(graph, sourceSatId, destSatId);
    }

    /**
     * 地面站→卫星 最短时延路由（首跳星地 + 后续 ISL）
     * <p>
     * 起点是虚拟节点 "STATION:{stationId}"，第一跳边权重为星地单向时延。
     */
    public ISLRouteResult findRouteStationToSat(GroundStation station,
                                                 String destSatId,
                                                 List<SatellitePosition> allSats,
                                                 double maxDistanceKm) {
        Map<String, SatellitePosition> satById = new HashMap<>();
        for (SatellitePosition s : allSats) satById.put(s.satelliteId(), s);

        if (!satById.containsKey(destSatId)) {
            return new ISLRouteResult(station.id(), destSatId, List.of(), 0, 0, false, "destination satellite not found");
        }

        String sourceNode = "STATION:" + station.id();

        // 构图：先建 ISL 图，再加入地面站虚拟节点
        Map<String, List<Edge>> graph = buildISLGraph(allSats, maxDistanceKm);
        // 地面站 ECEF
        double[] stEcef = com.starlab.orbit.GeoConverter.llaToEcef(
                station.latitude(), station.longitude(), station.altitude());
        // 对每颗可见卫星加接入边
        List<Edge> stationEdges = new ArrayList<>();
        for (SatellitePosition sat : allSats) {
            double dx = sat.ecefX() - stEcef[0];
            double dy = sat.ecefY() - stEcef[1];
            double dz = sat.ecefZ() - stEcef[2];
            double distanceKm = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // 仰角检查（简化：只接入可见卫星）
            double elevation = computeElevationDeg(sat, station);
            if (elevation < Math.toDegrees(SimConstants.MIN_ELEVATION_RAD)) continue;
            double latencyMs = distanceKm / SimConstants.SPEED_OF_LIGHT * 1000.0;
            stationEdges.add(new Edge(sat.satelliteId(), latencyMs));
            // 反向边也加入（卫星→地面站，用于回溯不影响）
            graph.computeIfAbsent(sat.satelliteId(), k -> new ArrayList<>())
                .add(new Edge(sourceNode, latencyMs));
        }
        graph.put(sourceNode, stationEdges);

        ISLRouteResult result = dijkstra(graph, sourceNode, destSatId);
        // 路径中保留 "STATION:<id>" 前缀，由 controller 在 segments 解析时识别
        // source 字段使用真实 station.id()，便于前端展示
        if (result.reachable()) {
            return new ISLRouteResult(station.id(), destSatId, result.path(),
                    result.hopCount(), result.totalLatencyMs(), true, null);
        }
        // 不可达：把 source 字段也用真实 station.id() 替换
        return new ISLRouteResult(station.id(), destSatId, result.path(),
                result.hopCount(), result.totalLatencyMs(), false, result.reason());
    }

    /**
     * 构造 ISL 拓扑图（无向图，双向加边）
     * 同面链路用 INTRA_PLANE_MAX_DISTANCE_KM，跨面用 maxDistanceKm
     */
    private Map<String, List<Edge>> buildISLGraph(List<SatellitePosition> allSats,
                                                   double maxDistanceKm) {
        Map<String, List<Edge>> graph = new HashMap<>();
        for (SatellitePosition s : allSats) graph.put(s.satelliteId(), new ArrayList<>());
        for (int i = 0; i < allSats.size(); i++) {
            for (int j = i + 1; j < allSats.size(); j++) {
                InterSatelliteLink link = computeLink(allSats.get(i), allSats.get(j));
                if (!link.visible()) continue;
                double limit = "intra-plane".equals(link.linkType())
                        ? INTRA_PLANE_MAX_DISTANCE_KM
                        : maxDistanceKm;
                if (link.distanceKm() > limit) continue;
                graph.get(link.sat1Id()).add(new Edge(link.sat2Id(), link.latencyMs()));
                graph.get(link.sat2Id()).add(new Edge(link.sat1Id(), link.latencyMs()));
            }
        }
        return graph;
    }

    /**
     * 计算卫星对地面站的仰角（度）
     */
    private double computeElevationDeg(SatellitePosition sat, GroundStation station) {
        double[] stEcef = com.starlab.orbit.GeoConverter.llaToEcef(
                station.latitude(), station.longitude(), station.altitude());
        double dx = sat.ecefX() - stEcef[0];
        double dy = sat.ecefY() - stEcef[1];
        double dz = sat.ecefZ() - stEcef[2];
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double lat = Math.toRadians(station.latitude());
        double lon = Math.toRadians(station.longitude());
        double up = Math.cos(lat) * Math.cos(lon) * dx
                + Math.cos(lat) * Math.sin(lon) * dy
                + Math.sin(lat) * dz;
        return Math.toDegrees(Math.asin(up / range));
    }

    /**
     * Dijkstra 最短时延路径
     */
    private ISLRouteResult dijkstra(Map<String, List<Edge>> graph,
                                     String source, String dest) {
        Map<String, Double> dist = new HashMap<>();
        Map<String, String> prev = new HashMap<>();
        PriorityQueue<Edge> pq = new PriorityQueue<>(Comparator.comparingDouble(e -> e.weight));
        dist.put(source, 0.0);
        pq.offer(new Edge(source, 0.0));

        while (!pq.isEmpty()) {
            Edge cur = pq.poll();
            if (cur.node.equals(dest)) break;
            if (cur.weight > dist.getOrDefault(cur.node, Double.POSITIVE_INFINITY)) continue;
            for (Edge nb : graph.getOrDefault(cur.node, List.of())) {
                double nd = cur.weight + nb.weight;
                if (nd < dist.getOrDefault(nb.node, Double.POSITIVE_INFINITY)) {
                    dist.put(nb.node, nd);
                    prev.put(nb.node, cur.node);
                    pq.offer(new Edge(nb.node, nd));
                }
            }
        }

        if (!dist.containsKey(dest)) {
            return new ISLRouteResult(source, dest, List.of(), 0, 0, false, "no visible path");
        }

        // 回溯路径
        List<String> path = new ArrayList<>();
        for (String cur = dest; cur != null; cur = prev.get(cur)) {
            path.add(cur);
        }
        Collections.reverse(path);

        double totalLatency = Math.round(dist.get(dest) * 100.0) / 100.0;
        return new ISLRouteResult(source, dest, path, path.size() - 1, totalLatency, true, null);
    }

    /** Dijkstra 内部边表示 */
    private record Edge(String node, double weight) {}
}
