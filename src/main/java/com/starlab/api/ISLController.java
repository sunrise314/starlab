package com.starlab.api;

import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.InterSatelliteLink;
import com.starlab.link.InterSatelliteLinkCalculator;
import com.starlab.link.ISLRouteResult;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 星间链路 (ISL) REST API
 * <p>
 * 端点：
 *   POST /api/isl/topology  — 当前时刻 ISL 拓扑（visible 且 distance ≤ maxDistanceKm）
 *   POST /api/isl/route      — 多跳最短时延路由
 *   <p>
 * 拓扑 Body（全可选）：
 * <pre>
 * { "start": "2026-09-22T14:00:00Z", "maxDistanceKm": 5000 }
 * </pre>
 * 路由 Body：
 * <pre>
 * {
 *   "sourceSatelliteId": "STARLAB-01",  // 二选一（与 sourceStationId 互斥）
 *   "sourceStationId":   "BJO",         // 二选一
 *   "destinationSatelliteId": "STARLAB-08",
 *   "start":             "2026-09-22T14:00:00Z",  // 可选
 *   "maxDistanceKm":     5000                     // 可选
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/isl")
public class ISLController {

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final InterSatelliteLinkCalculator islCalculator;

    public ISLController(Constellation constellation,
                         OrbitPropagator propagator,
                         InterSatelliteLinkCalculator islCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.islCalculator = islCalculator;
    }

    /**
     * 当前 ISL 拓扑
     */
    @PostMapping("/topology")
    public Map<String, Object> topology(@RequestBody(required = false) TopologyRequest req) {
        Instant t = req != null && req.start != null ? Instant.parse(req.start) : Instant.now();
        double maxDist = req != null && req.maxDistanceKm != null ? req.maxDistanceKm : 5000.0;

        List<SatellitePosition> allSats = constellation.getSatellites().stream()
                .map(el -> propagator.propagate(el, t))
                .toList();
        List<InterSatelliteLink> links = islCalculator.discoverTopology(allSats, maxDist);

        // 邻接表
        Map<String, List<String>> adjacency = new HashMap<>();
        for (SatellitePosition s : allSats) adjacency.put(s.satelliteId(), new java.util.ArrayList<>());
        for (InterSatelliteLink l : links) {
            adjacency.get(l.sat1Id()).add(l.sat2Id());
            adjacency.get(l.sat2Id()).add(l.sat1Id());
        }

        // 统计
        long intraCount = links.stream().filter(l -> "intra-plane".equals(l.linkType())).count();
        long crossCount = links.stream().filter(l -> "cross-plane".equals(l.linkType())).count();
        double avgDistance = links.stream().mapToDouble(InterSatelliteLink::distanceKm).average().orElse(0);
        double maxDistance = links.stream().mapToDouble(InterSatelliteLink::distanceKm).max().orElse(0);
        double avgLatency = links.stream().mapToDouble(InterSatelliteLink::latencyMs).average().orElse(0);

        // 每颗卫星的 ISL 邻居数（度数）
        Map<String, Integer> degrees = new HashMap<>();
        for (Map.Entry<String, List<String>> e : adjacency.entrySet()) {
            degrees.put(e.getKey(), e.getValue().size());
        }
        double avgDegree = degrees.values().stream().mapToInt(Integer::intValue).average().orElse(0);

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalLinks", links.size());
        stats.put("intraPlaneLinks", intraCount);
        stats.put("crossPlaneLinks", crossCount);
        stats.put("avgDistanceKm", Math.round(avgDistance * 100.0) / 100.0);
        stats.put("maxDistanceKm", Math.round(maxDistance * 100.0) / 100.0);
        stats.put("avgLatencyMs", Math.round(avgLatency * 1000.0) / 1000.0);
        stats.put("avgDegree", Math.round(avgDegree * 100.0) / 100.0);
        stats.put("maxIslDistanceKm", maxDist);

        Map<String, Object> response = new HashMap<>();
        response.put("analyzedAt", t.toString());
        response.put("statistics", stats);
        response.put("adjacency", adjacency);
        response.put("degrees", degrees);
        response.put("links", links);
        return response;
    }

    /**
     * 多跳最短时延路由
     */
    @PostMapping("/route")
    public Map<String, Object> route(@RequestBody RouteRequest req) {
        if (req == null || req.destinationSatelliteId == null) {
            throw new IllegalArgumentException("参数缺失：destinationSatelliteId 必填");
        }
        if ((req.sourceSatelliteId == null) == (req.sourceStationId == null)) {
            throw new IllegalArgumentException("sourceSatelliteId 与 sourceStationId 二选一");
        }

        Instant t = req.start != null ? Instant.parse(req.start) : Instant.now();
        double maxDist = req.maxDistanceKm != null ? req.maxDistanceKm : 5000.0;

        List<SatellitePosition> allSats = constellation.getSatellites().stream()
                .map(el -> propagator.propagate(el, t))
                .toList();

        ISLRouteResult result;
        if (req.sourceStationId != null) {
            GroundStation st = constellation.getGroundStation(req.sourceStationId);
            if (st == null) {
                throw new IllegalArgumentException("地面站不存在: " + req.sourceStationId);
            }
            result = islCalculator.findRouteStationToSat(st, req.destinationSatelliteId, allSats, maxDist);
        } else {
            result = islCalculator.findRouteSatToSat(
                    req.sourceSatelliteId, req.destinationSatelliteId, allSats, maxDist);
        }

        // 构建路径上的逐段时延明细（便于前端展示）
        List<Map<String, Object>> segments = new java.util.ArrayList<>();
        if (result.reachable() && result.path().size() >= 2) {
            Map<String, SatellitePosition> satById = allSats.stream()
                    .collect(Collectors.toMap(SatellitePosition::satelliteId, s -> s));
            for (int i = 0; i < result.path().size() - 1; i++) {
                String from = result.path().get(i);
                String to = result.path().get(i + 1);
                String segType;
                double segLatencyMs;
                if (from.startsWith("STATION:")) {
                    // 星地接入段
                    String realStationId = from.substring("STATION:".length());
                    GroundStation st = constellation.getGroundStation(realStationId);
                    SatellitePosition sat = satById.get(to);
                    segType = "ground-to-sat";
                    segLatencyMs = computeGroundSatLatencyMs(sat, st);
                } else if (to.startsWith("STATION:")) {
                    String realStationId = to.substring("STATION:".length());
                    GroundStation st = constellation.getGroundStation(realStationId);
                    SatellitePosition sat = satById.get(from);
                    segType = "sat-to-ground";
                    segLatencyMs = computeGroundSatLatencyMs(sat, st);
                } else {
                    SatellitePosition s1 = satById.get(from);
                    SatellitePosition s2 = satById.get(to);
                    InterSatelliteLink link = islCalculator.computeLink(s1, s2);
                    segType = link.linkType();
                    segLatencyMs = link.latencyMs();
                }
                Map<String, Object> seg = new HashMap<>();
                seg.put("from", from.startsWith("STATION:") ? from.substring("STATION:".length()) : from);
                seg.put("to", to.startsWith("STATION:") ? to.substring("STATION:".length()) : to);
                seg.put("type", segType);
                seg.put("latencyMs", Math.round(segLatencyMs * 1000.0) / 1000.0);
                segments.add(seg);
            }
        }

        Map<String, Object> response = new HashMap<>();
        response.put("analyzedAt", t.toString());
        response.put("source", result.source());
        response.put("destination", result.destination());
        // 清洗 path 中的 "STATION:<id>" → "<id>"，便于前端直接展示
        List<String> cleanedPath = result.path().stream()
                .map(n -> n.startsWith("STATION:") ? n.substring("STATION:".length()) : n)
                .toList();
        response.put("path", cleanedPath);
        response.put("hopCount", result.hopCount());
        response.put("totalLatencyMs", result.totalLatencyMs());
        response.put("reachable", result.reachable());
        response.put("reason", result.reason());
        response.put("segments", segments);
        return response;
    }

    /** 计算星地单向时延 (ms) */
    private double computeGroundSatLatencyMs(SatellitePosition sat, GroundStation st) {
        double[] stEcef = com.starlab.orbit.GeoConverter.llaToEcef(
                st.latitude(), st.longitude(), st.altitude());
        double dx = sat.ecefX() - stEcef[0];
        double dy = sat.ecefY() - stEcef[1];
        double dz = sat.ecefZ() - stEcef[2];
        double distanceKm = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return distanceKm / com.starlab.config.SimConstants.SPEED_OF_LIGHT * 1000.0;
    }

    public record TopologyRequest(
            String start,
            Double maxDistanceKm
    ) {}

    public record RouteRequest(
            String sourceSatelliteId,        // 与 sourceStationId 互斥
            String sourceStationId,           // 与 sourceSatelliteId 互斥
            String destinationSatelliteId,    // 必填
            String start,
            Double maxDistanceKm
    ) {}
}
