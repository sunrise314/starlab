package com.starlab.constellation;

import com.starlab.config.SimConstants;
import com.starlab.link.GroundStation;
import com.starlab.orbit.OrbitalElements;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 星座工厂
 * <p>
 * 默认构型：Walker Delta 12/3/1，倾角 53°，高度 550km
 *   - Walker 参数: T=12, P=3, S=4, F=1
 *   - 轨道面间距: 360°/P = 120°
 *   - 面内卫星间距: 360°/S = 90°
 *   - 面间相位偏移: F × 360°/T = 30°
 * <p>
 * v2 新增 {@link #createWalker(int, int, double, double, int)} 支持任意 Walker 参数动态生成。
 * <p>
 * 地面站: 北京 / 上海 / 三亚 / 喀什 / 漠河 (覆盖中国版图)
 */
public final class ConstellationFactory {

    // 默认 Walker 参数
    static final int DEFAULT_TOTAL_SATS = 12;
    static final int DEFAULT_PLANES = 3;
    static final int DEFAULT_PHASING_F = 1;

    // 默认轨道参数
    static final double DEFAULT_INCLINATION = 53.0;
    static final double DEFAULT_ALTITUDE_KM = 550.0;
    static final double ECCENTRICITY = 0.0001;
    static final double ARG_PERIGEE = 0.0;
    static final String EPOCH_ISO = "2026-01-01T00:00:00Z";

    private ConstellationFactory() {}

    /** 默认 Walker 12/3/1，53°/550km */
    public static List<OrbitalElements> createWalkerConstellation() {
        return createWalker(DEFAULT_TOTAL_SATS, DEFAULT_PLANES, DEFAULT_INCLINATION, DEFAULT_ALTITUDE_KM, DEFAULT_PHASING_F);
    }

    /**
     * 任意 Walker Delta 星座生成
     *
     * @param total        卫星总数 T
     * @param planes       轨道面数 P (必须整除 T)
     * @param inclination  倾角 (度)
     * @param altitudeKm   轨道高度 (km) —— 自动反算平均运动
     * @param phasingF     Walker F 参数（面间相位偏移系数，0~T-1）
     */
    public static List<OrbitalElements> createWalker(int total, int planes, double inclination, double altitudeKm, int phasingF) {
        if (total <= 0 || planes <= 0 || total % planes != 0) {
            throw new IllegalArgumentException(
                    "Walker 参数非法：T=" + total + " P=" + planes + "（需 T%P=0）");
        }
        if (phasingF < 0 || phasingF >= total) {
            throw new IllegalArgumentException("F 参数必须在 [0, T) 范围内，当前 F=" + phasingF);
        }
        if (altitudeKm < 200 || altitudeKm > 2000) {
            throw new IllegalArgumentException("轨道高度必须在 200~2000 km 范围内（LEO）");
        }

        int satsPerPlane = total / planes;
        // 用 altitude 反算平均运动：n = sqrt(μ/a³)/(2π) * 86400  (圈/天)
        double a = SimConstants.EARTH_RADIUS + altitudeKm;
        double nRadPerSec = Math.sqrt(SimConstants.MU / (a * a * a));
        double meanMotion = nRadPerSec * SimConstants.SECONDS_PER_DAY / (2 * Math.PI);  // 圈/天

        long epochSeconds = Instant.parse(EPOCH_ISO).getEpochSecond();
        List<OrbitalElements> sats = new ArrayList<>(total);

        double planeSpacing = 360.0 / planes;
        double satSpacing = 360.0 / satsPerPlane;
        double phaseOffset = (double) phasingF * 360.0 / total;

        int satNum = 1;
        for (int p = 0; p < planes; p++) {
            double raan = p * planeSpacing;
            double planePhase = p * phaseOffset;

            for (int s = 0; s < satsPerPlane; s++) {
                double meanAnomaly = (s * satSpacing + planePhase) % 360;
                String id = String.format("STARLAB-%02d", satNum);
                String name = String.format("Starlab-%d (P%d-S%d)", satNum, p + 1, s + 1);

                sats.add(new OrbitalElements(
                        id, name, id, "U",
                        inclination, raan, ECCENTRICITY,
                        ARG_PERIGEE, meanAnomaly, meanMotion,
                        epochSeconds
                ));
                satNum++;
            }
        }
        return sats;
    }

    public static List<GroundStation> createGroundStations() {
        return List.of(
                new GroundStation("BJO", "北京信关站", 39.9042, 116.4074, 0.05),
                new GroundStation("SHO", "上海信关站", 31.2304, 121.4737, 0.004),
                new GroundStation("SYO", "三亚信关站", 18.2528, 109.5120, 0.005),
                new GroundStation("KSO", "喀什信关站", 39.4700, 75.9900, 1.300),
                new GroundStation("MHO", "漠河信关站", 53.4300, 122.5300, 0.433)
        );
    }
}
