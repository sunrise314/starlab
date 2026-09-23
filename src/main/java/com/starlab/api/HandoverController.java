package com.starlab.api;

import com.starlab.handover.*;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * STCN 切换仿真 REST API v2 —— 支持算法选择 + 对比
 */
@RestController
@RequestMapping("/api/handover")
public class HandoverController {

    private final SimulationEngine engine;
    private final GreedyHandoverStrategy greedy;
    private final PredictiveHandoverStrategy predictive;
    private final InterferenceAwareHandoverStrategy interferenceAware;

    public HandoverController(SimulationEngine engine,
                              GreedyHandoverStrategy greedy,
                              PredictiveHandoverStrategy predictive,
                              InterferenceAwareHandoverStrategy interferenceAware) {
        this.engine = engine;
        this.greedy = greedy;
        this.predictive = predictive;
        this.interferenceAware = interferenceAware;
    }

    /**
     * 运行一次切换仿真
     * POST /api/handover/simulate
     * Body: { start?, durationSec: 600, tickSec: 10, algorithm: "greedy"|"predictive" }
     */
    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody(required = false) SimulateRequest req) {
        int durationSec = req != null ? req.durationSec : 600;
        int tickSec = req != null ? req.tickSec : 10;
        Instant start = req != null && req.start != null
                ? Instant.parse(req.start)
                : Instant.now();
        String algoId = req != null && req.algorithm != null ? req.algorithm : "greedy";

        durationSec = Math.min(Math.max(durationSec, 10), 7200);
        tickSec = Math.min(Math.max(tickSec, 1), 60);

        HandoverStrategy strategy = resolveStrategy(algoId);
        SimulationEngine.SimulationResult result = engine.simulate(strategy, start, durationSec, tickSec);

        return Map.of(
                "algorithmId", result.algorithmId(),
                "simStart", result.simStart(),
                "simEnd", result.simEnd(),
                "durationSec", result.durationSec(),
                "tickSec", result.tickSec(),
                "totalTicks", result.totalTicks(),
                "handoverCount", result.handoverCount(),
                "handoverRatePerMin", Math.round(result.handoverRatePerMin() * 100.0) / 100.0,
                "events", result.events()
        );
    }

    /**
     * 跑仿真并返回每个 tick 的快照（用于前端时间轴回放）
     * POST /api/handover/timeline
     * Body: { start?, durationSec: 3600, tickSec: 10, algorithm: "greedy"|"predictive" }
     * <p>
     * 返回结构：
     *   - simStart/simEnd/durationSec/tickSec/totalTicks/handoverCount/events（与 simulate 相同）
     *   - timeline: List<TickSnapshot>，每 tick 包含 12 颗卫星的：
     *     satId/satName/stationId/stationName/elevationDeg/latencyMs/rangeKm/latitude/longitude/altitude/linkStatus
     */
    @PostMapping("/timeline")
    public Map<String, Object> timeline(@RequestBody(required = false) SimulateRequest req) {
        int durationSec = req != null ? req.durationSec : 3600;
        int tickSec = req != null ? req.tickSec : 10;
        Instant start = req != null && req.start != null
                ? Instant.parse(req.start)
                : Instant.now();
        String algoId = req != null && req.algorithm != null ? req.algorithm : "greedy";

        durationSec = Math.min(Math.max(durationSec, 10), 7200);
        tickSec = Math.min(Math.max(tickSec, 1), 60);

        HandoverStrategy strategy = resolveStrategy(algoId);
        SimulationEngine.TimelineResult result = engine.simulateWithTimeline(strategy, start, durationSec, tickSec);

        return Map.of(
                "algorithmId", result.algorithmId(),
                "simStart", result.simStart(),
                "simEnd", result.simEnd(),
                "durationSec", result.durationSec(),
                "tickSec", result.tickSec(),
                "totalTicks", result.totalTicks(),
                "handoverCount", result.handoverCount(),
                "handoverRatePerMin", Math.round(result.handoverRatePerMin() * 100.0) / 100.0,
                "events", result.events(),
                "timeline", result.timeline()
        );
    }

    /**
     * 三算法对比 —— 同参数跑 greedy / predictive / interference-aware
     * POST /api/handover/compare
     * Body: { start?, durationSec: 3600, tickSec: 10 }
     */
    @PostMapping("/compare")
    public Map<String, Object> compare(@RequestBody(required = false) SimulateRequest req) {
        int durationSec = req != null ? req.durationSec : 3600;
        int tickSec = req != null ? req.tickSec : 10;
        Instant start = req != null && req.start != null
                ? Instant.parse(req.start)
                : Instant.now();

        durationSec = Math.min(Math.max(durationSec, 10), 7200);
        tickSec = Math.min(Math.max(tickSec, 1), 60);

        SimulationEngine.TripleCompareResult cmp = engine.compareThree(
                greedy, predictive, interferenceAware, start, durationSec, tickSec);

        // greedy vs predictive 改进
        double reducedSwitchesV2 = cmp.result1().handoverCount() - cmp.result2().handoverCount();
        double latencyDeltaV2 = cmp.avgLatency2() - cmp.avgLatency1();
        double linkBreakDeltaV2 = cmp.linkBreakCount2() - cmp.linkBreakCount1();

        // greedy vs interference-aware 改进
        double reducedSwitchesV3 = cmp.result1().handoverCount() - cmp.result3().handoverCount();
        double latencyDeltaV3 = cmp.avgLatency3() - cmp.avgLatency1();
        double linkBreakDeltaV3 = cmp.linkBreakCount3() - cmp.linkBreakCount1();

        return Map.of(
                "greedy", buildAlgoResult(cmp.algo1Id(), cmp.algo1Name(), cmp.result1(), cmp.avgLatency1(), cmp.linkBreakCount1()),
                "predictive", buildAlgoResult(cmp.algo2Id(), cmp.algo2Name(), cmp.result2(), cmp.avgLatency2(), cmp.linkBreakCount2()),
                "interferenceAware", buildAlgoResult(cmp.algo3Id(), cmp.algo3Name(), cmp.result3(), cmp.avgLatency3(), cmp.linkBreakCount3()),
                "improvementV2", Map.of(
                        "reducedSwitches", reducedSwitchesV2,
                        "reducedSwitchesPct", cmp.result1().handoverCount() > 0
                                ? Math.round(-reducedSwitchesV2 / cmp.result1().handoverCount() * 1000.0) / 10.0
                                : 0,
                        "latencyDeltaMs", Math.round(latencyDeltaV2 * 10.0) / 10.0,
                        "linkBreakDelta", linkBreakDeltaV2
                ),
                "improvementV3", Map.of(
                        "reducedSwitches", reducedSwitchesV3,
                        "reducedSwitchesPct", cmp.result1().handoverCount() > 0
                                ? Math.round(-reducedSwitchesV3 / cmp.result1().handoverCount() * 1000.0) / 10.0
                                : 0,
                        "latencyDeltaMs", Math.round(latencyDeltaV3 * 10.0) / 10.0,
                        "linkBreakDelta", linkBreakDeltaV3
                )
        );
    }

    private Map<String, Object> buildAlgoResult(String algoId, String algoName,
                                                SimulationEngine.SimulationResult r,
                                                double avgLatency, long linkBreakCount) {
        return Map.of(
                "algorithmId", algoId,
                "algorithmName", algoName,
                "handoverCount", r.handoverCount(),
                "handoverRatePerMin", Math.round(r.handoverRatePerMin() * 100.0) / 100.0,
                "avgLatencyMs", avgLatency,
                "linkBreakCount", linkBreakCount,
                "events", r.events()
        );
    }

    /** 查询最近切换事件 */
    @GetMapping("/events")
    public Map<String, Object> getRecentEvents(@RequestParam(defaultValue = "50") int limit) {
        limit = Math.min(Math.max(limit, 1), 500);
        return Map.of("total", limit, "events", engine.getRecentEvents(limit));
    }

    /** 清空环形缓冲 */
    @DeleteMapping("/events")
    public Map<String, Object> clearEvents() {
        engine.clear();
        return Map.of("status", "ok");
    }

    private HandoverStrategy resolveStrategy(String id) {
        if ("predictive".equalsIgnoreCase(id)) return predictive;
        if ("interference-aware".equalsIgnoreCase(id)) return interferenceAware;
        return greedy; // 默认贪心
    }

    public record SimulateRequest(
            String start,
            Integer durationSec,
            Integer tickSec,
            String algorithm  // "greedy" | "predictive"
    ) {}
}
