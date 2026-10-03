package com.starlab.link;

import com.starlab.config.SimConstants;
import com.starlab.orbit.GeoConverter;
import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Component;

/**
 * 星地链路计算器
 * <p>
 * v2 增强：物理层模型更真实
 *   - 自由空间路径损耗 (FSPL) — 原有
 *   - 雨衰 (ITU-R P.618 简化)：γ_R = k·R^α × slant_path
 *   - 大气气体吸收损耗：与仰角反比（仰角越低，大气路径越长）
 *   - 信噪比 SNR：链路预算 (Tx + Gains − Losses − Noise)
 *   - Shannon 吞吐量：C = B·log2(1 + SNR_linear)
 *   - 大气折射仰角修正（ITU-R P.834 简化）：低仰角时电波被大气"抬"起来，
 *     视在仰角 = 几何仰角 + Δe（10° 时 +0.10°，5° 时 +0.21°）
 *     → LinkResult.elevationDeg() 返回视在仰角，可见性判定随之使用视在仰角
 * <p>
 * ENU 变换: 将 ECEF 下卫星-地面站差矢量分解到地面站的
 * East-North-Up 坐标系，得到仰角和方位角
 */
@Component
public class LinkCalculator {

    // ── 链路预算参数（典型 LEO 链路预算值） ──
    /** 发射功率 (dBm) — 卫星下行 2W */
    private static final double TX_POWER_DBM = 33.0;
    /** 卫星发射天线增益 (dBi) — 高增益相控阵 */
    private static final double TX_GAIN_DBI = 30.0;
    /** 地面站接收天线增益 (dBi) */
    private static final double RX_GAIN_DBI = 30.0;
    /** 信道带宽 (Hz) — 20 MHz */
    private static final double BANDWIDTH_HZ = 20e6;
    /** 接收机噪声系数 (dB) */
    private static final double NOISE_FIGURE_DB = 3.0;
    /** 热噪声功率谱密度 (dBm/Hz) — T0=290K */
    private static final double THERMAL_NOISE_DENSITY_DBM_HZ = -174.0;

    // ── 雨衰模型参数 (ITU-R P.618 简化) ──
    /** 降雨率 (mm/h) — 中等降雨 */
    private static final double RAIN_RATE_MMH = 10.0;
    /** 雨顶高度 (km) — 温带平均 */
    private static final double RAIN_HEIGHT_KM = 5.0;
    /** 频率相关系数 k（L 频段 ~1e-4，按频率平方近似外推） */
    private static final double RAIN_K_BASE = 0.0001;
    /** 频率相关系数 α (L 频段 ~1.0) */
    private static final double RAIN_ALPHA = 1.0;

    /**
     * 计算卫星与地面站之间的链路状态（含物理层增强）
     */
    public LinkResult calculate(SatellitePosition sat, GroundStation station) {
        // 1. 地面站 ECEF 坐标
        double[] stationEcef = GeoConverter.llaToEcef(
                station.latitude(), station.longitude(), station.altitude());

        // 2. 星地差矢量 (ECEF)
        double dx = sat.ecefX() - stationEcef[0];
        double dy = sat.ecefY() - stationEcef[1];
        double dz = sat.ecefZ() - stationEcef[2];
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 3. ENU 变换 (地面站处的 East-North-Up 基矢量)
        double lat = Math.toRadians(station.latitude());
        double lon = Math.toRadians(station.longitude());

        double east = -Math.sin(lon) * dx + Math.cos(lon) * dy;
        double north = -Math.sin(lat) * Math.cos(lon) * dx
                      - Math.sin(lat) * Math.sin(lon) * dy
                      + Math.cos(lat) * dz;
        double up = Math.cos(lat) * Math.cos(lon) * dx
                   + Math.cos(lat) * Math.sin(lon) * dy
                   + Math.sin(lat) * dz;

        // 4. 仰角 = arcsin(U / |Δr|)
        double elevation = Math.toDegrees(Math.asin(up / range));

        // 4.5 大气折射修正 —— 视在仰角 = 几何仰角 + Δe(e)
        //     电波在低仰角穿过大气层时被折射"抬高"，天线实际指向比几何位置高：
        //     10° → +0.10°，5° → +0.21°，1° → ~+0.65°（经验限幅）
        double apparentElevation = elevation + refractionCorrectionDeg(elevation);

        // 5. 方位角 = atan2(E, N), 正北 0°, 顺时针
        double azimuth = Math.toDegrees(Math.atan2(east, north));
        if (azimuth < 0) azimuth += 360;

        // 6. 多普勒频移: Δf = -f * v_LOS / c
        double[] losVec = {dx / range, dy / range, dz / range};
        double vLOS = sat.ecefVx() * losVec[0]
                     + sat.ecefVy() * losVec[1]
                     + sat.ecefVz() * losVec[2];
        double doppler = -SimConstants.DEFAULT_CARRIER_FREQ * vLOS / SimConstants.SPEED_OF_LIGHT;

        // 7. 自由空间路径损耗: L = 20·log10(4π·d·f / c)
        double pathLoss = 20 * Math.log10(
                4 * Math.PI * range * SimConstants.DEFAULT_CARRIER_FREQ / SimConstants.SPEED_OF_LIGHT);

        // 8. 可见性判定（用视在仰角——门限是天线看的方向，不是几何方向）
        boolean visible = apparentElevation >= Math.toDegrees(SimConstants.MIN_ELEVATION_RAD);

        // ── v2 物理层增强 ──
        // 9. 雨衰 (ITU-R P.618 简化)
        double rainLossDb = calculateRainLoss(apparentElevation);

        // 10. 大气气体吸收损耗
        double atmosphericLossDb = calculateAtmosphericLoss(apparentElevation);

        // 11. SNR + Shannon 吞吐量
        double totalLossDb = pathLoss + rainLossDb + atmosphericLossDb;
        double snrDb = calculateSnrDb(totalLossDb);
        double throughputMbps = calculateThroughputMbps(snrDb);

        return new LinkResult(
                sat.satelliteId(), sat.name(),
                station.id(), station.name(),
                range, apparentElevation, azimuth,
                doppler, pathLoss, visible,
                rainLossDb, atmosphericLossDb, snrDb, throughputMbps,
                // v3 干扰默认值（未做干扰分析时，按"无干扰"填）
                // InterferenceCalculator 会用 LinkResult.withInterference 覆盖
                -99.0, 0, 99.0, visible
        );
    }

