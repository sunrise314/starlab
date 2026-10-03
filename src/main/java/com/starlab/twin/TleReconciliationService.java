package com.starlab.twin;

import com.starlab.orbit.OrbitPropagator;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.SatellitePosition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 孪生闭环核心：TLE 快照对表（预测 vs 现实）
 * <p>
 * 原理：TLE 星历每 1~3 天由真实观测更新一次。拿"龄期 N 天的旧快照"把卫星传播到
 * "新发布 TLE 的历元时刻"，再与新 TLE 自身状态比对——两者之差就是传播模型在
 * 真实世界的误差（TLE 拟合误差 + SGP4 模型差 + 大气阻力预报差的合计）。
 * <p>
 * 产出三件事：
 *   1. 对表报告：全量误差分布（p50/p90/p99/max）+ 按龄期分桶的误差曲线；
 *   2. 归因：p99 以外的离群星逐颗列出——通常是正在升轨/离轨机动的卫星；
 *   3. 校准：从"龄期-误差"曲线反推平台 TLE 强制刷新周期（p90 ≤ 25 km 的最大龄期）。
 */
@Service
public class TleReconciliationService {

    /** 位置误差告警阈值 (km)：550 km 轨道上 ~25 km ≈ 2.6° 视差，足以影响 10° 仰角门限判定 */
    private static final double ERR_ALARM_KM = 25.0;

    /** 龄期分桶上界（天） */
    private static final double[] AGE_EDGES = {1, 2, 3, 5, 7, 14, Double.MAX_VALUE};
    private static final String[] AGE_LABELS = {"≤1d", "1-2d", "2-3d", "3-5d", "5-7d", "7-14d", ">14d"};

    /** 桶内样本数低于该值不参与刷新周期判定（统计不稳） */
    private static final int MIN_BUCKET_N = 50;

    private final OrbitPropagator prop;

    public TleReconciliationService(OrbitPropagator prop) {
        this.prop = prop;
    }

    public record ReconciliationReport(
            String snapshotId, String realitySource, String generatedAt,
            int snapshotCount, int freshCount, int matched, int onlyInSnapshot, int onlyInFresh,
            double ageDaysMedian, double ageDaysMax,
            double errKmMean, double errKmP50, double errKmP90, double errKmP99, double errKmMax,
            long over25km, long over100km, int ageAnomalies,
            List<AgeBucket> ageBuckets, List<Outlier> outliers, String policy) {

        public record AgeBucket(String range, int n, double p50Km, double p90Km, double maxKm) {}
        public record Outlier(String catalogNumber, String name, double ageDays, double errKm) {}
    }

    private record Sample(String catalogNumber, String name, double ageDays, double errKm) {}

