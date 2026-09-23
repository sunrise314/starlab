package com.starlab.orbit;

import com.starlab.config.SimConstants;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 轨道传播器 —— 策略委派
 * <p>
 * 有 BSTAR 阻力项 → 走 SGP4（TLE 导入场景，带 J2 摄动 + 大气阻力）
 * 否则 → 走二体/开普勒（Walker 星座演示场景）
 */
@Component
public class OrbitPropagator {

    private static final int MAX_ITER = 30;
    private static final double TOLERANCE = 1e-10;

    private final Sgp4Propagator sgp4;

    public OrbitPropagator(Sgp4Propagator sgp4) {
        this.sgp4 = sgp4;
    }

    /**
     * 传播卫星到指定时刻（策略委派）
     * SGP4 模式: 有 BSTAR 阻力项 → 走 SGP4
     * Kepler 模式: 纯经典根数 → 走二体解析解
     */
    public SatellitePosition propagate(OrbitalElements el, Instant time) {
        if (el.hasDragTerms()) {
            return sgp4.propagate(el, time);
        }
        return propagateKepler(el, time);
    }

    /**
     * 二体/开普勒解析解传播
     */
    private SatellitePosition propagateKepler(OrbitalElements el, Instant time) {
        // 1. 时间增量 (秒)
        double dt = time.getEpochSecond() - el.epochSeconds();

        // 2. 平均运动 (rad/s)
        double n = el.meanMotion() * 2 * Math.PI / SimConstants.SECONDS_PER_DAY;

        // 3. 半长轴 (km)
        double a = Math.cbrt(SimConstants.MU / (n * n));

        // 4. 历元平近点角 → 当前平近点角
        double M = Math.toRadians(el.meanAnomaly()) + n * dt;
        M = normalizeAngle(M);

        // 5. 解开普勒方程: M = E - e·sin(E)
        double e = el.eccentricity();
        double E = solveKepler(M, e);

        // 6. 真近点角 ν
        double nu = 2 * Math.atan2(
                Math.sqrt(1 + e) * Math.sin(E / 2),
                Math.sqrt(1 - e) * Math.cos(E / 2)
        );

        // 7. 轨道半径
        double r = a * (1 - e * Math.cos(E));

        // 8. 轨道面 (perifocal) 坐标
        double px = r * Math.cos(nu);
        double py = r * Math.sin(nu);

        // 速度 (vis-viva 推导)
        double p = a * (1 - e * e);         // 半通径
        double h = Math.sqrt(SimConstants.MU * p);  // 比角动量
        double vpx = -SimConstants.MU / h * Math.sin(nu);
        double vpy = SimConstants.MU / h * (e + Math.cos(nu));

        // 9. 旋转 perifocal → ECI: R_z(Ω)·R_x(i)·R_z(ω)
        double omega = Math.toRadians(el.argumentOfPerigee());
        double inc = Math.toRadians(el.inclination());
        double raan = Math.toRadians(el.raan());

        double[] eciPos = rotateToECI(px, py, omega, inc, raan);
        double[] eciVel = rotateToECI(vpx, vpy, omega, inc, raan);

        // 10. ECI → ECEF
        double gmst = GeoConverter.computeGMST(time);
        double[][] ecef = GeoConverter.eciToEcef(eciPos, eciVel, gmst);
        double[] ecefPos = ecef[0];
        double[] ecefVel = ecef[1];

        // 11. ECEF → LLA
        double[] lla = GeoConverter.ecefToLla(ecefPos[0], ecefPos[1], ecefPos[2]);

        // 12. 地面速度
        double gs = GeoConverter.groundSpeed(ecefVel);

        return new SatellitePosition(
                el.satelliteId(), el.name(), time,
                eciPos[0], eciPos[1], eciPos[2],
                eciVel[0], eciVel[1], eciVel[2],
                ecefPos[0], ecefPos[1], ecefPos[2],
                ecefVel[0], ecefVel[1], ecefVel[2],
                lla[0], lla[1], lla[2],
                gs
        );
    }

    /**
     * 求解开普勒方程: M = E - e·sin(E)
     * Newton-Raphson 迭代
     */
    private double solveKepler(double M, double e) {
        double E = M; // 初始猜测: E₀ = M (对小偏心率有效)
        for (int i = 0; i < MAX_ITER; i++) {
            double f = E - e * Math.sin(E) - M;
            double fp = 1 - e * Math.cos(E);
            double dE = f / fp;
            E -= dE;
            if (Math.abs(dE) < TOLERANCE) break;
        }
        return E;
    }

    /**
     * perifocal → ECI 旋转: R_z(Ω)·R_x(i)·R_z(ω)
     * 输入: 轨道面内坐标 (px, py, 0)
     */
    private double[] rotateToECI(double px, double py,
                                  double omega, double inc, double raan) {
        // Step 1: R_z(ω)
        double x1 = Math.cos(omega) * px - Math.sin(omega) * py;
        double y1 = Math.sin(omega) * px + Math.cos(omega) * py;

        // Step 2: R_x(i) (z=0 → 简化)
        double x2 = x1;
        double y2 = Math.cos(inc) * y1;
        double z2 = Math.sin(inc) * y1;

        // Step 3: R_z(Ω)
        double x = Math.cos(raan) * x2 - Math.sin(raan) * y2;
        double y = Math.sin(raan) * x2 + Math.cos(raan) * y2;
        double z = z2;

        return new double[]{x, y, z};
    }

    private double normalizeAngle(double rad) {
        return ((rad % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI);
    }
}
