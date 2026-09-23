package com.starlab.handover;

import com.starlab.orbit.OrbitalElements;

import java.time.Instant;

/**
 * STCN 切换决策策略接口
 * <p>
 * v1: {@link GreedyHandoverStrategy}      贪心最高仰角选站
 * v2: {@link PredictiveHandoverStrategy}  预测式选站（预判未来可见窗口）
 */
public interface HandoverStrategy {

    /** 算法标识，用于日志和 API 返回 */
    String algorithmId();

    /** 算法中文名，用于前端显示 */
    String algorithmName();

    /**
     * 为某颗卫星在某时刻选择最优地面站
     *
     * @param sat             卫星轨道根数
     * @param time            仿真时刻
     * @param currentStationId 当前服务站 ID（null = 首次选站）
     * @return 选站结果
     */
    StationSelection selectBestStation(OrbitalElements sat, Instant time, String currentStationId);
}
