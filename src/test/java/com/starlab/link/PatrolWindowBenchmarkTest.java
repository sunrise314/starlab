package com.starlab.link;

import com.starlab.constellation.Constellation;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.SatellitePosition;
import com.starlab.orbit.Sgp4Propagator;
import com.starlab.orbit.TleCatalogService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static com.starlab.link.InterferenceBenchmarkTest.Dist;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 09 章（收官）基准：端到端生成"全星座 24 小时过境窗口表"。
 * <p>
 * 数字孪生巡检平台的排班底座：AOS/LOS 时刻、窗口时长、最大仰角、方位角、
 * 每站昼夜覆盖占比、最坏空窗（巡检等待上界）、并发可见数（与第 6 章 K=205 对照）。
 * <p>
 * 口径：T0 与第 5~8 章对齐；tick=30s；可见性 = 视在仰角 ≥ 10°（含 ITU-R P.834 折射，
 * 与 LinkCalculator/第 5 章一致）。24h 切片边缘的窗口/空窗按切片内统计。
 * <p>
 * 手动跑（约 2 分钟）：临时注释掉下方 @Disabled 执行，跑完记得恢复。
 */
@Disabled("手动基准：24h 全星座窗口表生成，本地按需运行")
class PatrolWindowBenchmarkTest {

    private static final Instant T0 = Instant.parse("2026-10-03T12:00:00Z");
    private static final int DURATION_SEC = 86400; // 24h
    private static final int TICK_SEC = 30;

