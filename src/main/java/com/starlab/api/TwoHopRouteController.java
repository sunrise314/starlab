package com.starlab.api;

import com.starlab.route.TwoHopRouteCalculator;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * 多用户终端两跳路由 REST API
 * <p>
 * 端点：
 *   POST /api/route/two-hop  —— 计算指定用户位置的两跳路由
 *     Body: { lat: 39.9, lon: 116.4, time?: "ISO" }
 *     Response: { userLat, userLon, time, routes: [...] }
 * <p>
 * 用例：手机用户 / 偏远地区终端 / 应急通信终端接入星座，
 * 经卫星中继到最近信关站，再走地面光纤到达服务网。
 */
@RestController
@RequestMapping("/api/route")
public class TwoHopRouteController {

    private final TwoHopRouteCalculator calculator;

    public TwoHopRouteController(TwoHopRouteCalculator calculator) {
        this.calculator = calculator;
    }

    /**
     * 计算两跳路由
     * <p>
     * POST /api/route/two-hop
     * Body: { "lat": 39.9, "lon": 116.4, "time"?: "2026-09-22T08:00:00Z" }
     */
    @PostMapping("/two-hop")
    public Map<String, Object> twoHop(@RequestBody TwoHopRequest req) {
        if (req == null || req.lat == null || req.lon == null) {
            throw new IllegalArgumentException("lat 和 lon 必填");
        }
        double lat = Math.min(Math.max(req.lat, -90), 90);
        double lon = Math.min(Math.max(req.lon, -180), 180);
        Instant t = req.time != null && !req.time.isBlank()
                ? Instant.parse(req.time)
                : Instant.now();

        TwoHopRouteCalculator.TwoHopResult result = calculator.calculate(lat, lon, t);
        return Map.of(
                "userLat", result.userLat(),
                "userLon", result.userLon(),
                "time", result.time(),
                "routes", result.routes()
        );
    }

    public record TwoHopRequest(Double lat, Double lon, String time) {}
}
