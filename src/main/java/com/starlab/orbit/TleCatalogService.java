package com.starlab.orbit;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TLE 目录服务 —— 批量导入星库
 * <p>
 * 将真实 TLE 文本文件（数千至上万颗卫星）解析为轨道根数列表，
 * 供 {@link com.starlab.constellation.Constellation#replaceSatellites} 整体换装。
 * <p>
 * 防御式设计：真实世界的 TLE 文件不是教科书样例——
 * 编辑器吃掉尾随空格、负 BSTAR、跨行粘贴产生的残行、重复 NORAD 编号都会出现。
 * 单条非法数据只拒绝该条，绝不中断整批导入。
 */
@Component
public class TleCatalogService {

    /** 每组错误样本上限（防止 report 被刷爆） */
    private static final int MAX_SAMPLE_ERRORS = 20;

    public record CatalogStats(int totalGroups, int parsed, int rejected, int duplicates,
                               long parseMs, List<String> sampleErrors) {}

    public record ParsedCatalog(List<OrbitalElements> satellites, CatalogStats stats) {}

    /**
     * 解析整份 TLE 文本（名称行 + line1 + line2 为一组，容忍 CRLF 与空行）
     */
    public ParsedCatalog parse(String text) {
        long t0 = System.nanoTime();
        List<String> lines = text.lines()
                .map(String::trim)
                .filter(l -> !l.isEmpty())
                .toList();

        // ── 1. 分组：名称行 + line1 + line2 为一组（状态机推进） ──
        List<String[]> groups = new ArrayList<>();
        String pendingName = null;
        String pendingL1 = null;
        for (String line : lines) {
            if (line.startsWith("1 ")) {
                pendingName = pendingName == null ? "UNKNOWN" : pendingName;
                pendingL1 = line;
            } else if (line.startsWith("2 ")) {
                if (pendingL1 != null) {
                    groups.add(new String[]{pendingName, pendingL1, line});
                } else {
                    groups.add(new String[]{null, null, line});  // 残组：缺 line1
                }
                pendingName = null;
                pendingL1 = null;
            } else {
                pendingName = line;  // 普通行视为名称行
            }
        }

        // ── 2. 逐组解析 + 校验 ──
        List<OrbitalElements> parsed = new ArrayList<>(groups.size());
        List<String> errors = new ArrayList<>();
        int rejected = 0;

        Map<String, OrbitalElements> byCatalog = new LinkedHashMap<>();
        int duplicates = 0;

        for (String[] g : groups) {
            String name = g[0], l1 = g[1], l2 = g[2];
            String reason = validate(l1, l2);
            if (reason != null) {
                rejected++;
                if (errors.size() < MAX_SAMPLE_ERRORS) {
                    errors.add(rejectSummary(name, l1, reason));
                }
                continue;
            }
            try {
                OrbitalElements el = TleParser.parse(
                        name == null ? "UNKNOWN" : name, l1, l2);
                OrbitalElements prev = byCatalog.get(el.catalogNumber());
                if (prev != null) {
                    duplicates++;
                    if (el.epochSeconds() > prev.epochSeconds()) {
                        byCatalog.put(el.catalogNumber(), el);  // 历元新者胜
                    }
                } else {
                    byCatalog.put(el.catalogNumber(), el);
                }
                parsed.add(el);
            } catch (Exception e) {
                rejected++;
                if (errors.size() < MAX_SAMPLE_ERRORS) {
                    errors.add(rejectSummary(name, l1, "解析异常: " + e.getMessage()));
                }
            }
        }

        long parseMs = (System.nanoTime() - t0) / 1_000_000;
        List<OrbitalElements> unique = List.copyOf(byCatalog.values());
        return new ParsedCatalog(unique,
                new CatalogStats(groups.size(), unique.size(), rejected, duplicates,
                        parseMs, List.copyOf(errors)));
    }

    /**
     * 结构与物理合法性校验，返回拒绝原因（null = 通过）
     */
    private String validate(String l1, String l2) {
        if (l1 == null || l2 == null || l1.length() < 69 || l2.length() < 69) {
            return "行不足 69 列（尾随空格被编辑器吃掉是最常见事故）";
        }
        if (!l1.startsWith("1 ") || !l2.startsWith("2 ")) {
            return "行首标识符非法（应为 \"1 \"/\"2 \"）";
        }
        if (!l1.substring(2, 7).equals(l2.substring(2, 7))) {
            return "line1/line2 NORAD 编号不匹配";
        }
        if (checksum(l1) != Character.getNumericValue(l1.charAt(68))
                || checksum(l2) != Character.getNumericValue(l2.charAt(68))) {
            return "校验和错误";
        }
        try {
            double i = Double.parseDouble(l2.substring(8, 16).trim());
            double e = Double.parseDouble("0." + l2.substring(26, 33).trim());
            double n = Double.parseDouble(l2.substring(52, 63).trim());
            if (i < 0 || i > 180) return "倾角越界: " + i;
            if (e < 0 || e >= 1) return "偏心率越界: " + e;
            if (n <= 0 || n >= 20) return "平均运动越界(LEO 应 < 20 圈/天): " + n;
        } catch (NumberFormatException ex) {
            return "数值字段非法: " + ex.getMessage();
        }
        return null;
    }

    /** 标准 TLE 校验和：前 68 列数字求和，'-' 记 1，模 10 */
    static int checksum(String line) {
        int sum = 0;
        for (int k = 0; k < 68; k++) {
            char c = line.charAt(k);
            if (c >= '0' && c <= '9') sum += c - '0';
            else if (c == '-') sum += 1;
        }
        return sum % 10;
    }

    private static String rejectSummary(String name, String l1, String reason) {
        String id = (l1 != null && l1.length() >= 7) ? l1.substring(2, 7).trim() : "?";
        return (name == null ? id : name) + " [" + id + "]: " + reason;
    }
}
