package com.starlab.handover;

import java.time.Instant;

/**
 * 星地智连 (STCN) 切换事件
 * <p>
 * 一次切换 = 卫星与旧地面站链路断裂 + 新地面站链路建立
 * <p>
 * 时延模型（毫秒）：
 *   处理时延 (processingMs)  —— 固定 5ms（信关站选站决策 + 资源分配）
 *   传播时延 (propagationMs)  —— 目标站距离 / c × 2（请求+确认双程）
 *   总时延  = processingMs + propagationMs
 * <p>
 * 触发类型：
 *   ELEVATION_FALL —— 旧链仰角穿越最小阈值（10°）向下
 *   LINK_BREAK     —— 旧链已不可见（仰角 < 0°），强制切换
 *   PREDICTIVE      —— 预判型：旧链仰角仍够但未来 Ns 内将穿越，提前切换
 */
public record HandoverEvent(
        String satelliteId,
        String satelliteName,
        String fromStationId,
        String fromStationName,
        double fromElevationDeg,    // 切换瞬间旧链仰角
        String toStationId,
        String toStationName,
        double toElevationDeg,      // 切换瞬间新链仰角
        Instant triggerTime,        // 仿真时钟触发时刻
        TriggerType triggerType,
        double propagationMs,       // 传播时延 (请求+确认双程)
        double processingMs,        // 处理时延
        double totalLatencyMs       // 总切换时延
) {
    public enum TriggerType {
        ELEVATION_FALL,
        LINK_BREAK,
        PREDICTIVE
    }
}
