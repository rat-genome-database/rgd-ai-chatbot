package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UrlRequest;
import edu.mcw.rgdai.reader.UrlDocumentReader;
import edu.mcw.rgdai.service.DocumentPreprocessor;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
// Commented out - SCGE clinical trials DAO not needed for RGD
// import edu.mcw.scge.dao.DataSourceFactory;
// import edu.mcw.scge.dao.implementation.ClinicalTrailDAO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
public class UrlController {
    private static final Logger LOG = LoggerFactory.getLogger(UrlController.class);

    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final DocumentEmbeddingOpenAIRepository repository;

    public UrlController(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                         DocumentPreprocessor preprocessor,
                         DocumentEmbeddingOpenAIRepository repository){
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.repository = repository;
    }

    @PostMapping("/process-url")
    public ResponseEntity<?> processUrl(@RequestBody UrlRequest urlRequest) {
        String urlString = urlRequest.getUrl();
        LOG.info("Processing URL for OpenAI: {}", urlString);

        // Validate URL
        URL url;
        try {
            url = new URL(urlString);
        } catch (MalformedURLException e) {
            LOG.error("Invalid URL: {}", urlString, e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Invalid URL format");
            return ResponseEntity.badRequest().body(response);
        }

        return processUrlInternal(urlString);
    }

    /* Commented out for RGD - clinical trials loading uses SCGE-specific DAO
    @PostMapping("/load-clinical-trials")
    public ResponseEntity<?> loadClinicalTrials(HttpServletRequest request) {
        LOG.info("Starting clinical trials loading process");

        try {
            // Get NCT IDs from CurDS database using custom datasource
            DataSource curationDS = DataSourceFactory.getInstance().getScgePlatformDataSource();
            ClinicalTrailDAO dao = new ClinicalTrailDAO(curationDS);
            List<String> nctIds = dao.getAllNctIds();
            LOG.info("Retrieved {} NCT IDs from CurDS database", nctIds.size());

            List<String> processed = new ArrayList<>();
            List<String> failed = new ArrayList<>();
            List<String> overwritten = new ArrayList<>();

            for (String nctId : nctIds) {
                try {
                    if (nctId == null || nctId.trim().isEmpty()) {
                        LOG.warn("Skipping empty nctId");
                        continue;
                    }

                    nctId = nctId.trim();
                    String serverName = request.getServerName();
                    String baseUrl = serverName.contains("stage") ? "https://stage.scge.mcw.edu" : "https://scge.mcw.edu";
                    String url = baseUrl + "/platform/data/report/clinicalTrials/" + nctId;

                    LOG.info("Processing trial: {}", nctId);

                    List<DocumentEmbeddingOpenAI> existing = repository.findByFileName("CLINICAL TRIAL: " + nctId);
                    boolean isOverwrite = !existing.isEmpty();

                    if (isOverwrite) {
                        for (DocumentEmbeddingOpenAI doc : existing) {
                            repository.delete(doc);
                        }
                        LOG.info("Deleted {} existing entries for trial: {}", existing.size(), nctId);
                        overwritten.add(nctId);
                    }

                    ResponseEntity<?> result = processUrlInternal(url);

                    if (result.getStatusCode().is2xxSuccessful()) {
                        processed.add(nctId);
                        LOG.info("Successfully processed trial: {} ({})", nctId, isOverwrite ? "overwritten" : "new");
                    } else {
                        failed.add(nctId);
                        LOG.error("Failed to process trial: {}", nctId);
                    }

                } catch (Exception e) {
                    String safeNctId = nctId != null ? nctId.trim() : "unknown";
                    failed.add(safeNctId);
                    LOG.error("Exception processing trial: {}", safeNctId, e);
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("total", nctIds.size());
            response.put("processed", processed.size());
            response.put("overwritten", overwritten.size());
            response.put("failed", failed.size());
            response.put("processedList", processed);
            response.put("overwrittenList", overwritten);
            response.put("failedList", failed);

            LOG.info("Clinical trials processing complete. Total: {}, Processed: {}, Overwritten: {}, Failed: {}",
                    nctIds.size(), processed.size(), overwritten.size(), failed.size());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LOG.error("Error during clinical trials loading", e);
            Map<String, String> errorResponse = new HashMap<>();
            errorResponse.put("error", "Failed to load clinical trials: " + e.getMessage());
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
    */


    private ResponseEntity<?> processUrlInternal(String urlString) {
        try {
            // Fetch content from URL
            UrlDocumentReader documentReader = new UrlDocumentReader(urlString);
            List<Document> documents = documentReader.get();

            if (documents.isEmpty()) {
                LOG.error("Failed to fetch content from URL: {}", urlString);
                Map<String, String> response = new HashMap<>();
                response.put("error", "Failed to fetch content from the provided URL");
                return ResponseEntity.badRequest().body(response);
            }

            // Fix the metadata issue - add filename by creating new documents with mutable metadata
            List<Document> documentsWithFilename = documents.stream()
                    .map(doc -> {
                        Map<String, Object> mutableMetadata = new HashMap<>(doc.getMetadata());
                        mutableMetadata.put("filename", extractFilenameFromUrl(urlString));
                        return new Document(doc.getContent(), mutableMetadata);
                    })
                    .collect(Collectors.toList());

            documents = documentsWithFilename;

            // STEP 1: Universal preprocessing for ANY document type
            List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
            LOG.debug("Preprocessed into {} clean documents", preprocessedDocs.size());

            if (preprocessedDocs.isEmpty()) {
                LOG.error("No usable content after preprocessing");
                throw new RuntimeException("Document preprocessing failed - no usable content found");
            }

            // STEP 2: Split into chunks with correct Spring AI settings
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(800)                // Target chunk size in tokens
                    .withMinChunkSizeChars(200)        // Minimum characters per chunk
                    .withMinChunkLengthToEmbed(50)     // Minimum length to embed
                    .withMaxNumChunks(10000)           // Maximum number of chunks
                    .withKeepSeparator(true)           // Keep separators for readability
                    .build();

            List<Document> splitDocuments = splitter.apply(preprocessedDocs);
            LOG.debug("Split into {} chunks after preprocessing", splitDocuments.size());

            // Add to OpenAI vector store
            openaiVectorStore.add(splitDocuments);
            LOG.debug("Successfully added {} URL chunks to OpenAI vector store", splitDocuments.size());

            Map<String, Object> response = new HashMap<>();
            response.put("url", urlString);
            response.put("title", documents.get(0).getMetadata().getOrDefault("title", "Unknown"));
            response.put("chunkCount", splitDocuments.size());
            response.put("vectorStore", "OpenAI");

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            LOG.error("Error processing URL: {}", urlString, e);
            Map<String, String> response = new HashMap<>();
            response.put("error", "Failed to process URL: " + e.getMessage());
            return ResponseEntity.status(500).body(response);
        }
    }

    private String extractFilenameFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            String path = url.getPath();

            // Check if this is a clinical trial URL
            boolean isClinicalTrialUrl = urlString.contains("/clinicalTrials/report/") ||
                                         urlString.contains("/report/clinicalTrials/");

            String filename;
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                String[] pathParts = path.split("/");
                String lastPart = pathParts[pathParts.length - 1];
                if (!lastPart.isEmpty()) {
                    filename = lastPart;
                } else {
                    filename = url.getHost().replaceAll("\\.", "_");
                }
            } else {
                filename = url.getHost().replaceAll("\\.", "_");
            }

            // For clinical trials, prepend "CLINICAL TRIAL: " to the NCTID
            // For other URLs, append the full URL for uniqueness
            if (isClinicalTrialUrl) {
                return "CLINICAL TRIAL: " + filename;  // "CLINICAL TRIAL: NCT06285643"
            } else {
                return filename + ":" + urlString;  // "page_name:https://example.com/page"
            }

        } catch (MalformedURLException e) {
            return "webpage_" + System.currentTimeMillis() + ":" + urlString;
        }
    }
}
