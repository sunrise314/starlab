package com.starlab.orbit;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * TLE (Two-Line Element) 两行轨道根数解析器
 * <p>
 * 格式示例:
 * <pre>
 * ISS (ZARYA)
 * 1 25544U 98067A   24001.50000000  .00016717  00000+0  10270-3 1  9994
 * 2 25544  51.6400  21.7500 0006703  10.0000  20.0000 15.50000000123456
 * </pre>
 */
public final class TleParser {

    private TleParser() {}

    /**
     * 解析三行 TLE（名称行 + 两行根数）
     */
    public static OrbitalElements parse(String nameLine, String line1, String line2) {
        String name = nameLine.trim();
        String catNum = line1.substring(2, 7).trim();
        String classification = line1.substring(7, 8).trim();
        double epochSec = parseEpoch(line1.substring(18, 32).trim());
        double inclination = Double.parseDouble(line2.substring(8, 16).trim());
        double raan = Double.parseDouble(line2.substring(17, 25).trim());
        double eccentricity = Double.parseDouble("0." + line2.substring(26, 33).trim());
        double argPerigee = Double.parseDouble(line2.substring(34, 42).trim());
        double meanAnomaly = Double.parseDouble(line2.substring(43, 51).trim());
        double meanMotion = Double.parseDouble(line2.substring(52, 63).trim());

        // ── TLE line 1: ndot, nddot, BSTAR ──
        // ndot: col 34-43 (带符号, 以空格为正号)
        double ndot = parseSignedDouble(line1.substring(33, 43));
        // nddot: col 45-52 (格式 .NNNNN-N 或 .NNNNN+N)
        double nddot = parseExponential(line1.substring(44, 52));
        // BSTAR: col 54-61 (格式 NNNNN-N 或 .NNNNN+N)
        double bstar = parseExponential(line1.substring(53, 61));

        return new OrbitalElements(
                catNum, name, catNum, classification,
                inclination, raan, eccentricity,
                argPerigee, meanAnomaly, meanMotion, epochSec,
                bstar, ndot, nddot
        );
    }

    /**
     * 解析纯小数带符号字符串（如 "  .00019378" 或 "-0.0012345"）
     */
    static double parseSignedDouble(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        return Double.parseDouble(s);
    }

    /**
     * 解析 TLE 指数格式小数
     * 例: " 33590-3" → 0.33590 × 10⁻³
     *     ".12345-2" → 0.12345 × 10⁻²
     *     "-43656-4" → -0.43656 × 10⁻⁴（负 BSTAR：Starlink 机动卫星常见）
     */
    static double parseExponential(String s) {
        s = s.trim();
        if (s.isEmpty()) return 0.0;
        // 取最后一个 +/- 作为指数符号——尾数可能带前导负号（负 BSTAR）
        int signPos = Math.max(s.lastIndexOf('+'), s.lastIndexOf('-'));
        if (signPos <= 0) {
            return Double.parseDouble("0." + s.replace(".", "").trim());
        }
        String mantissaStr = s.substring(0, signPos);
        String expStr = s.substring(signPos);
        // 构造标准科学计数法: 补 "0" 前缀
        if (!mantissaStr.contains(".")) {
            boolean negative = false;
            if (mantissaStr.startsWith("-")) {
                negative = true;
                mantissaStr = mantissaStr.substring(1);
            } else if (mantissaStr.startsWith("+")) {
                mantissaStr = mantissaStr.substring(1);
            }
            double value = Double.parseDouble("0." + mantissaStr + "e" + expStr);
            return negative ? -value : value;
        }
        return Double.parseDouble(mantissaStr + "e" + expStr);
    }

    /**
     * 解析 TLE 历元 (YYDDD.FFFFFFF → Unix秒)
     * 例: "24001.50000000" → 2024-01-01T12:00:00Z
     */
    static double parseEpoch(String epochStr) {
        int year = Integer.parseInt(epochStr.substring(0, 2));
        year = year >= 57 ? 1900 + year : 2000 + year;
        int dayOfYear = Integer.parseInt(epochStr.substring(2, 5));
        double fractionOfDay = Double.parseDouble("0" + epochStr.substring(5));

        LocalDate date = LocalDate.ofYearDay(year, dayOfYear);
        long epochSeconds = date.atStartOfDay(ZoneOffset.UTC).toInstant().getEpochSecond();
        epochSeconds += (long) (fractionOfDay * 86400.0);
        return epochSeconds;
    }
}
