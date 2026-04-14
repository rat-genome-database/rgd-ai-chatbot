package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.service.ReportConverterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/report-loader")
public class ReportLoaderController {

    private static final Logger LOG = LoggerFactory.getLogger(ReportLoaderController.class);
    private final ReportConverterService converterService;

    @Value("${report.loader.output-dir}")
    private String outputDir;

    public ReportLoaderController(ReportConverterService converterService) {
        this.converterService = converterService;
    }

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
}