    /**
     * 给定一条已计算好的链路，反推接收端信号功率 (dBm)
     * C = P_tx + G_tx + G_rx − L_path − L_rain − L_atm
     * <p>
     * 供 InterferenceCalculator 计算载干比 C/I 使用，避免常量重复定义。
     */
    public double receivedPowerDbm(LinkResult link) {
        return TX_POWER_DBM + TX_GAIN_DBI + RX_GAIN_DBI
                - link.pathLossDb()
                - link.rainAttenuationDb()
                - link.atmosphericLossDb();
    }

    /**
     * 系统带宽内的热噪声底 (dBm)
     * N = -174 + 10·log10(B) + NF
     * <p>
     * 供 InterferenceCalculator 累加宽带噪声使用。
     */
    public double noiseFloorDbm() {
        return THERMAL_NOISE_DENSITY_DBM_HZ
                + 10 * Math.log10(BANDWIDTH_HZ)
                + NOISE_FIGURE_DB;
    }

    /**
     * 大气折射仰角修正（ITU-R P.834 简化）
     * <p>
     * Δe(rad) ≈ Ns·10⁻⁶·cot(e)，Ns = 315（标准大气地面折射率）
     * 数值：10° → +0.10°，5° → +0.21°，2° → +0.52°；
     * cot 线性近似在 1° 以下发散（经验值约 +0.65°），故限幅。
     * <p>
     * 工程含义：10° 门限下折射白送的 ~0.1°，约等于过境尾段多出 20~30 秒可见时间。
     * 是否建模取决于你要"教科书几何"还是"天线实际指向"——本仿真选后者。
     */
    private double refractionCorrectionDeg(double geometricElevDeg) {
        // 地平线以下模型失效不修正；>= 20° 修正量 < 0.05°，工程上忽略
        if (geometricElevDeg <= 0 || geometricElevDeg >= 20.0) {
            return 0.0;
        }
        double e = Math.max(geometricElevDeg, 1.0);
        double deltaDeg = Math.toDegrees(315e-6 / Math.tan(Math.toRadians(e)));
        return Math.round(Math.min(deltaDeg, 0.65) * 1000.0) / 1000.0;
    }

    /**
     * 雨衰 (ITU-R P.618 简化版)
     * <p>
     * γ_R = k·R^α (dB/km)  — 比衰减系数
     * d_slant = (h_R - h_station) / sin(elevation)  — 斜路径长度
     * A_rain = γ_R × d_slant
     * <p>
     * 注意：L 频段（2 GHz）雨衰极小（< 1 dB），Ka 频段才显著
     */
    private double calculateRainLoss(double elevationDeg) {
        double elevationRad = Math.toRadians(elevationDeg);
        double sinEl = Math.max(Math.sin(elevationRad), 0.01);  // 防 0
        // 斜路径长度 (km)：雨顶到地面站的几何路径
        double slantPathKm = RAIN_HEIGHT_KM / sinEl;
        // 比衰减系数：k 随频率平方近似外推（baseline=2GHz）
        double freqGHz = SimConstants.DEFAULT_CARRIER_FREQ / 1e9;
        double k = RAIN_K_BASE * Math.pow(freqGHz / 2.0, 2);
        double gammaR = k * Math.pow(RAIN_RATE_MMH, RAIN_ALPHA);  // dB/km
        return Math.round(gammaR * slantPathKm * 1000.0) / 1000.0;
    }

    /**
     * 大气气体吸收损耗 (简化)
     * <p>
     * L 频段总大气损耗 ~0.3 / sin(elevation)（仰角越低，大气路径越长）
     * 包含 O2 + H2O 吸收
     */
    private double calculateAtmosphericLoss(double elevationDeg) {
        double sinEl = Math.max(Math.sin(Math.toRadians(elevationDeg)), 0.01);
        return Math.round(0.3 / sinEl * 1000.0) / 1000.0;
    }

    /**
     * 链路预算 — 信噪比 (dB)
     * <p>
     * SNR = P_tx + G_tx + G_rx − L_total − N
     * N = -174 + 10·log10(B) + NF (dBm)
     */
    private double calculateSnrDb(double totalLossDb) {
        double noiseDbm = THERMAL_NOISE_DENSITY_DBM_HZ
                + 10 * Math.log10(BANDWIDTH_HZ)
                + NOISE_FIGURE_DB;
        return Math.round((TX_POWER_DBM + TX_GAIN_DBI + RX_GAIN_DBI
                - totalLossDb - noiseDbm) * 100.0) / 100.0;
    }

    /**
     * Shannon 容量 — C = B·log2(1 + SNR_linear)
     * @return 吞吐量 (Mbps)
     */
    private double calculateThroughputMbps(double snrDb) {
        if (snrDb <= 0) return 0;
        double snrLinear = Math.pow(10, snrDb / 10.0);
        double bps = BANDWIDTH_HZ * (Math.log(1 + snrLinear) / Math.log(2));
        return Math.round(bps / 1e6 * 100.0) / 100.0;
    }
}