    @Test
    void patrolWindowTable24h() throws Exception {
        String text = resource("/tle/starlink-2026-10.tle");
        TleCatalogService.ParsedCatalog cat = new TleCatalogService().parse(text);
        List<OrbitalElements> sats = cat.satellites();
        System.out.printf("[catalog] parsed=%d rejected=%d dup=%d%n",
                cat.stats().parsed(), cat.stats().rejected(), cat.stats().duplicates());

        Constellation constellation = new Constellation();
        constellation.replaceSatellites(sats);
        OrbitPropagator prop = new OrbitPropagator(new Sgp4Propagator());
        LinkCalculator links = new LinkCalculator();
        List<GroundStation> stations = constellation.getGroundStations();

        int nS = sats.size(), nSt = stations.size();
        int ticks = DURATION_SEC / TICK_SEC;
        System.out.printf("[plan] 24h x %d 颗 x %d 站, tick=%ds → %d tick, SGP4 传播 %dM 步, 链路判定 %dM 次%n",
                nS, nSt, TICK_SEC, ticks, (long) ticks * nS / 1_000_000, (long) ticks * nSt * nS / 1_000_000);

        // ── 窗口状态机（每 站×星 一格）──
        boolean[][] inWin = new boolean[nSt][nS];
        int[][] winStartTick = new int[nSt][nS];
        double[][] winBestEl = new double[nSt][nS];
        double[][] winBestAz = new double[nSt][nS];

        Dist durAll = new Dist(), elAll = new Dist();
        Dist[] durBySt = new Dist[nSt], elBySt = new Dist[nSt], concVis = new Dist[nSt];
        for (int s = 0; s < nSt; s++) {
            durBySt[s] = new Dist(); elBySt[s] = new Dist(); concVis[s] = new Dist();
        }
        long[] winCount = new long[nSt];
        long[] coveredTicks = new long[nSt];
        long[] longestGap = new long[nSt];
        int[] prevCovered = new int[nSt]; // -1 = 尚未覆盖
        java.util.Arrays.fill(prevCovered, -1);
        String[][] sampleWin = new String[nSt][3];
        int[] sampleN = new int[nSt];
        long winTotal = 0;

        long start = System.currentTimeMillis();
        for (int i = 0; i < ticks; i++) {
            Instant t = T0.plusSeconds((long) i * TICK_SEC);
            List<SatellitePosition> positions = new ArrayList<>(nS);
            for (OrbitalElements el : sats) positions.add(prop.propagate(el, t));

            for (int si = 0; si < nSt; si++) {
                GroundStation st = stations.get(si);
                int visNow = 0;
                for (int j = 0; j < nS; j++) {
                    LinkResult lr = links.calculate(positions.get(j), st);
                    if (lr.visible()) {
                        visNow++;
                        if (!inWin[si][j]) {
                            inWin[si][j] = true;
                            winStartTick[si][j] = i;
                            winBestEl[si][j] = lr.elevationDeg();
                            winBestAz[si][j] = lr.azimuthDeg();
                        } else if (lr.elevationDeg() > winBestEl[si][j]) {
                            winBestEl[si][j] = lr.elevationDeg();
                            winBestAz[si][j] = lr.azimuthDeg();
                        }
                    } else if (inWin[si][j]) {
                        inWin[si][j] = false;
                        double durMin = (i - winStartTick[si][j]) * (double) TICK_SEC / 60.0;
                        durAll.add(durMin);
                        durBySt[si].add(durMin);
                        elAll.add(winBestEl[si][j]);
                        elBySt[si].add(winBestEl[si][j]);
                        winCount[si]++;
                        winTotal++;
                        if (sampleN[si] < 3) {
                            sampleWin[si][sampleN[si]++] = String.format(
                                    "AOS %s → LOS %s  %.0f min  最大仰角 %.1f° @ 方位 %.0f°",
                                    T0.plusSeconds((long) winStartTick[si][j] * TICK_SEC),
                                    T0.plusSeconds((long) i * TICK_SEC),
                                    durMin, winBestEl[si][j], winBestAz[si][j]);
                        }
                    }
                }
                coveredTicks[si] += visNow > 0 ? 1 : 0;
                if (visNow > 0) {
                    long gap = prevCovered[si] < 0 ? i : i - prevCovered[si] - 1;
                    if (gap > longestGap[si]) longestGap[si] = gap;
                    prevCovered[si] = i;
                }
                concVis[si].add(visNow);
            }
            if (i % 480 == 0) {
                System.out.printf("    [%02dh/%02dh] elapsed %.0fs%n",
                        i * TICK_SEC / 3600, DURATION_SEC / 3600,
                        (System.currentTimeMillis() - start) / 1000.0);
            }
        }
        // 收尾：切片末尾仍开着的窗口关闭；最后一段空窗入账
        for (int si = 0; si < nSt; si++) {
            for (int j = 0; j < nS; j++) {
                if (inWin[si][j]) {
                    double durMin = (ticks - winStartTick[si][j]) * (double) TICK_SEC / 60.0;
                    durAll.add(durMin); durBySt[si].add(durMin);
                    elAll.add(winBestEl[si][j]); elBySt[si].add(winBestEl[si][j]);
                    winCount[si]++; winTotal++;
                }
            }
            long gap = (ticks - 1) - prevCovered[si];
            if (gap > longestGap[si]) longestGap[si] = gap;
        }
        long scanMs = System.currentTimeMillis() - start;

        // ── 报表 ──
        System.out.println();
        System.out.printf("== 24h 巡检窗口表（全星座 %d 颗 x %d 站, tick=%ds, 生成耗时 %.1fs）==%n",
                nS, nSt, TICK_SEC, scanMs / 1000.0);
        System.out.printf("[吞吐] SGP4 传播 %dM 步 + 链路判定 %dM 次，端到端 %.2fM 判定/s，单 tick %.0f ms%n",
                (long) ticks * nS / 1_000_000, (long) ticks * nSt * nS / 1_000_000,
                (double) ticks * nSt * nS / scanMs / 1000.0, (double) scanMs / ticks);

        System.out.println();
        System.out.printf("窗口总数 = %d（%d 站合计，%.0f 窗/站/天）%n", winTotal, nSt, (double) winTotal / nSt);
        for (int si = 0; si < nSt; si++) {
            GroundStation st = stations.get(si);
            System.out.printf("  %s(%s): 窗口 %d 个, 覆盖 %.2f%%, 最坏空窗 %.0f min%n",
                    st.id(), st.name(), winCount[si],
                    100.0 * coveredTicks[si] / ticks, longestGap[si] * TICK_SEC / 60.0);
        }
        System.out.print("窗口时长 (min)  : "); durAll.print();
        for (int si = 0; si < nSt; si++) {
            System.out.printf("  %-4s med=%.1f min%n", stations.get(si).id(), durBySt[si].median());
        }
        System.out.print("窗口最大仰角 (°): "); elAll.print();
        System.out.println("并发可见数（每 时刻×站, 对照第 6 章 K=205）:");
        for (int si = 0; si < nSt; si++) {
            System.out.printf("  %-4s med=%.0f max=%.0f%n",
                    stations.get(si).id(), concVis[si].median(), concVis[si].percentile(1.0));
        }
        System.out.println("窗口表样例（第 1 站前 3 行）:");
        for (String s : sampleWin[0]) System.out.println("  " + s);

        assertTrue(winTotal > 100, "窗口数异常");
        assertTrue(cat.stats().parsed() > 10_000);
    }

    private static String resource(String path) throws Exception {
        try (InputStream in = PatrolWindowBenchmarkTest.class.getResourceAsStream(path)) {
            assert in != null;
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
