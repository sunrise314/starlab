package com.starlab.handover;

import com.starlab.link.GroundStation;
import com.starlab.link.LinkResult;

/**
 * 选站结果 DTO —— 所有 HandoverStrategy 实现共享
 */
public record StationSelection(
        GroundStation station,
        LinkResult link,
        boolean handoverNeeded,
        double propagationMs,
        double totalLatencyMs,
        Reason reason
) {
    public enum Reason {
        NONE,               // 继续用当前站
        INITIAL,            // 首次选站（无当前站）
        ELEVATION_SWITCH,   // 贪心仰角切换（v1）
        PREDICTIVE_SWITCH,  // 预测式切换（v2，预判未来可见窗口）
        INTERFERENCE_SWITCH,// 干扰感知切换（v3，当前 C/I 恶化或新站 C/I 显著更好）
        LINK_BREAK,         // 当前站已不可见，强制切换/断开
        NO_VISIBLE          // 完全无可见站
    }
}
