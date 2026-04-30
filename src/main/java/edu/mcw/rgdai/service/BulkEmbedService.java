package edu.mcw.rgdai.service;

import edu.mcw.rgd.dao.impl.DocumentEmbeddingDAO;
import edu.mcw.rgd.dao.impl.EmbedStatusDAO;
import edu.mcw.rgd.datamodel.EmbedStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Bulk embedding service: reads markdown files from the bulk loader's output
 * directory, chunks them, and embeds into the vector store asynchronously.
 * Follows the same @Async pattern as ReportLoaderService.
 */
@Service
public class BulkEmbedService {

    private static final Logger LOG = LoggerFactory.getLogger(BulkEmbedService.class);

    private final VectorStore openaiVectorStore;
    private final ReportMarkdownChunker reportChunker;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingDAO documentEmbeddingDAO;
    private final EmbedStatusDAO embedStatusDAO;

    @Lazy
    @Autowired
    private BulkEmbedService self;

    @Value("${report.loader.output-dir}")
    private String outputDir;

    @Value("${bulk.embed.threads:2}")
    private int threadCount;

    // Concurrency control
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    // In-memory progress counters
    private final AtomicInteger totalCount = new AtomicInteger(0);
    private final AtomicInteger completedCount = new AtomicInteger(0);
    private final AtomicInteger skippedCount = new AtomicInteger(0);
    private final AtomicInteger failedCount = new AtomicInteger(0);
    private final AtomicReference<String> currentFile = new AtomicReference<>("");
    private final Set<String> activeFiles = ConcurrentHashMap.newKeySet();

    // Track active batch for page-load restore
    private volatile String activePath = null;
    private volatile boolean activeForceReembed = false;

    // Failed files for retry
    private final List<String> failedFiles = Collections.synchronizedList(new ArrayList<>());

    public BulkEmbedService(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                            ReportMarkdownChunker reportChunker,
                            DocumentPreprocessor preprocessor) {
        this.openaiVectorStore = openaiVectorStore;
        this.reportChunker = reportChunker;
        this.preprocessor = preprocessor;
        this.documentEmbeddingDAO = new DocumentEmbeddingDAO();
        this.embedStatusDAO = new EmbedStatusDAO();
    }

    // ============================================================
    // Directory listing
    // ============================================================

    public List<Map<String, Object>> listDirectories() throws Exception {
        List<Map<String, Object>> dirs = new ArrayList<>();
        Path root = Paths.get(outputDir);
        if (!Files.isDirectory(root)) return dirs;

        // Load all embedded file names once for efficient cross-referencing
        Set<String> embeddedNames = documentEmbeddingDAO.getEmbeddedFileNames();

        try (DirectoryStream<Path> typeStream = Files.newDirectoryStream(root)) {
            for (Path typeDir : typeStream) {
                if (!Files.isDirectory(typeDir)) continue;
                String reportType = typeDir.getFileName().toString();

                // Check for assembly subdirectories
                boolean hasSubDirs = false;
                try (DirectoryStream<Path> subStream = Files.newDirectoryStream(typeDir)) {
                    for (Path sub : subStream) {
                        if (Files.isDirectory(sub)) {
                            hasSubDirs = true;
                            Map<String, Object> info = buildDirInfo(sub, reportType,
                                    sub.getFileName().toString(), embeddedNames);
                            if (info != null) dirs.add(info);
                        }
                    }
                }

                // If no subdirectories, the type dir itself contains .md files
                if (!hasSubDirs) {
                    Map<String, Object> info = buildDirInfo(typeDir, reportType, "", embeddedNames);
                    if (info != null) dirs.add(info);
                }
            }
        }

        // Sort by path
        dirs.sort(Comparator.comparing(d -> (String) d.get("path")));
        return dirs;
    }

