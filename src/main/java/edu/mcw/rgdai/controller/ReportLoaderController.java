package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.service.ReportConverterService;
import edu.mcw.rgdai.service.ReportLoaderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/report-loader")
public class ReportLoaderController {

    private static final Logger LOG = LoggerFactory.getLogger(ReportLoaderController.class);
    private final ReportConverterService converterService;
    private final ReportLoaderService loaderService;

    @Value("${report.loader.output-dir}")
    private String outputDir;

    public ReportLoaderController(ReportConverterService converterService,
                                  ReportLoaderService loaderService) {
        this.converterService = converterService;
        this.loaderService = loaderService;
    }

    // ============================================================
    // Single URL convert (unchanged)
    // ============================================================
    @PostMapping("/convert")
    public ResponseEntity<?> convertUrl(@RequestBody Map<String, String> request) {
        try {
            String url = request.get("url");
            if (url == null || url.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of("error", "URL is required"));
            }

            LOG.info("Converting URL: {}", url);

            String html = converterService.fetchHtml(url.trim());
            ReportConverterService.ConversionResult result = converterService.convert(html, url.trim());

            // Save file to output directory
            String reportType = result.metadata.reportType.isEmpty() ? "other" : result.metadata.reportType;
            String safeSymbol = (result.metadata.entityName.isEmpty() ? "unknown" : result.metadata.entityName)
                    .replaceAll("[^a-zA-Z0-9_.-]", "_");
            String rgdId = result.metadata.rgdId.isEmpty() ? "noid" : result.metadata.rgdId;
            String fileName = reportType + "_" + safeSymbol + "_" + rgdId + ".md";

            Path typeDir = Paths.get(outputDir, reportType);
            Files.createDirectories(typeDir);
            Path filePath = typeDir.resolve(fileName);
            Files.writeString(filePath, result.markdown);
            long fileSize = Files.size(filePath);

            // Save processed HTML (what FlexmarkHtmlConverter receives) for debugging
            Path htmlDir = Paths.get(outputDir).getParent().resolve("html").resolve(reportType);
            Files.createDirectories(htmlDir);

            String procFileName = reportType + "_" + safeSymbol + "_" + rgdId + ".html";
            Files.writeString(htmlDir.resolve(procFileName), result.processedHtml);

            LOG.info("Saved: {} ({} bytes) + raw HTML", filePath, fileSize);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("markdown", result.markdown);
            response.put("displayName", result.metadata.displayName);
            response.put("title", result.metadata.title);
            response.put("rgdId", result.metadata.rgdId);
            response.put("reportType", result.metadata.reportType);
            response.put("entityName", result.metadata.entityName);
            response.put("sourceLink", result.metadata.sourceLink);
            response.put("charCount", result.charCount);
            response.put("lineCount", result.lineCount);
            response.put("elapsedMs", result.elapsedMs);
            response.put("filePath", filePath.toString());
            response.put("fileName", fileName);
            response.put("fileSize", fileSize);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            LOG.error("Error converting URL", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // ============================================================
    // Bulk loader endpoints
    // ============================================================

    @GetMapping("/species")
    public ResponseEntity<?> species(@RequestParam("type") String type) {
        try {
            List<Map<String, Object>> list = loaderService.getSpecies(type);
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            LOG.error("species() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/assemblies")
    public ResponseEntity<?> assemblies(@RequestParam("species") int speciesKey) {
        try {
            List<Map<String, Object>> list = loaderService.getAssemblies(speciesKey);
            return ResponseEntity.ok(list);
        } catch (Exception e) {
            LOG.error("assemblies() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/count")
    public ResponseEntity<?> count(@RequestParam("type") String type,
                                   @RequestParam(value = "species", defaultValue = "0") int speciesKey,
                                   @RequestParam(value = "mapKey", defaultValue = "0") int mapKey) {
        try {
            int count = loaderService.countReports(type, speciesKey, mapKey);
            return ResponseEntity.ok(Map.of("count", count));
        } catch (Exception e) {
            LOG.error("count() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/bulk/start")
    public ResponseEntity<?> bulkStart(@RequestBody Map<String, Object> body) {
        try {
            String type = (String) body.get("reportType");
            int speciesKey = body.get("speciesKey") == null ? 0 : ((Number) body.get("speciesKey")).intValue();
            int mapKey = body.get("mapKey") == null ? 0 : ((Number) body.get("mapKey")).intValue();
            boolean reset = Boolean.TRUE.equals(body.get("reset"));

            Map<String, Object> resp = loaderService.startBatch(type, speciesKey, mapKey, reset);
            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("bulkStart() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/bulk/retry")
    public ResponseEntity<?> bulkRetry(@RequestBody Map<String, Object> body) {
        try {
            String type = (String) body.get("reportType");
            int speciesKey = body.get("speciesKey") == null ? 0 : ((Number) body.get("speciesKey")).intValue();
            int mapKey = body.get("mapKey") == null ? 0 : ((Number) body.get("mapKey")).intValue();

            Map<String, Object> resp = loaderService.retryFailed(type, speciesKey, mapKey);
            return ResponseEntity.ok(resp);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("bulkRetry() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/bulk/cancel")
    public ResponseEntity<?> bulkCancel() {
        return ResponseEntity.ok(loaderService.cancel());
    }

    @GetMapping("/bulk/progress")
    public ResponseEntity<?> bulkProgress(@RequestParam("type") String type,
                                          @RequestParam(value = "species", defaultValue = "0") int speciesKey,
                                          @RequestParam(value = "mapKey", defaultValue = "0") int mapKey) {
        try {
            return ResponseEntity.ok(loaderService.getProgress(type, speciesKey, mapKey));
        } catch (Exception e) {
            LOG.error("bulkProgress() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/bulk/active")
    public ResponseEntity<?> bulkActive() {
        try {
            return ResponseEntity.ok(loaderService.getActiveBatch());
        } catch (Exception e) {
            LOG.error("bulkActive() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
