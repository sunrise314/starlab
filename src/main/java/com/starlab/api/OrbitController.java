package com.starlab.api;

import com.starlab.constellation.Constellation;
import com.starlab.link.GroundStation;
import com.starlab.link.LinkCalculator;
import com.starlab.link.LinkResult;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.TleParser;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 星地智连仿真 REST API
 */
@RestController
@RequestMapping("/api")
public class OrbitController {

    private final Constellation constellation;
    private final OrbitPropagator propagator;
    private final LinkCalculator linkCalculator;

    public OrbitController(Constellation constellation,
                          OrbitPropagator propagator,
                          LinkCalculator linkCalculator) {
        this.constellation = constellation;
        this.propagator = propagator;
        this.linkCalculator = linkCalculator;
    }

    /**
     * 星座概览
     * GET /api/constellation
     */
    @GetMapping("/constellation")
    public Map<String, Object> getConstellationOverview() {
        double altitude = constellation.getSatellites().isEmpty()
                ? 0.0 : constellation.getSatellites().get(0).altitude();
        double meanMotion = constellation.getSatellites().isEmpty()
                ? 0.0 : constellation.getSatellites().get(0).meanMotion();
        String name = String.format("Starlab Walker-%d/%d/%d",
                constellation.getCurrentTotal(),
                constellation.getCurrentPlanes(),
                constellation.getCurrentF());
        return Map.ofEntries(
                Map.entry("name", name),
                Map.entry("totalSatellites", constellation.getSatelliteCount()),
                Map.entry("totalStations", constellation.getStationCount()),
                Map.entry("planes", constellation.getCurrentPlanes()),
                Map.entry("satsPerPlane", constellation.getCurrentSatsPerPlane()),
                Map.entry("inclination", constellation.getCurrentInclination()),
                Map.entry("altitude", Math.round(altitude * 100.0) / 100.0),
                Map.entry("phasingF", constellation.getCurrentF()),
                Map.entry("meanMotion", Math.round(meanMotion * 1000.0) / 1000.0),
                Map.entry("epoch", constellation.getEpoch().toString()),
                Map.entry("satellites", constellation.getSatellites().stream()
                        .map(el -> Map.of(
                                "id", el.satelliteId(),
                                "name", el.name(),
                                "raan", el.raan(),
                                "meanAnomaly", el.meanAnomaly(),
                                "altitude", el.altitude()
                        ))
                        .toList())
        );
    }

    /**
     * 重新配置星座参数（动态 Walker）
     * POST /api/constellation/configure
     * Body: { total: 24, planes: 6, inclination: 53.0, altitudeKm: 550, phasingF: 1 }
     * <p>
     * 调用后所有正在跑的仿真会被打断（卫星列表全变），属于预期行为。
     */
    @PostMapping("/constellation/configure")
    public Map<String, Object> configureConstellation(@RequestBody ConfigureRequest req) {
        if (req == null || req.total == null || req.planes == null
                || req.inclination == null || req.altitudeKm == null || req.phasingF == null) {
            throw new IllegalArgumentException("参数缺失：total/planes/inclination/altitudeKm/phasingF 必填");
        }
        constellation.reconfigure(
                req.total, req.planes, req.inclination, req.altitudeKm, req.phasingF);
        // 返回新概览
        return getConstellationOverview();
    }

    /** 重置为默认 Walker 12/3/1，53°/550km */
    @PostMapping("/constellation/reset")
    public Map<String, Object> resetConstellation() {
        constellation.resetToDefault();
        return getConstellationOverview();
    }

    public record ConfigureRequest(
            Integer total,
            Integer planes,
            Double inclination,
            Double altitudeKm,
            Integer phasingF
    ) {}

    /**
     * 所有卫星当前位置
     * GET /api/satellites
     */
    @GetMapping("/satellites")
    public List<SatellitePosition> getAllSatellites() {
        Instant now = Instant.now();
        return constellation.getSatellites().stream()
                .map(el -> propagator.propagate(el, now))
                .toList();
    }

    /**
     * 单颗卫星当前位置
     * GET /api/satellites/{id}
     */
    @GetMapping("/satellites/{id}")
    public SatellitePosition getSatellite(@PathVariable String id) {
        OrbitalElements el = constellation.getSatellite(id);
        if (el == null) {
            throw new IllegalArgumentException("卫星不存在: " + id);
        }
        return propagator.propagate(el, Instant.now());
    }