    private Map<String, Object> buildDirInfo(Path dir, String reportType,
                                              String assemblyName, Set<String> embeddedNames) throws IOException {
        List<String> mdFileNames = listMdFileNames(dir);
        if (mdFileNames.isEmpty()) return null;

        // Count how many are already embedded (check both raw filename and display name)
        int embedded = 0;
        for (String fn : mdFileNames) {
            if (embeddedNames.contains(fn)) {
                embedded++;
            } else {
                // Also check display name from file content
                String displayName = resolveDisplayName(dir.resolve(fn));
                if (displayName != null && !displayName.equals(fn) && embeddedNames.contains(displayName)) {
                    embedded++;
                }
            }
        }

        String path = assemblyName.isEmpty() ? reportType : reportType + "/" + assemblyName;

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("path", path);
        info.put("reportType", reportType);
        info.put("assemblyName", assemblyName);
        info.put("totalFiles", mdFileNames.size());
        info.put("embeddedFiles", embedded);
        info.put("remainingFiles", mdFileNames.size() - embedded);
        return info;
    }

    private List<String> listMdFileNames(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(p -> p.toString().endsWith(".md"))
                    .map(p -> p.getFileName().toString())
                    .collect(Collectors.toList());
        }
    }

    /**
     * Read just the first line of a file to extract display name from
     * <!-- file_name: ... --> comment. Returns null if not present.
     */
    private String resolveDisplayName(Path file) {
        try {
            String firstLine = Files.lines(file, StandardCharsets.UTF_8)
                    .findFirst().orElse("");
            if (firstLine.startsWith("<!-- file_name:")) {
                int end = firstLine.indexOf("-->");
                if (end > 0) {
                    String name = firstLine.substring("<!-- file_name:".length(), end).trim();
                    if (!name.isEmpty()) return name;
                }
            }
        } catch (IOException e) {
            // ignore
        }
        return null;
    }

    // ============================================================
    // Start / cancel / progress / active
    // ============================================================

    public Map<String, Object> startEmbed(String subPath, boolean forceReembed) {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Bulk embed already running");
        }

        cancelled.set(false);
        totalCount.set(0);
        completedCount.set(0);
        skippedCount.set(0);
        failedCount.set(0);
        currentFile.set("Preparing...");
        failedFiles.clear();
        activePath = subPath;
        activeForceReembed = forceReembed;

        self.processEmbedAsync(subPath, forceReembed);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "started");
        resp.put("path", subPath);
        resp.put("forceReembed", forceReembed);
        return resp;
    }

    public Map<String, Object> cancel() {
        cancelled.set(true);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "cancelling");
        resp.put("running", running.get());
        return resp;
    }

    public Map<String, Object> getProgress() {
        int total = totalCount.get();
        int completed = completedCount.get();
        int skipped = skippedCount.get();
        int failed = failedCount.get();
        int processed = completed + skipped + failed;

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("completed", completed);
        resp.put("skipped", skipped);
        resp.put("failed", failed);
        resp.put("pending", Math.max(0, total - processed));
        resp.put("running", running.get());
        resp.put("currentFile", currentFile.get());
        return resp;
    }

    public Map<String, Object> getActiveBatch() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", running.get());
        if (activePath != null) {
            resp.put("active", true);
            resp.put("path", activePath);
            resp.put("currentFile", currentFile.get());
        } else {
            resp.put("active", false);
        }
        return resp;
    }

    public Map<String, Object> retryFailed() {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Bulk embed already running");
        }

        // Get failed + interrupted files from DB (survives restart)
        List<String> toRetry = new ArrayList<>();
        try {
            List<EmbedStatus> failed = embedStatusDAO.getByStatus("FAILED");
            List<EmbedStatus> interrupted = embedStatusDAO.getByStatus("IN_PROGRESS");
            for (EmbedStatus es : failed) {
                toRetry.add(Paths.get(outputDir, es.getFilePath()).toString());
            }
            for (EmbedStatus es : interrupted) {
                toRetry.add(Paths.get(outputDir, es.getFilePath()).toString());
            }
        } catch (Exception e) {
            LOG.error("Failed to load retry list from DB", e);
            // Fall back to in-memory list
            toRetry.addAll(failedFiles.stream()
                    .map(fp -> Paths.get(outputDir, fp).toString())
                    .toList());
        }

        int count = toRetry.size();
        cancelled.set(false);
        totalCount.set(count);
        completedCount.set(0);
        skippedCount.set(0);
        failedCount.set(0);
        failedFiles.clear();
        activePath = "retry";
        currentFile.set("Retrying failed files...");

        self.processRetryAsync(toRetry);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "retrying");
        resp.put("retryCount", count);
        return resp;
    }

    // ============================================================
    // @Async processing
    // ============================================================

    @Async
    public void processEmbedAsync(String subPath, boolean forceReembed) {
        LOG.info("Bulk embed started: path={} forceReembed={} threads={}", subPath, forceReembed, threadCount);

        try {
            Path dir = Paths.get(outputDir, subPath);
            if (!Files.isDirectory(dir)) {
                LOG.error("Directory not found: {}", dir);
                return;
            }

            // Collect all .md files
            List<Path> mdFiles;
            try (Stream<Path> stream = Files.list(dir)) {
                mdFiles = stream
                        .filter(p -> p.toString().endsWith(".md"))
                        .sorted()
                        .collect(Collectors.toList());
            }

            totalCount.set(mdFiles.size());
            LOG.info("Found {} markdown files in {}", mdFiles.size(), dir);

            // Load completed file paths from embed_status table
            Set<String> completedPaths;
            try {
                List<EmbedStatus> completed = embedStatusDAO.getByStatus("COMPLETED");
                completedPaths = completed.stream()
                        .map(EmbedStatus::getFilePath)
                        .collect(Collectors.toSet());
                LOG.info("Found {} already-completed files in embed_status", completedPaths.size());
            } catch (Exception e) {
                LOG.error("Failed to load embed status", e);
                completedPaths = Collections.emptySet();
            }

            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            Set<String> finalCompletedPaths = completedPaths;
            Path rootDir = Paths.get(outputDir);

            for (Path mdFile : mdFiles) {
                if (cancelled.get()) {
                    LOG.info("Bulk embed paused — stopping submission at {}", mdFile.getFileName());
                    break;
                }
                pool.submit(() -> processOneFile(mdFile, rootDir, finalCompletedPaths, forceReembed));
            }

            pool.shutdown();
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            LOG.error("Bulk embed failed", e);
        } finally {
            running.set(false);
            currentFile.set("");
            LOG.info("Bulk embed finished: completed={} skipped={} failed={}",
                    completedCount.get(), skippedCount.get(), failedCount.get());
        }
    }

    @Async
    public void processRetryAsync(List<String> filePaths) {
        LOG.info("Bulk embed retry started: {} files", filePaths.size());

        try {
            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            Path rootDir = Paths.get(outputDir);

            for (String fp : filePaths) {
                if (cancelled.get()) break;
                // Retry always re-processes — pass empty completed set
                pool.submit(() -> processOneFile(Paths.get(fp), rootDir, Collections.emptySet(), false));
            }

            pool.shutdown();
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            LOG.error("Bulk embed retry failed", e);
        } finally {
            running.set(false);
            currentFile.set("");
            LOG.info("Bulk embed retry finished: completed={} failed={}",
                    completedCount.get(), failedCount.get());
        }
    }

    // ============================================================
    // Per-file processing
    // ============================================================

    private void processOneFile(Path mdFile, Path rootDir, Set<String> completedPaths, boolean forceReembed) {
        if (cancelled.get()) return;

        String rawFileName = mdFile.getFileName().toString();
        String filePath = rootDir.relativize(mdFile).toString().replace('\\', '/');
        activeFiles.add(rawFileName);
        updateCurrentFile();

        try {
            // Skip if already completed (unless force re-embed)
            if (!forceReembed && completedPaths.contains(filePath)) {
                skippedCount.incrementAndGet();
                return;
            }

            // Read content
            String content = Files.readString(mdFile, StandardCharsets.UTF_8);

            // Resolve display name from embedded comment
            String displayName = rawFileName;
            if (content.startsWith("<!-- file_name:")) {
                int end = content.indexOf("-->");
                if (end > 0) {
                    String name = content.substring("<!-- file_name:".length(), end).trim();
                    if (!name.isEmpty()) displayName = name;
                }
            }

            // Mark IN_PROGRESS in embed_status
            try {
                embedStatusDAO.deleteByFilePath(filePath);
                embedStatusDAO.insert(filePath, displayName, "IN_PROGRESS");
            } catch (Exception e) {
                LOG.warn("Failed to update embed_status for {}: {}", filePath, e.getMessage());
            }

            // Delete any existing chunks (clean slate)
            try {
                documentEmbeddingDAO.deleteByFileName(displayName);
                if (!displayName.equals(rawFileName)) {
                    documentEmbeddingDAO.deleteByFileName(rawFileName);
                }
            } catch (Exception e) {
                LOG.warn("Failed to delete old chunks for {}: {}", displayName, e.getMessage());
            }

            // Chunk the content
            List<Document> finalChunks;
            String fn = displayName;

            if (ReportMarkdownChunker.isRgdReport(content)) {
                List<String> textChunks = reportChunker.chunk(content);
                finalChunks = textChunks.stream()
                        .filter(c -> c.trim().length() >= 50)
                        .map(c -> {
                            Map<String, Object> meta = new HashMap<>();
                            meta.put("filename", fn);
                            return new Document(c, meta);
                        })
                        .toList();
            } else {
                Document doc = new Document(content, Map.of("filename", fn));
                List<Document> preprocessed = preprocessor.preprocessDocuments(List.of(doc));

                if (preprocessed.isEmpty()) {
                    LOG.warn("No usable content after preprocessing: {}", rawFileName);
                    markFailed(filePath, "No usable content after preprocessing");
                    return;
                }

                TokenTextSplitter splitter = TokenTextSplitter.builder()
                        .withChunkSize(1000)
                        .withMinChunkSizeChars(200)
                        .withMinChunkLengthToEmbed(50)
                        .withMaxNumChunks(10000)
                        .withKeepSeparator(true)
                        .build();

                List<Document> split = splitter.apply(preprocessed);
                finalChunks = split.stream()
                        .filter(d -> preprocessor.isQualityChunk(d.getContent()))
                        .toList();
            }

            if (finalChunks.isEmpty()) {
                LOG.warn("No usable chunks from file: {}", rawFileName);
                markFailed(filePath, "No usable chunks produced");
                return;
            }

            // Embed into vector store
            openaiVectorStore.add(finalChunks);

            // Mark COMPLETED
            try {
                embedStatusDAO.updateStatus(filePath, "COMPLETED", null, finalChunks.size());
            } catch (Exception e) {
                LOG.warn("Failed to mark COMPLETED for {}: {}", filePath, e.getMessage());
            }
            completedCount.incrementAndGet();
            LOG.debug("Embedded {} chunks for: {}", finalChunks.size(), displayName);

        } catch (Exception e) {
            LOG.warn("Failed to embed {}: {}", rawFileName, e.getMessage());
            markFailed(filePath, e.getMessage());
        } finally {
            activeFiles.remove(rawFileName);
            updateCurrentFile();
        }
    }

    private void markFailed(String filePath, String errorMessage) {
        failedCount.incrementAndGet();
        failedFiles.add(filePath);
        try {
            embedStatusDAO.updateStatus(filePath, "FAILED", errorMessage, 0);
        } catch (Exception e) {
            LOG.warn("Failed to mark FAILED for {}: {}", filePath, e.getMessage());
        }
    }

    private void updateCurrentFile() {
        if (activeFiles.isEmpty()) {
            currentFile.set("");
        } else {
            currentFile.set(String.join(", ", activeFiles));
        }
    }
}
