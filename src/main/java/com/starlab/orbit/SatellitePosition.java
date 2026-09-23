package com.starlab.orbit;

import java.time.Instant;

/**
 * 卫星在某时刻的完整状态向量
 */
public record SatellitePosition(
        String satelliteId,
        String name,
        Instant timestamp,
        // ECI 坐标 (Earth-Centered Inertial)
        double eciX, double eciY, double eciZ,   // 位置 (km)
        double eciVx, double eciVy, double eciVz,  // 速度 (km/s)
        // ECEF 坐标 (Earth-Centered Earth-Fixed)
        double ecefX, double ecefY, double ecefZ,  // 位置 (km)
        double ecefVx, double ecefVy, double ecefVz,  // 速度 (km/s)
        // 地理坐标
        double latitude,   // 纬度 (度)
        double longitude,  // 经度 (度)
        double altitude,    // 高度 (km)
        // 地面速度
        double groundSpeed  // 地面轨迹速度 (km/s)
) {}
