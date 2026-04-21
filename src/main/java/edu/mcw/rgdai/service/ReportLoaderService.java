package edu.mcw.rgdai.service;

import edu.mcw.rgd.dao.DataSourceFactory;
import edu.mcw.rgd.dao.impl.ChatbotDAOs;
import edu.mcw.rgd.dao.impl.ReportLoadStatusDAO;
import edu.mcw.rgd.dao.spring.IntStringMapQuery;
import edu.mcw.rgd.datamodel.Map;
import edu.mcw.rgd.datamodel.ReportLoadStatus;
import edu.mcw.rgd.datamodel.SpeciesType;
import edu.mcw.rgd.services.RgdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bulk loader: fetches RGD IDs per report-type + species + assembly, then
 * converts each report page to markdown and tracks per-record status.
 */
@Service
public class ReportLoaderService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportLoaderService.class);

    private final ReportConverterService converter;

    @Lazy
    @Autowired
    private ReportLoaderService self;

    @Value("${report.loader.base-url}")
    private String baseUrl;

    @Value("${report.loader.output-dir}")
    private String outputDir;

    @Value("${report.loader.threads:5}")
    private int threadCount;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> currentSymbol = new AtomicReference<>("");
    private final Set<String> activeSymbols = ConcurrentHashMap.newKeySet();

    public ReportLoaderService(ReportConverterService converter) {
        this.converter = converter;
    }

    // ============================================================
    // DataSource routing
    // ============================================================

    /**
     * Production and Pipelines tomcats use the default jdbc/rgd2 DataSource.
     * Everywhere else (isDev hansen, test laptops, local dev) uses reed
     * (jdbc/reed) so local dev has real RGD data without a local Oracle.
     */
    private DataSource oracleDs() throws Exception {
        boolean onServer = RgdContext.isProduction() || RgdContext.isPipelines();
        return onServer
                ? DataSourceFactory.getInstance().getDataSource()
                : DataSourceFactory.getInstance().getChatbotOracleDataSource();
    }

    // ============================================================
    // Species list (per report type)
    // ============================================================

    public List<java.util.Map<String, Object>> getSpecies(String reportType) {
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        switch (reportType) {
            case "gene":
                for (Integer k : SpeciesType.getSpeciesTypeKeys()) {
                    if (k != null && k != SpeciesType.ALL && SpeciesType.isSearchable(k)) {
                        out.add(species(k));
                    }
                }
                out.sort(Comparator.comparing(m -> (String) m.get("name")));
                break;
            case "qtl":
            case "marker":
                out.add(species(SpeciesType.RAT));
                out.add(species(SpeciesType.HUMAN));
                out.add(species(SpeciesType.MOUSE));
                break;
            default:
                // strain / reference / project — no species filter
                break;
        }
        return out;
    }

    private java.util.Map<String, Object> species(int key) {
        java.util.Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", SpeciesType.getCommonName(key));
        return m;
    }

    // ============================================================
    // Assemblies (per species)
    // ============================================================

    public List<java.util.Map<String, Object>> getAssemblies(int speciesKey) throws Exception {
        ChatbotDAOs.Map mapDao = new ChatbotDAOs.Map(oracleDs());
        List<Map> maps = mapDao.getMaps(speciesKey);
        List<java.util.Map<String, Object>> out = new ArrayList<>();
        if (maps == null) return out;

        for (Map m : maps) {
            if (!"bp".equalsIgnoreCase(m.getUnit())) continue; // skip cM etc.
            java.util.Map<String, Object> row = new LinkedHashMap<>();
            row.put("mapKey", m.getKey());
            row.put("name", m.getName());
            row.put("primary", m.isPrimaryRefAssembly());
            row.put("source", m.getSource());
            out.add(row);
        }
        // primary first, then by name
        out.sort((a, b) -> {
            boolean pa = (boolean) a.get("primary"), pb = (boolean) b.get("primary");
            if (pa != pb) return pa ? -1 : 1;
            return ((String) a.get("name")).compareTo((String) b.get("name"));
        });
        return out;
    }

    // ============================================================
    // Count
    // ============================================================

    public int countReports(String reportType, int speciesKey, int mapKey) throws Exception {
        DataSource ds = oracleDs();
        switch (reportType) {
            case "gene": {
                ChatbotDAOs.Gene dao = new ChatbotDAOs.Gene(ds);
                return (mapKey > 0) ? dao.countActiveGenesByMapKey(speciesKey, mapKey)
                                    : dao.countActiveGenes(speciesKey);
            }
            case "qtl": {
                ChatbotDAOs.QTL dao = new ChatbotDAOs.QTL(ds);
                return (mapKey > 0) ? dao.countActiveQTLsByMapKey(speciesKey, mapKey)
                                    : dao.countActiveQTLs(speciesKey);
            }
            case "marker": {
                ChatbotDAOs.SSLP dao = new ChatbotDAOs.SSLP(ds);
                return (mapKey > 0) ? dao.countActiveSSLPsByMapKey(speciesKey, mapKey)
                                    : dao.countActiveSSLPs(speciesKey);
            }
            case "strain":
                return new ChatbotDAOs.Strain(ds).countActiveStrains();
            case "reference":
                return new ChatbotDAOs.Reference(ds).countActiveReferences();
            case "project":
                return new ChatbotDAOs.Project(ds).countAllProjects();
            default:
                throw new IllegalArgumentException("Unsupported report type: " + reportType);
        }
    }

    // ============================================================
    // RGD ID fetching (per report type + species + mapKey)
    // ============================================================

    /** Returns list of MapPair (rgdId, symbol/name) using lightweight queries. */
    private List<IntStringMapQuery.MapPair> fetchRgdIds(String reportType, int speciesKey, int mapKey) throws Exception {
        DataSource ds = oracleDs();
        switch (reportType) {
            case "gene": {
                ChatbotDAOs.Gene dao = new ChatbotDAOs.Gene(ds);
                return (mapKey > 0) ? dao.getActiveGeneIdsByMapKey(speciesKey, mapKey)
                                    : dao.getActiveGeneIds(speciesKey);
            }
            case "qtl": {
                ChatbotDAOs.QTL dao = new ChatbotDAOs.QTL(ds);
                return (mapKey > 0) ? dao.getActiveQTLIdsByMapKey(speciesKey, mapKey)
                                    : dao.getActiveQTLIds(speciesKey);
            }
            case "marker": {
                ChatbotDAOs.SSLP dao = new ChatbotDAOs.SSLP(ds);
                return (mapKey > 0) ? dao.getActiveSSLPIdsByMapKey(speciesKey, mapKey)
                                    : dao.getActiveSSLPIds(speciesKey);
            }
            case "strain":
                return new ChatbotDAOs.Strain(ds).getActiveStrainIds();
            case "reference":
                return new ChatbotDAOs.Reference(ds).getActiveReferenceIds();
            case "project":
                return new ChatbotDAOs.Project(ds).getAllProjectIds();
            default:
                throw new IllegalArgumentException("Unsupported report type: " + reportType);
        }
    }

    // ============================================================
    // Start / cancel / progress
    // ============================================================

    public java.util.Map<String, Object> startBatch(
            String reportType, int speciesKey, int mapKey, boolean reset) {

        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Bulk load already running");
        }

        cancelled.set(false);
        currentSymbol.set("Preparing...");

        // All heavy work happens in the async thread
        self.processBatchAsync(reportType, speciesKey, mapKey, reset);

        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "started");
        resp.put("reportType", reportType);
        resp.put("speciesKey", speciesKey);
        return resp;
    }

    public java.util.Map<String, Object> retryFailed(String reportType, int speciesKey, int mapKey) throws Exception {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Bulk load already running");
        }

        cancelled.set(false);
        currentSymbol.set("Resetting failed records...");

        ReportLoadStatusDAO statusDAO = new ReportLoadStatusDAO();
        int reset = statusDAO.resetFailed(reportType, speciesKey, mapKey);
        LOG.info("Retry: reset {} failed records to pending for type={} species={} mapKey={}", reset, reportType, speciesKey, mapKey);

        self.processBatchAsync(reportType, speciesKey, mapKey, false);

        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "retrying");
        resp.put("reset", reset);
        return resp;
    }

    public java.util.Map<String, Object> cancel() {
        cancelled.set(true);
        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", "cancelling");
        resp.put("running", running.get());
        return resp;
    }

    public java.util.Map<String, Object> getProgress(String reportType, int speciesKey, int mapKey) throws Exception {
        ReportLoadStatusDAO statusDAO = new ReportLoadStatusDAO();
        int total, completed, failed, pending, processing;

        if (mapKey > 0) {
            total = statusDAO.getTotalCountByMapKey(reportType, speciesKey, mapKey);
            completed = statusDAO.getCountByStatusAndMapKey(reportType, speciesKey, mapKey, "completed");
            failed = statusDAO.getCountByStatusAndMapKey(reportType, speciesKey, mapKey, "failed");
            pending = statusDAO.getCountByStatusAndMapKey(reportType, speciesKey, mapKey, "pending");
            processing = statusDAO.getCountByStatusAndMapKey(reportType, speciesKey, mapKey, "processing");
        } else {
            total = statusDAO.getTotalCount(reportType, speciesKey);
            completed = statusDAO.getCountByStatus(reportType, speciesKey, "completed");
            failed = statusDAO.getCountByStatus(reportType, speciesKey, "failed");
            pending = statusDAO.getCountByStatus(reportType, speciesKey, "pending");
            processing = statusDAO.getCountByStatus(reportType, speciesKey, "processing");
        }

        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("total", total);
        resp.put("completed", completed);
        resp.put("failed", failed);
        resp.put("pending", pending);
        resp.put("processing", processing);
        resp.put("running", running.get());
        resp.put("currentSymbol", currentSymbol.get());
        return resp;
    }

    public java.util.Map<String, Object> getActiveBatch() throws Exception {
        java.util.Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("running", running.get());

        ReportLoadStatusDAO statusDAO = new ReportLoadStatusDAO();
        ReportLoadStatus active = statusDAO.getActiveBatchInfo();
        if (active != null) {
            resp.put("active", true);
            resp.put("reportType", active.getReportType());
            resp.put("speciesKey", active.getSpeciesKey());
            resp.put("mapKey", active.getMapKey());
            resp.put("currentSymbol", currentSymbol.get());
        } else {
            resp.put("active", false);
        }
        return resp;
    }

    // ============================================================
    // @Async processing loop
    // ============================================================

    @Async
    public void processBatchAsync(String reportType, int speciesKey, int mapKey, boolean reset) {
        LOG.info("Batch async started: type={} species={} mapKey={} reset={}", reportType, speciesKey, mapKey, reset);

        // Resolve assembly name for subdirectory (empty for types without assembly or "All")
        String assemblyName = "";
        if (mapKey > 0) {
            try {
                ChatbotDAOs.Map mapDao = new ChatbotDAOs.Map(oracleDs());
                for (edu.mcw.rgd.datamodel.Map m : mapDao.getMaps(speciesKey)) {
                    if (m.getKey() == mapKey) {
                        assemblyName = m.getName().replaceAll("[^a-zA-Z0-9_.-]", "_");
                        break;
                    }
                }
            } catch (Exception e) {
                LOG.warn("Could not resolve assembly name for mapKey={}, using mapKey as folder", mapKey);
                assemblyName = String.valueOf(mapKey);
            }
        }

        ReportLoadStatusDAO statusDAO = new ReportLoadStatusDAO();
        try {
            // Phase 1: Reset if requested
            if (reset) {
                currentSymbol.set("Resetting existing records...");
                int deleted = (mapKey > 0)
                        ? statusDAO.deleteByTypeSpeciesAndMapKey(reportType, speciesKey, mapKey)
                        : statusDAO.deleteByTypeAndSpecies(reportType, speciesKey);
                LOG.info("Reset: deleted {} existing records for type={} species={} mapKey={}",
                        deleted, reportType, speciesKey, mapKey);
            }

            // Phase 2 & 3: Fetch IDs from Oracle and insert pending records (skip on resume)
            if (reset || statusDAO.getTotalCount(reportType, speciesKey) == 0) {
                currentSymbol.set("Fetching " + reportType + " IDs from RGD...");
                List<IntStringMapQuery.MapPair> ids = fetchRgdIds(reportType, speciesKey, mapKey);
                LOG.info("Fetched {} IDs from Oracle", ids.size());

                currentSymbol.set("Checking for already completed records...");
                Set<Integer> completed = new HashSet<>(statusDAO.getCompletedRgdIds(reportType));
                List<ReportLoadStatus> toInsert = new ArrayList<>();
                for (IntStringMapQuery.MapPair pair : ids) {
                    if (completed.contains(pair.keyValue)) continue;
                    String symbol = pair.stringValue == null ? "" : pair.stringValue;
                    ReportLoadStatus r = new ReportLoadStatus();
                    r.setRgdId(pair.keyValue);
                    r.setReportType(reportType);
                    r.setSpeciesKey(speciesKey);
                    r.setMapKey(mapKey);
                    r.setSymbol(symbol);
                    r.setStatus("pending");
                    toInsert.add(r);
                }
                if (!toInsert.isEmpty()) {
                    currentSymbol.set("Inserting " + toInsert.size() + " records into database...");
                    statusDAO.insertBatch(toInsert);
                }
                LOG.info("Inserted {} new pending records ({} already completed)", toInsert.size(), completed.size());
            } else {
                LOG.info("Resume mode — skipping Oracle fetch, using existing pending/processing records");
            }

            // Phase 4: Process pending records in parallel (filter by mapKey if specific assembly)
            List<ReportLoadStatus> pendingList = (mapKey > 0)
                    ? statusDAO.getPendingByTypeSpeciesAndMapKey(reportType, speciesKey, mapKey)
                    : statusDAO.getPendingByTypeAndSpecies(reportType, speciesKey);
            LOG.info("Processing {} pending records (mapKey={}) with {} threads",
                    pendingList.size(), mapKey, threadCount);

            ExecutorService pool = Executors.newFixedThreadPool(threadCount);
            for (ReportLoadStatus r : pendingList) {
                if (cancelled.get()) {
                    LOG.info("Batch paused — stopping submission at rgdId={}", r.getRgdId());
                    break;
                }
                String asmName = assemblyName; // effectively final for lambda
                pool.submit(() -> processOneRecord(r, reportType, asmName, statusDAO));
            }
            pool.shutdown();
            try {
                pool.awaitTermination(Long.MAX_VALUE, TimeUnit.HOURS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        } catch (Exception e) {
            LOG.error("Batch failed", e);
        } finally {
            running.set(false);
            currentSymbol.set("");
            LOG.info("Batch finished: type={} species={}", reportType, speciesKey);
        }
    }

    // ============================================================
    // Per-record processing (called from thread pool)
    // ============================================================

    private void processOneRecord(ReportLoadStatus r, String reportType, String assemblyName, ReportLoadStatusDAO statusDAO) {
        if (cancelled.get()) return;

        String symbol = r.getSymbol() == null ? String.valueOf(r.getRgdId()) : r.getSymbol();
        activeSymbols.add(symbol);
        updateCurrentSymbol();
        try {
            statusDAO.updateStatus(r.getReportLoadStatusId(), "processing", null, null);

            String url = buildUrl(reportType, r.getRgdId());
            String html = converter.fetchHtml(url);
            ReportConverterService.ConversionResult result = converter.convert(html, url);
            saveHtml(result, reportType, assemblyName);
            String fileName = saveMarkdown(result, reportType, assemblyName);

            statusDAO.updateStatus(r.getReportLoadStatusId(), "completed", fileName, null);
        } catch (Exception e) {
            LOG.warn("Failed rgdId={} symbol={}: {}", r.getRgdId(), symbol, e.getMessage());
            try {
                statusDAO.updateStatus(r.getReportLoadStatusId(), "failed", null, e.getMessage());
            } catch (Exception ignore) { /* swallow */ }
        } finally {
            activeSymbols.remove(symbol);
            updateCurrentSymbol();
        }
    }

    private void updateCurrentSymbol() {
        if (activeSymbols.isEmpty()) {
            currentSymbol.set("");
        } else {
            currentSymbol.set(String.join(", ", activeSymbols));
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private String buildUrl(String reportType, int rgdId) {
        String path;
        switch (reportType) {
            case "gene":      path = "/rgdweb/report/gene/main.html"; break;
            case "qtl":       path = "/rgdweb/report/qtl/main.html"; break;
            case "strain":    path = "/rgdweb/report/strain/main.html"; break;
            case "marker":    path = "/rgdweb/report/sslp/main.html"; break;
            case "reference": path = "/rgdweb/report/reference/main.html"; break;
            case "project":   path = "/rgdweb/report/project/main.html"; break;
            default: throw new IllegalArgumentException("Unsupported report type: " + reportType);
        }
        return baseUrl + path + "?id=" + rgdId;
    }

    private String saveMarkdown(ReportConverterService.ConversionResult result, String reportType, String assemblyName) throws Exception {
        String safeSymbol = (result.metadata.entityName == null || result.metadata.entityName.isEmpty()
                ? "unknown" : result.metadata.entityName).replaceAll("[^a-zA-Z0-9_.-]", "_");
        String rgdId = (result.metadata.rgdId == null || result.metadata.rgdId.isEmpty())
                ? "noid" : result.metadata.rgdId;
        String fileName = reportType + "_" + safeSymbol + "_" + rgdId + ".md";

        Path typeDir = Paths.get(outputDir, reportType);
        if (!assemblyName.isEmpty()) typeDir = typeDir.resolve(assemblyName);
        Files.createDirectories(typeDir);
        Files.writeString(typeDir.resolve(fileName), result.markdown);
        return fileName;
    }

    private void saveHtml(ReportConverterService.ConversionResult result, String reportType, String assemblyName) throws Exception {
        String safeSymbol = (result.metadata.entityName == null || result.metadata.entityName.isEmpty()
                ? "unknown" : result.metadata.entityName).replaceAll("[^a-zA-Z0-9_.-]", "_");
        String rgdId = (result.metadata.rgdId == null || result.metadata.rgdId.isEmpty())
                ? "noid" : result.metadata.rgdId;
        String fileName = reportType + "_" + safeSymbol + "_" + rgdId + ".html";

        Path htmlDir = Paths.get(outputDir).getParent().resolve("html").resolve(reportType);
        if (!assemblyName.isEmpty()) htmlDir = htmlDir.resolve(assemblyName);
        Files.createDirectories(htmlDir);
        Files.writeString(htmlDir.resolve(fileName), result.processedHtml);
    }
}
