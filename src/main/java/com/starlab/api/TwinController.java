package com.starlab.api;

import com.starlab.orbit.TleCatalogService;
import com.starlab.twin.CelesTrakClient;
import com.starlab.twin.TleReconciliationService;
import com.starlab.twin.TleSnapshotStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 孪生闭环 API —— 快照、对表、报告
 * <p>
 * POST /api/twin/snapshots/fetch?group=starlink  从 CelesTrak 拉取当前星历并存快照
 * POST /api/twin/snapshots/upload                上传 TLE 文本存快照（离线场景）
 * GET  /api/twin/snapshots                       快照列表
 * POST /api/twin/reconcile?snapshot=&amp;group=   对表：旧快照预测 vs 现实源新历元，产出报告
 * GET  /api/twin/report/latest                   最近一次对表报告
 */
@RestController
@RequestMapping("/api/twin")
public class TwinController {

    private final CelesTrakClient celestrak;
    private final TleSnapshotStore store;
    private final TleReconciliationService reconciliation;
    private final TleCatalogService catalogService;

    public TwinController(CelesTrakClient celestrak, TleSnapshotStore store,
                          TleReconciliationService reconciliation, TleCatalogService catalogService) {
        this.celestrak = celestrak;
        this.store = store;
        this.reconciliation = reconciliation;
        this.catalogService = catalogService;
    }

    @PostMapping("/snapshots/fetch")
    public Mono<TleSnapshotStore.SnapshotMeta> fetchSnapshot(
            @RequestParam(defaultValue = "starlink") String group) {
        return Mono.fromCallable(() -> store.saveSnapshot("celestrak:" + group, celestrak.fetchGroupTle(group)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/snapshots/upload", consumes = MediaType.TEXT_PLAIN_VALUE)
    public TleSnapshotStore.SnapshotMeta uploadSnapshot(
            @RequestParam(required = false, defaultValue = "upload") String source,
            @RequestBody String tleText) {
        try {
            return store.saveSnapshot(source, tleText);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "快照保存失败: " + e.getMessage());
        }
    }

    @GetMapping("/snapshots")
    public List<TleSnapshotStore.SnapshotMeta> snapshots() {
        try {
            return store.listSnapshots();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @PostMapping("/reconcile")
    public Mono<TleReconciliationService.ReconciliationReport> reconcile(
            @RequestParam String snapshot,
            @RequestParam(defaultValue = "starlink") String group) {
        return Mono.fromCallable(() -> {
                    TleCatalogService.ParsedCatalog old = catalogService.parse(store.loadSnapshot(snapshot));
                    String freshText = celestrak.fetchGroupTle(group);
                    TleCatalogService.ParsedCatalog fresh = catalogService.parse(freshText);
                    TleReconciliationService.ReconciliationReport report = reconciliation.reconcile(
                            old.satellites(), fresh.satellites(), snapshot, "celestrak:" + group);
                    store.saveReport(report);
                    return report;
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/report/latest")
    public TleReconciliationService.ReconciliationReport latestReport() {
        try {
            return store.latestReport();
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }
}