    /**
     * 所有地面站
     * GET /api/ground-stations
     */
    @GetMapping("/ground-stations")
    public List<GroundStation> getGroundStations() {
        return constellation.getGroundStations();
    }

    /**
     * 当前可见链路
     * GET /api/links
     */
    @GetMapping("/links")
    public List<LinkResult> getVisibleLinks() {
        Instant now = Instant.now();
        List<OrbitalElements> sats = constellation.getSatellites();
        List<GroundStation> stations = constellation.getGroundStations();
        List<LinkResult> links = new ArrayList<>();

        for (OrbitalElements el : sats) {
            SatellitePosition satPos = propagator.propagate(el, now);
            for (GroundStation station : stations) {
                LinkResult link = linkCalculator.calculate(satPos, station);
                if (link.visible()) {
                    links.add(link);
                }
            }
        }
        return links;
    }

    /**
     * 指定地面站的可见卫星
     * GET /api/ground-stations/{id}/visibility
     */
    @GetMapping("/ground-stations/{id}/visibility")
    public List<LinkResult> getStationVisibility(@PathVariable String id) {
        GroundStation station = constellation.getGroundStation(id);
        if (station == null) {
            throw new IllegalArgumentException("地面站不存在: " + id);
        }
        Instant now = Instant.now();
        List<LinkResult> results = new ArrayList<>();

        for (OrbitalElements el : constellation.getSatellites()) {
            SatellitePosition satPos = propagator.propagate(el, now);
            results.add(linkCalculator.calculate(satPos, station));
        }
        return results;
    }

    // ── SGP4 TLE 导入 ──

    /**
     * 解析 TLE 并返回传播后的位置（不修改星座）
     * POST /api/orbit/propagate
     * Body: { name?, line1, line2, time? }
     */
    @PostMapping("/orbit/propagate")
    public Map<String, Object> propagateFromTle(@RequestBody TlePropagateRequest req) {
        OrbitalElements el = TleParser.parse(
                req.name != null ? req.name : "TLE-Sat",
                req.line1, req.line2);
        Instant time = req.time != null ? Instant.parse(req.time) : Instant.now();
        SatellitePosition pos = propagator.propagate(el, time);
        return Map.of(
                "satelliteId", el.satelliteId(),
                "name", el.name(),
                "propagationMode", el.hasDragTerms() ? "SGP4" : "Kepler",
                "hasDragTerms", el.hasDragTerms(),
                "bstar", el.bstar(),
                "position", Map.of(
                        "latitudeDeg", pos.latitude(),
                        "longitudeDeg", pos.longitude(),
                        "altitudeKm", Math.round(pos.altitude() * 100.0) / 100.0
                ),
                "ecefKm", Map.of(
                        "x", Math.round(pos.ecefX() * 100.0) / 100.0,
                        "y", Math.round(pos.ecefY() * 100.0) / 100.0,
                        "z", Math.round(pos.ecefZ() * 100.0) / 100.0
                ),
                "velocityKms", Map.of(
                        "vx", Math.round(pos.ecefVx() * 1000.0) / 1000.0,
                        "vy", Math.round(pos.ecefVy() * 1000.0) / 1000.0,
                        "vz", Math.round(pos.ecefVz() * 1000.0) / 1000.0
                )
        );
    }

    /**
     * 批量导入 TLE 替换星座卫星列表
     * POST /api/constellation/import-tle
     * Body: { tles: [{ name, line1, line2 }, ...] }
     */
    @PostMapping("/constellation/import-tle")
    public Map<String, Object> importTleConstellation(@RequestBody TleImportRequest req) {
        List<OrbitalElements> elements = new ArrayList<>();
        for (TlePropagateRequest tle : req.tles) {
            elements.add(TleParser.parse(
                    tle.name != null ? tle.name : "TLE-Sat",
                    tle.line1, tle.line2));
        }
        constellation.replaceSatellites(elements);
        return getConstellationOverview();
    }

    public record TlePropagateRequest(
            String name,
            String line1,
            String line2,
            String time
    ) {}

    public record TleImportRequest(
            List<TlePropagateRequest> tles
    ) {}
}
