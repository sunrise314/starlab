package com.starlab.api;

import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.InterferenceCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 干扰分析 REST API
 * <p>
 * 端点：
 *   POST /api/interference/analyze  分析当前时刻目标卫星与地面站链路的同频/邻信道/宽带噪声干扰
 *   <p>
 * Body（全可选，缺省时全组合分析）：
 * <pre>
 * {
 *   "targetSatelliteId": "STARLAB-01",  // 可选，指定目标卫星
 *   "stationId":         "BJO",         // 可选，指定地面站
 *   "start":             "2026-09-22T14:00:00Z"  // 可选，缺省 now()
 * }
 * </pre>
 * 返回：
 * <pre>
 * {
 *   "analyzedAt": "...",
 *   "targetSatelliteId": "STARLAB-01" | null,
 *   "stationId":         "BJO" | null,
 *   "totalCombos": 60,                   // 分析的 sat×station 组合数
 *   "availableCount": 32,               // linkAvailable=true 的组合数
 *   "availabilityPct": 53.3,            // 可用率
 *   "avgCirDb": 18.7,                    // 平均载干比
 *   "worstCirDb": -3.2,                  // 最差载干比
 *   "avgInterferenceDbm": -85.4,         // 平均干扰功率
 *   "results": [ LinkResult, ... ]      // 详细链路结果（含干扰字段）
 * }
 * </pre>
 */
@RestController
@RequestMapping("/api/interference")
public class InterferenceController {

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final InterferenceCalculator interferenceCalculator;

    public InterferenceController(Constellation constellation,
                                  OrbitPropagator propagator,
                                  InterferenceCalculator interferenceCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.interferenceCalculator = interferenceCalculator;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@RequestBody(required = false) AnalyzeRequest req) {
        Instant t = req != null && req.start != null ? Instant.parse(req.start) : Instant.now();

        // 1. 当前时刻所有卫星位置
        List<SatellitePosition> allSats = constellation.getSatellites().stream()
                .map(el -> propagator.propagate(el, t))
                .toList();

        // 2. 选取目标卫星子集
        List<SatellitePosition> targetSats;
        if (req != null && req.targetSatelliteId != null) {
            targetSats = allSats.stream()
                    .filter(s -> req.targetSatelliteId.equals(s.satelliteId()))
                    .toList();
            if (targetSats.isEmpty()) {
                throw new IllegalArgumentException("卫星不存在: " + req.targetSatelliteId);
            }
        } else {
            targetSats = allSats;
        }

        // 3. 选取地面站子集
        List<GroundStation> stations;
        if (req != null && req.stationId != null) {
            GroundStation st = constellation.getGroundStation(req.stationId);
            if (st == null) {
                throw new IllegalArgumentException("地面站不存在: " + req.stationId);
            }
            stations = List.of(st);
        } else {
            stations = constellation.getGroundStations();
        }

        // 4. 计算每个 (sat, station) 组合的干扰
        List<LinkResult> results = new ArrayList<>(targetSats.size() * stations.size());
        for (SatellitePosition sat : targetSats) {
            for (GroundStation st : stations) {
                results.add(interferenceCalculator.analyze(sat, st, allSats));
            }
        }

        // 5. 统计聚合
        int total = results.size();
        int available = 0;
        double sumCir = 0;
        double worstCir = Double.POSITIVE_INFINITY;
        double sumInterf = 0;
        int interfNonZero = 0;

        for (LinkResult r : results) {
            if (r.linkAvailable()) available++;
            if (r.visible()) {  // 仅可见链路参与 C/I 统计
                sumCir += r.cirDb();
                if (r.cirDb() < worstCir) worstCir = r.cirDb();
                sumInterf += r.interferencePowerDbm();
                interfNonZero++;
            }
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCombos", total);
        stats.put("availableCount", available);
        stats.put("availabilityPct", total > 0
                ? Math.round(available * 1000.0 / total) / 10.0 : 0);
        stats.put("avgCirDb", interfNonZero > 0
                ? Math.round(sumCir * 100.0 / interfNonZero) / 100.0 : 0);
        stats.put("worstCirDb", interfNonZero > 0
                ? Math.round(worstCir * 100.0) / 100.0 : 0);
        stats.put("avgInterferenceDbm", interfNonZero > 0
                ? Math.round(sumInterf * 100.0 / interfNonZero) / 100.0 : 0);
        stats.put("coChannelAvg", interfNonZero > 0
                ? Math.round(results.stream()
                    .filter(LinkResult::visible)
                    .mapToInt(LinkResult::coChannelCount)
                    .average().orElse(0) * 100.0) / 100.0
                : 0);

        Map<String, Object> response = new HashMap<>();
        response.put("analyzedAt", t.toString());
        response.put("targetSatelliteId", req != null ? req.targetSatelliteId : null);
        response.put("stationId", req != null ? req.stationId : null);
        response.put("statistics", stats);
        response.put("results", results);
        return response;
    }

    public record AnalyzeRequest(
            String targetSatelliteId,  // 可选，目标卫星 ID
            String stationId,           // 可选，地面站 ID
            String start                 // 可选，ISO 时间，缺省 now()
    ) {}
}
