package com.starlab.orbit;

import com.starlab.config.SimConstants;

/**
 * 坐标转换工具
 * ECI(惯性系) ↔ ECEF(地固系) ↔ LLA(经纬高)
 */
public final class GeoConverter {

    private GeoConverter() {}

    /**
     * 计算格林尼治平恒星时 (GMST)
     * @param time UTC 时间
     * @return GMST 弧度 [0, 2π)
     */
    public static double computeGMST(java.time.Instant time) {
        // 儒略日
        double jd = time.getEpochSecond() / SimConstants.SECONDS_PER_DAY + SimConstants.UNIX_EPOCH_JD;
        // J2000 至今的天数
        double d = jd - SimConstants.J2000_JD;
        // GMST (度)
        double gmstDeg = 280.46061837 + 360.98564736629 * d;
        // 归一化到 [0, 360)
        gmstDeg = gmstDeg % 360;
        if (gmstDeg < 0) gmstDeg += 360;
        return Math.toRadians(gmstDeg);
    }

    /**
     * ECI → ECEF（位置 + 速度）
     * @param eciPos [x, y, z] km
     * @param eciVel [vx, vy, vz] km/s
     * @param gmst GMST 弧度
     * @return {pos=[x,y,z], vel=[vx,vy,vz]} ECEF
     */
    public static double[][] eciToEcef(double[] eciPos, double[] eciVel, double gmst) {
        double cosG = Math.cos(gmst);
        double sinG = Math.sin(gmst);

        // 位置: r_ECEF = R_z(-gmst) * r_ECI
        double xE = eciPos[0] * cosG + eciPos[1] * sinG;
        double yE = -eciPos[0] * sinG + eciPos[1] * cosG;
        double zE = eciPos[2];

        // 速度: 先旋转，再减去地球自转分量 ω×r
        double vxRot = eciVel[0] * cosG + eciVel[1] * sinG;
        double vyRot = -eciVel[0] * sinG + eciVel[1] * cosG;
        double vzRot = eciVel[2];

        // ω×r = (-ω*y, ω*x, 0)
        double vxE = vxRot + SimConstants.OMEGA_EARTH * yE;
        double vyE = vyRot - SimConstants.OMEGA_EARTH * xE;
        double vzE = vzRot;

        return new double[][]{{xE, yE, zE}, {vxE, vyE, vzE}};
    }

    /**
     * ECEF → 地理坐标（球面近似）
     * @return [latitude(deg), longitude(deg), altitude(km)]
     */
    public static double[] ecefToLla(double x, double y, double z) {
        double lon = Math.atan2(y, x);
        double r = Math.sqrt(x * x + y * y);
        double lat = Math.atan2(z, r);
        double alt = Math.sqrt(x * x + y * y + z * z) - SimConstants.EARTH_RADIUS;
        return new double[]{Math.toDegrees(lat), Math.toDegrees(lon), alt};
    }

    /**
     * 地理坐标 → ECEF
     */
    public static double[] llaToEcef(double latDeg, double lonDeg, double altKm) {
        double lat = Math.toRadians(latDeg);
        double lon = Math.toRadians(lonDeg);
        double r = SimConstants.EARTH_RADIUS + altKm;
        return new double[]{
                r * Math.cos(lat) * Math.cos(lon),
                r * Math.cos(lat) * Math.sin(lon),
                r * Math.sin(lat)
        };
    }

    /**
     * 地面速度（地面轨迹速度）
     * v_ground = sqrt(vx² + vy²) in ECEF
     */
    public static double groundSpeed(double[] ecefVel) {
        return Math.sqrt(ecefVel[0] * ecefVel[0] + ecefVel[1] * ecefVel[1]);
    }
}
