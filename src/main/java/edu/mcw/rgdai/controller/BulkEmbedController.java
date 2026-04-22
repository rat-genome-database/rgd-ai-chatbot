package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.service.BulkEmbedService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/curation/bulk-embed")
public class BulkEmbedController {

    private static final Logger LOG = LoggerFactory.getLogger(BulkEmbedController.class);
    private final BulkEmbedService bulkEmbedService;

    public BulkEmbedController(BulkEmbedService bulkEmbedService) {
        this.bulkEmbedService = bulkEmbedService;
    }

    @GetMapping("/directories")
    public ResponseEntity<?> listDirectories() {
        try {
            return ResponseEntity.ok(bulkEmbedService.listDirectories());
        } catch (Exception e) {
            LOG.error("listDirectories() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/start")
    public ResponseEntity<?> start(@RequestBody Map<String, Object> body) {
        try {
            String path = (String) body.get("path");
            boolean forceReembed = Boolean.TRUE.equals(body.get("forceReembed"));
            return ResponseEntity.ok(bulkEmbedService.startEmbed(path, forceReembed));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("start() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/cancel")
    public ResponseEntity<?> cancel() {
        return ResponseEntity.ok(bulkEmbedService.cancel());
    }

    @PostMapping("/retry")
    public ResponseEntity<?> retry() {
        try {
            return ResponseEntity.ok(bulkEmbedService.retryFailed());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            LOG.error("retry() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/progress")
    public ResponseEntity<?> progress() {
        return ResponseEntity.ok(bulkEmbedService.getProgress());
    }

    @GetMapping("/active")
    public ResponseEntity<?> active() {
        try {
            return ResponseEntity.ok(bulkEmbedService.getActiveBatch());
        } catch (Exception e) {
            LOG.error("active() failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }
}
