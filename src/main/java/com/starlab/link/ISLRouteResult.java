package com.starlab.link;

import java.util.List;

/**
 * 星间多跳路由结果
 * <p>
 * 源节点 → 卫星 → 卫星 → ... → 目的节点 的最短时延路径
 * <p>
 * - path[0]        = 源节点（卫星 ID 或地面站 ID）
 * - path[last]     = 目的节点（卫星 ID 或地面站 ID）
 * - path[1..n-1]   = 中间卫星 ID
 * - hopCount       = path.size - 1（链路跳数）
 * - totalLatencyMs = 各跳时延之和（含星地接入段 + 星间传播段）
 * - reachable      = 是否找到至少一条端到端路径
 */
public record ISLRouteResult(
        String source,
        String destination,
        List<String> path,
        int hopCount,
        double totalLatencyMs,
        boolean reachable,
        String reason           // 不可达时的原因（"no visible path", "source not found", ...）
) {}
