package com.starlab.config;

/**
 * 仿真物理常量
 * 单位: 距离 km, 时间 s, 角度 rad, 频率 Hz
 */
public final class SimConstants {

    private SimConstants() {}

    /** 地球引力常数 (km^3/s^2) */
    public static final double MU = 398600.4418;

    /** 地球自转角速度 (rad/s) */
    public static final double OMEGA_EARTH = 7.2921159e-5;

    /** 地球平均半径 (km) */
    public static final double EARTH_RADIUS = 6371.0;

    /** 光速 (km/s) */
    public static final double SPEED_OF_LIGHT = 299792.458;

    /** 默认载波频率 - L频段 2GHz (Hz) */
    public static final double DEFAULT_CARRIER_FREQ = 2.0e9;

    /** 最小可见仰角 (弧度) */
    public static final double MIN_ELEVATION_RAD = Math.toRadians(10.0);

    /** J2000 历元儒略日 (2000-01-01 12:00:00 UTC) */
    public static final double J2000_JD = 2451545.0;

    /** 1970-01-01 00:00:00 UTC 对应的儒略日 */
    public static final double UNIX_EPOCH_JD = 2440587.5;

    /** 一天的秒数 */
    public static final double SECONDS_PER_DAY = 86400.0;
}
