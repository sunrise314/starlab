package com.starlab.constellation;

import com.starlab.link.GroundStation;
import com.starlab.orbit.OrbitalElements;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 星座管理器
 * <p>
 * 管理一组卫星的轨道根数和地面站列表，按需传播计算位置。
 * <p>
 * 默认构型：Walker Delta 12/3/1，53° 倾角，550km 高度
 * <p>
 * v2 新增 {@link #reconfigure(int, int, double, double, int)} 动态重新生成星座参数。
 * 注意：reconfigure 会改变所有正在用的卫星列表，进行中的仿真会被打断。
 */
@Component
public class Constellation {

    private volatile List<OrbitalElements> satellites;
    private volatile List<GroundStation> groundStations;
    private volatile Map<String, OrbitalElements> satelliteById;
    private volatile Map<String, GroundStation> stationById;

    // 当前 Walker 参数（用于 OrbitController 概览）
    private volatile int currentTotal;
    private volatile int currentPlanes;
    private volatile int currentF;
    private volatile double currentInclination;
    private volatile double currentAltitudeKm;

    public Constellation() {
        this.currentTotal = ConstellationFactory.DEFAULT_TOTAL_SATS;
        this.currentPlanes = ConstellationFactory.DEFAULT_PLANES;
        this.currentF = ConstellationFactory.DEFAULT_PHASING_F;
        this.currentInclination = ConstellationFactory.DEFAULT_INCLINATION;
        this.currentAltitudeKm = ConstellationFactory.DEFAULT_ALTITUDE_KM;
        this.satellites = ConstellationFactory.createWalkerConstellation();
        this.groundStations = ConstellationFactory.createGroundStations();
        this.satelliteById = indexSatellites(satellites);
        this.stationById = groundStations.stream()
                .collect(Collectors.toMap(GroundStation::id, Function.identity()));
    }

    /**
     * 重新生成星座（动态 Walker 参数）
     * <p>
     * 调用后所有正在跑的仿真会受影响（卫星列表全变），属于预期行为。
     */
    public synchronized void reconfigure(int total, int planes, double inclination, double altitudeKm, int phasingF) {
        List<OrbitalElements> newSats = ConstellationFactory.createWalker(total, planes, inclination, altitudeKm, phasingF);
        this.satellites = Collections.unmodifiableList(newSats);
        this.satelliteById = indexSatellites(newSats);
        this.currentTotal = total;
        this.currentPlanes = planes;
        this.currentF = phasingF;
        this.currentInclination = inclination;
        this.currentAltitudeKm = altitudeKm;
    }

    /** 重置为默认 Walker 12/3/1，53°/550km */
    public synchronized void resetToDefault() {
        reconfigure(ConstellationFactory.DEFAULT_TOTAL_SATS,
                ConstellationFactory.DEFAULT_PLANES,
                ConstellationFactory.DEFAULT_INCLINATION,
                ConstellationFactory.DEFAULT_ALTITUDE_KM,
                ConstellationFactory.DEFAULT_PHASING_F);
    }

    /**
     * 批量替换卫星列表（TLE 导入场景）
     * 注：TLE 导入时 Walker 参数失去意义，概览接口会标记为 "TLE" 模式。
     */
    public synchronized void replaceSatellites(List<OrbitalElements> newSats) {
        this.satellites = Collections.unmodifiableList(newSats);
        this.satelliteById = indexSatellites(newSats);
        // 标记为 TLE 模式
        this.currentTotal = newSats.size();
        this.currentPlanes = 0;
        this.currentF = 0;
        this.currentInclination = 0;
        this.currentAltitudeKm = 0;
    }

    private static Map<String, OrbitalElements> indexSatellites(List<OrbitalElements> sats) {
        return sats.stream()
                .collect(Collectors.toMap(OrbitalElements::satelliteId, Function.identity()));
    }

    public List<OrbitalElements> getSatellites() {
        return satellites;
    }

    public List<GroundStation> getGroundStations() {
        return groundStations;
    }

    public OrbitalElements getSatellite(String id) {
        return satelliteById.get(id);
    }

    public GroundStation getGroundStation(String id) {
        return stationById.get(id);
    }

    public int getSatelliteCount() {
        return satellites.size();
    }

    public int getStationCount() {
        return groundStations.size();
    }

    public Instant getEpoch() {
        return Instant.ofEpochSecond((long) satellites.getFirst().epochSeconds());
    }

    // ── Walker 参数访问器（用于 /api/constellation 概览） ──
    public int getCurrentTotal() { return currentTotal; }
    public int getCurrentPlanes() { return currentPlanes; }
    public int getCurrentF() { return currentF; }
    public double getCurrentInclination() { return currentInclination; }
    public double getCurrentAltitudeKm() { return currentAltitudeKm; }
    public int getCurrentSatsPerPlane() {
        return currentPlanes > 0 ? currentTotal / currentPlanes : 0;
    }
}