    /**
     * 对表：旧快照（snapshot）预测 vs 新目录（fresh，现实源）
     */
    public ReconciliationReport reconcile(List<OrbitalElements> snapshot, List<OrbitalElements> fresh,
                                          String snapshotId, String realitySource) {
        Map<String, OrbitalElements> freshByCat = new HashMap<>(fresh.size() * 2);
        for (OrbitalElements el : fresh) freshByCat.put(el.catalogNumber(), el);

        List<Sample> samples = new ArrayList<>(snapshot.size());
        int onlyInSnapshot = 0;
        int ageAnomalies = 0;
        for (OrbitalElements old : snapshot) {
            OrbitalElements now = freshByCat.get(old.catalogNumber());
            if (now == null) {
                onlyInSnapshot++;
                continue;
            }
            Instant t = Instant.ofEpochSecond((long) now.epochSeconds());
            double ageDays = (t.getEpochSecond() - old.epochSeconds()) / 86400.0;
            if (ageDays < 0) {
                ageAnomalies++;   // 现实目录历元早于快照历元（对表方向反了），单颗跳过
                continue;
            }
            SatellitePosition pred = prop.propagate(old, t);
            SatellitePosition real = prop.propagate(now, t);
            double dx = pred.ecefX() - real.ecefX();
            double dy = pred.ecefY() - real.ecefY();
            double dz = pred.ecefZ() - real.ecefZ();
            samples.add(new Sample(old.catalogNumber(), now.name(), ageDays, Math.sqrt(dx * dx + dy * dy + dz * dz)));
        }
        // 快照里命中的（含因龄期为负被跳过的）都算"在现实源中存在"
        int onlyInFresh = Math.max(0, fresh.size() - (snapshot.size() - onlyInSnapshot));

        // ── 误差分布 ──
        double[] errs = samples.stream().mapToDouble(Sample::errKm).toArray();
        double[] ages = samples.stream().mapToDouble(Sample::ageDays).toArray();
        double[] errsSorted = errs.clone();
        Arrays.sort(errsSorted);
        double[] agesSorted = ages.clone();
        Arrays.sort(agesSorted);

        double sum = 0;
        long over25 = 0, over100 = 0;
        for (double e : errs) {
            sum += e;
            if (e >= ERR_ALARM_KM) over25++;
            if (e >= 100) over100++;
        }

        // ── 龄期分桶 ──
        List<ReconciliationReport.AgeBucket> buckets = new ArrayList<>();
        for (int b = 0; b < AGE_EDGES.length; b++) {
            double lo = b == 0 ? 0 : AGE_EDGES[b - 1];
            double hi = AGE_EDGES[b];
            double[] bucketErrs = samples.stream()
                    .filter(s -> s.ageDays() >= lo && s.ageDays() < hi)
                    .mapToDouble(Sample::errKm).toArray();
            if (bucketErrs.length == 0) continue;
            Arrays.sort(bucketErrs);
            buckets.add(new ReconciliationReport.AgeBucket(
                    AGE_LABELS[b], bucketErrs.length,
                    r2(pct(bucketErrs, 0.5)), r2(pct(bucketErrs, 0.9)), r2(bucketErrs[bucketErrs.length - 1])));
        }

        // ── 离群星（机动嫌疑）──
        List<ReconciliationReport.Outlier> outliers = samples.stream()
                .sorted(Comparator.comparingDouble(Sample::errKm).reversed())
                .limit(10)
                .map(s -> new ReconciliationReport.Outlier(s.catalogNumber(), s.name(), r2(s.ageDays()), r2(s.errKm())))
                .toList();

        // ── 校准：反推 TLE 强制刷新周期 ──
        String policy = deriveRefreshPolicy(buckets);

        return new ReconciliationReport(
                snapshotId, realitySource, Instant.now().toString(),
                snapshot.size(), fresh.size(), samples.size(), onlyInSnapshot, onlyInFresh,
                r2(agesSorted.length == 0 ? 0 : pct(agesSorted, 0.5)),
                r2(agesSorted.length == 0 ? 0 : agesSorted[agesSorted.length - 1]),
                r2(samples.isEmpty() ? 0 : sum / samples.size()),
                r2(pct(errsSorted, 0.5)), r2(pct(errsSorted, 0.9)),
                r2(pct(errsSorted, 0.99)), r2(errsSorted.length == 0 ? 0 : errsSorted[errsSorted.length - 1]),
                over25, over100, ageAnomalies,
                List.copyOf(buckets), List.copyOf(outliers), policy);
    }

    /** 最后一个 n≥50 且 p90 ≤ 25 km 的龄期桶上界 = 建议刷新周期 */
    private String deriveRefreshPolicy(List<ReconciliationReport.AgeBucket> buckets) {
        String best = null;
        for (ReconciliationReport.AgeBucket b : buckets) {
            if (b.n() >= MIN_BUCKET_N && b.p90Km() <= ERR_ALARM_KM) {
                best = b.range();
            }
        }
        if (best == null) {
            return "TLE 龄期 ≤1 天时 p90 误差已超 " + (int) ERR_ALARM_KM + " km——平台需要实时/每日刷新星历";
        }
        return "TLE 龄期 " + best + " 内 p90 位置误差 ≤ " + (int) ERR_ALARM_KM
                + " km → 平台 TLE 强制刷新周期建议 = " + best;
    }

    private static double pct(double[] sorted, double p) {
        if (sorted.length == 0) return 0;
        return sorted[(int) Math.floor(p * (sorted.length - 1))];
    }

    private static double r2(double x) {
        return Math.round(x * 100.0) / 100.0;
    }
}
