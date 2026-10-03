package com.starlab.orbit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SGP4-lite 黄金数据断言：与 skyfield（完整 SGP4）对比 ISS 真实 TLE 的 24h 传播误差。
 * <p>
 * 黄金数据由 tools/golden_sgp4.py 生成（历元起 0~24h，每 1h 一点，ITRS/ECEF km）。
 * 断言的含义：自研简化传播器对 LEO 卫星 24 小时位置漂移有上界保证，
 * 让"物理上基本正确"从一句自我评价变成一条 CI 里的断言。
 */
class Sgp4GoldenDataTest {

    /** 误差上界 (km)。依据 2026-10-03 ISS TLE 实测最大误差 14.7 km 留 ~35% 余量设定。 */
    private static final double MAX_ERROR_KM = 20.0;

    @Test
    void issTle24hErrorWithinBound() throws Exception {
        JsonNode doc;
        try (InputStream in = getClass().getResourceAsStream("/golden/iss-sgp4.json")) {
            doc = new ObjectMapper().readTree(in);
        }

        OrbitalElements el = TleParser.parse(
                doc.get("name").asText(), doc.get("line1").asText(), doc.get("line2").asText());
        Sgp4Propagator propagator = new Sgp4Propagator();

        System.out.println("=== SGP4-lite vs skyfield(SGP4 full) | " + el.name()
                + " | epoch " + doc.get("epoch").asText() + " ===");
        System.out.println("hours   error(km)   error/radius");

        double maxErr = 0, sumErr = 0;
        int n = 0;
        for (JsonNode p : doc.get("points")) {
            Instant t = Instant.parse(p.get("t").asText());
            SatellitePosition pos = propagator.propagate(el, t);
            double dx = pos.ecefX() - p.get("x").asDouble();
            double dy = pos.ecefY() - p.get("y").asDouble();
            double dz = pos.ecefZ() - p.get("z").asDouble();
            double err = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double radius = Math.sqrt(p.get("x").asDouble() * p.get("x").asDouble()
                    + p.get("y").asDouble() * p.get("y").asDouble()
                    + p.get("z").asDouble() * p.get("z").asDouble());
            maxErr = Math.max(maxErr, err);
            sumErr += err;
            n++;
            System.out.printf("%5d   %8.3f     %6.3f%%%n", p.get("hours").asInt(), err, 100 * err / radius);
        }

        System.out.printf("max error = %.3f km, mean = %.3f km over %d points%n", maxErr, sumErr / n, n);
        assertTrue(maxErr < MAX_ERROR_KM,
                "SGP4-lite 24h 最大误差 " + maxErr + " km 超过断言上界 " + MAX_ERROR_KM + " km");
    }
}
