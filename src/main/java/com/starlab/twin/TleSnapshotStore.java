package com.starlab.twin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starlab.orbit.OrbitalElements;
import com.starlab.orbit.TleCatalogService;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 孪生闭环快照与报告的本地存储（文件持久化，无数据库依赖）
 * <p>
 * data/twin/snapshots/&lt;id&gt;.tle      —— TLE 快照原文
 * data/twin/snapshots/&lt;id&gt;.meta.json —— 快照元数据
 * data/twin/reports/&lt;id&gt;.json        —— 对表报告
 */
@Component
public class TleSnapshotStore {

    private static final Path SNAPSHOT_DIR = Path.of("data/twin/snapshots");
    private static final Path REPORT_DIR = Path.of("data/twin/reports");
    private static final DateTimeFormatter ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final ObjectMapper mapper = new ObjectMapper();
    private final TleCatalogService catalogService;

    public TleSnapshotStore(TleCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public record SnapshotMeta(String id, String savedAt, String source, int satellites, String epochMedianIso) {}

    /** 保存一份 TLE 快照，返回元数据 */
    public SnapshotMeta saveSnapshot(String source, String tleText) throws IOException {
        Files.createDirectories(SNAPSHOT_DIR);
        TleCatalogService.ParsedCatalog cat = catalogService.parse(tleText);

        String id = "snap-" + ID_FMT.format(Instant.now().atOffset(ZoneOffset.UTC));
        Files.writeString(SNAPSHOT_DIR.resolve(id + ".tle"), tleText);

        List<Double> epochs = cat.satellites().stream().map(OrbitalElements::epochSeconds).sorted().toList();
        double med = epochs.isEmpty() ? 0 : epochs.get(epochs.size() / 2);
        SnapshotMeta meta = new SnapshotMeta(id, Instant.now().toString(), source,
                cat.satellites().size(), Instant.ofEpochSecond((long) med).toString());
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(SNAPSHOT_DIR.resolve(id + ".meta.json").toFile(), meta);
        return meta;
    }

    public List<SnapshotMeta> listSnapshots() throws IOException {
        if (!Files.exists(SNAPSHOT_DIR)) return List.of();
        try (Stream<Path> files = Files.list(SNAPSHOT_DIR)) {
            return files.filter(p -> p.getFileName().toString().endsWith(".meta.json"))
                    .sorted(Comparator.reverseOrder())
                    .map(p -> {
                        try {
                            return mapper.readValue(p.toFile(), SnapshotMeta.class);
                        } catch (IOException e) {
                            throw new IllegalStateException("快照元数据损坏: " + p, e);
                        }
                    })
                    .toList();
        }
    }

    public String loadSnapshot(String id) throws IOException {
        Path file = SNAPSHOT_DIR.resolve(id + ".tle");
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("快照不存在: " + id);
        }
        return Files.readString(file);
    }

    public void saveReport(TleReconciliationService.ReconciliationReport report) throws IOException {
        Files.createDirectories(REPORT_DIR);
        String file = report.snapshotId().replaceAll("[^A-Za-z0-9_-]", "_")
                + "-" + ID_FMT.format(Instant.now().atOffset(ZoneOffset.UTC)) + ".json";
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(REPORT_DIR.resolve(file).toFile(), report);
    }

    public TleReconciliationService.ReconciliationReport latestReport() throws IOException {
        if (!Files.exists(REPORT_DIR)) {
            throw new IllegalStateException("尚无对表报告");
        }
        try (Stream<Path> files = Files.list(REPORT_DIR)) {
            Path latest = files.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .max(Comparator.naturalOrder())
                    .orElseThrow(() -> new IllegalStateException("尚无对表报告"));
            return mapper.readValue(latest.toFile(), TleReconciliationService.ReconciliationReport.class);
        }
    }
}
