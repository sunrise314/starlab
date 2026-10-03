package com.starlab.twin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * CelesTrak 客户端 —— 孪生闭环的"现实源"
 * <p>
 * CelesTrak 每天发布新 TLE（GP 数据），是公开、免鉴权、持续更新的真实轨道信息源。
 * 用"旧快照 TLE 预测 vs 新发布 TLE"的差异来度量传播模型的真实误差，
 * 这是没有自建地面站时能拿到的最诚实遥测对表数据。
 * <p>
 * 两个工程事实（2026-10-04 实测）：
 *   1. ~1.5 MB 的星链目录偶发"200 OK 后响应体中断"，重试即可拿到完整数据；
 *   2. CelesTrak 有缓存合规限流：同一 GROUP 成功下载后 2h 内重复请求返回
 *      403 + 提示正文，此时应换 IP 或等缓存窗口，而不是加重试硬怼。
 */
public class CelesTrakClient {

    private static final String BASE = "https://celestrak.org";
    private static final long[] RETRY_BACKOFF_MS = {2000, 4000, 8000};

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 拉取一个卫星组的 TLE 文本（名称行 + line1 + line2 三行一组）
     *
     * @param group 组名，如 starlink / stations / oneweb
     */
    public String fetchGroupTle(String group) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + "/NORAD/elements/gp.php?GROUP=" + group + "&FORMAT=tle"))
                .header("User-Agent", "starlab-twin-loop/1.0")
                .header("Accept", "text/plain")
                .timeout(Duration.ofSeconds(90))
                .GET()
                .build();

        HttpResponse<String> resp = null;
        for (int attempt = 0; attempt <= RETRY_BACKOFF_MS.length; attempt++) {
            if (attempt > 0) {
                sleep(RETRY_BACKOFF_MS[attempt - 1]);
            }
            try {
                resp = http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) {
                if (attempt == RETRY_BACKOFF_MS.length) {
                    throw new IllegalStateException("CelesTrak 下载失败（含重试）: " + e.getMessage(), e);
                }
                continue;   // 传输中断 → 退避重试
            }
            if (resp.statusCode() == 200) {
                String body = resp.body();
                if (body == null || !body.contains("\n1 ")) {
                    throw new IllegalStateException("CelesTrak 返回内容不是 TLE 文本: group=" + group);
                }
                return body;
            }
            // 缓存合规 403 属预期行为：重试无意义，直接给出可操作错误
            String body = resp.body() == null ? "" : resp.body().trim();
            if (body.contains("has not updated since") || body.contains("Data is updated")) {
                throw new IllegalStateException(
                        "CelesTrak 缓存合规限流：GROUP=" + group + " 在 2h 窗口内已下载过，"
                                + "请换出口 IP 或等数据更新（每 2h 一次）。响应: " + abbreviate(body));
            }
            if (attempt == RETRY_BACKOFF_MS.length) {
                throw new IllegalStateException("CelesTrak HTTP " + resp.statusCode() + ": " + abbreviate(body));
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("重试等待被中断", e);
        }
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
