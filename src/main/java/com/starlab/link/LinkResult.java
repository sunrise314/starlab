package com.starlab.link;

/**
 * 星地链路计算结果
 * <p>
 * v2 增强（更真实链路模型）：
 *   - rainAttenuationDb: 雨衰 (ITU-R P.618 简化)
 *   - atmosphericLossDb: 大气气体吸收损耗
 *   - snrDb: 信噪比 (Shannon 容量前提)
 *   - throughputMbps: Shannon 吞吐量 C = B·log2(1+SNR)
 * <p>
 * v3 干扰模型增强：
 *   - interferencePowerDbm: 同频+邻信道+宽带噪声合计的干扰功率 (dBm)
 *   - coChannelCount:      对本地面站造成同频干扰的可见卫星数
 *   - cirDb:                载干比 C/I (dB)，决定能否解调
 *   - linkAvailable:        链路可用性（C/I > 阈值 且 SNR > 阈值）
 */
public record LinkResult(
        String satelliteId,
        String satelliteName,
        String stationId,
        String stationName,
        double rangeKm,       // 星地距离 (km)
        double elevationDeg,  // 仰角 (度)
        double azimuthDeg,    // 方位角 (度，正北为0，顺时针)
        double dopplerShiftHz, // 多普勒频移 (Hz)
        double pathLossDb,     // 自由空间路径损耗 (dB)
        boolean visible,        // 是否可见 (仰角 > 阈值)
        // 物理层增强字段
        double rainAttenuationDb,    // 雨衰 (dB)
        double atmosphericLossDb,     // 大气损耗 (dB)
        double snrDb,                 // 信噪比 (dB)
        double throughputMbps,        // Shannon 吞吐量 (Mbps)
        // v3 干扰模型字段
        double interferencePowerDbm,  // 干扰功率 (dBm)
        int coChannelCount,            // 同频干扰卫星数
        double cirDb,                  // 载干比 C/I (dB)
        boolean linkAvailable          // 链路可用性 (C/I > 阈值 且 SNR > 阈值)
) {
    /**
     * 用干扰分析结果覆盖一条已计算的 LinkResult 的 4 个干扰字段。
     * 用于 InterferenceCalculator 复用 LinkCalculator 的物理层结果。
     */
    public static LinkResult withInterference(LinkResult base,
                                               double interferencePowerDbm,
                                               int coChannelCount,
                                               double cirDb,
                                               boolean linkAvailable) {
        return new LinkResult(
                base.satelliteId(), base.satelliteName(),
                base.stationId(), base.stationName(),
                base.rangeKm(), base.elevationDeg(), base.azimuthDeg(),
                base.dopplerShiftHz(), base.pathLossDb(), base.visible(),
                base.rainAttenuationDb(), base.atmosphericLossDb(),
                base.snrDb(), base.throughputMbps(),
                interferencePowerDbm, coChannelCount, cirDb, linkAvailable
        );
    }
}
