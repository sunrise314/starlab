package com.starlab.api;

import com.starlab.constellation.Constellation;
import com.starlab.orbit.TleCatalogService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TLE 批量导入端点
 * <p>
 * POST /api/tle/import?load=true，请求体为 TLE 原始文本（text/plain）。
 * load=true 时解析成功的卫星整体换装到当前星座（{@link Constellation#replaceSatellites}）。
 */
@RestController
@RequestMapping("/api/tle")
public class TleImportController {

    private final TleCatalogService catalogService;
    private final Constellation constellation;

    public TleImportController(TleCatalogService catalogService, Constellation constellation) {
        this.catalogService = catalogService;
        this.constellation = constellation;
    }

    public record ImportResult(int totalGroups, int parsed, int rejected, int duplicates,
                               long parseMs, List<String> sampleErrors,
                               boolean loaded, int constellationSize) {}

    @PostMapping(value = "/import", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ImportResult importTle(@RequestBody String text,
                                  @RequestParam(defaultValue = "false") boolean load) {
        TleCatalogService.ParsedCatalog cat = catalogService.parse(text);
        TleCatalogService.CatalogStats s = cat.stats();

        boolean loaded = false;
        int size = constellation.getSatelliteCount();
        if (load && !cat.satellites().isEmpty()) {
            constellation.replaceSatellites(cat.satellites());
            loaded = true;
            size = constellation.getSatelliteCount();
        }
        return new ImportResult(s.totalGroups(), s.parsed(), s.rejected(), s.duplicates(),
                s.parseMs(), s.sampleErrors(), loaded, size);
    }
}
