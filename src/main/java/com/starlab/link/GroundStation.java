package com.starlab.link;

/**
 * 地面站（信关站）模型
 */
public record GroundStation(
        String id,
        String name,
        double latitude,   // 纬度 (度)
        double longitude,  // 经度 (度)
        double altitude     // 海拔 (km)
) {}
