package com.starlab.link;

/**
 * 星间链路 (Inter-Satellite Link, ISL)
 * <p>
 * 表示两颗 LEO 卫星之间的激光/微波链路：
 *   - 无大气损耗（真空传播）
 *   - 距离 = ECEF 笛卡尔距离
 *   - 时延 = 距离 / 光速
 *   - 可见性：链路不被地球遮挡（视线检查）
 * <p>
 * 拓扑发现：
 *   - 同轨道面：前后卫星永久 ISL（仰角无遮挡）
 *   - 跨轨道面：仅在两星视线不被地球遮挡时建立
 */
public record InterSatelliteLink(
        String sat1Id,
        String sat2Id,
        double distanceKm,    // 星间距离 (km)
        double latencyMs,     // 单向传播时延 (ms)
        boolean visible,      // 是否可见（不被地球遮挡）
        String linkType        // intra-plane / cross-plane
) {}
