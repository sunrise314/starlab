package com.starlab.orbit;

import com.starlab.config.SimConstants;

/**
 * 开普勒轨道根数（六根 + 标识信息）
 * <p>
 * 对应 TLE 中的经典轨道参数：
 * - 倾角 i (inclination)
 * - 升交点赤经 RAAN Ω
 * - 偏心率 e (eccentricity)
 * - 近地点幅角 ω (argument of perigee)
 * - 平近点角 M (mean anomaly) —— 历元时刻
 * - 平均运动 n (mean motion) —— 决定半长轴 a
 */
public record OrbitalElements(
        String satelliteId,
        String name,
        String catalogNumber,
        String classification,
        double inclination,       // 倾角 (度)
        double raan,               // 升交点赤经 (度)
        double eccentricity,       // 偏心率 (无量纲)
        double argumentOfPerigee,  // 近地点幅角 (度)
        double meanAnomaly,        // 历元平近点角 (度)
        double meanMotion,         // 平均运动 (圈/天)
        double epochSeconds,       // 历元 (Unix秒)
        // ── SGP4 阻力项（来自 TLE line 1）──
        double bstar,              // BSTAR 阻力系数 (1/ER)
        double ndot,               // 平均运动一阶导数 (圈/天²)
        double nddot               // 平均运动二阶导数 (圈/天³)
) {
    /**
     * 经典六根数构造器（向后兼容，阻力项为 0）
     */
    public OrbitalElements(String satelliteId, String name, String catalogNumber, String classification,
                           double inclination, double raan, double eccentricity,
                           double argumentOfPerigee, double meanAnomaly, double meanMotion, double epochSeconds) {
        this(satelliteId, name, catalogNumber, classification, inclination, raan, eccentricity,
                argumentOfPerigee, meanAnomaly, meanMotion, epochSeconds,
                0.0, 0.0, 0.0);
    }

    /**
     * 是否包含 SGP4 阻力项（BSTAR 非零且 TLE 格式）
     */
    public boolean hasDragTerms() {
        return bstar != 0.0;
    }
    /**
     * 计算半长轴 (km)
     * n = sqrt(μ/a³) → a = (μ/n²)^(1/3)
     */
    public double semiMajorAxis() {
        double n = meanMotion * 2 * Math.PI / SimConstants.SECONDS_PER_DAY; // rad/s
        return Math.cbrt(SimConstants.MU / (n * n));
    }

    /**
     * 轨道周期 (秒)
     */
    public double period() {
        return SimConstants.SECONDS_PER_DAY / meanMotion;
    }

    /**
     * 轨道高度 (km) —— 近圆轨道近似
     */
    public double altitude() {
        return semiMajorAxis() - SimConstants.EARTH_RADIUS;
    }
}
