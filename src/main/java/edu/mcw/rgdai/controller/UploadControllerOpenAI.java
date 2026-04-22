package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UploadResponse;
import edu.mcw.rgdai.service.DocumentPreprocessor;
import edu.mcw.rgdai.service.ReportMarkdownChunker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/upload-openai")  //
public class UploadControllerOpenAI {
    private static final Logger LOG = LoggerFactory.getLogger(UploadControllerOpenAI.class);
    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;
    private final ReportMarkdownChunker reportChunker;

    public UploadControllerOpenAI(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                                  DocumentPreprocessor preprocessor,
                                  ReportMarkdownChunker reportChunker) {
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        this.reportChunker = reportChunker;
        LOG.info("UploadControllerOpenAI initialized with OpenAI vector store and report chunker");
    }

    @PostMapping  // Now this maps to POST /upload-openai
    public UploadResponse upload(@RequestParam("file") MultipartFile file) throws IOException {
        LOG.info("Starting OpenAI file upload: {}", file.getOriginalFilename());

        // Create temp directory and save file
        Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "rgdai-uploads-openai");
        Files.createDirectories(tempDir);
        Path destinationFile = tempDir.resolve(file.getOriginalFilename());

        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("File saved to: {}", destinationFile);
        }

        // Check for embedded display name: <!-- file_name: ... -->
        // RGD report converter embeds this for proper DB storage (preserves /, full names, etc.)
        // Non-report files won't have this, so we fall back to the original filename
        String fileName = file.getOriginalFilename();
        String rawContent = Files.readString(destinationFile, StandardCharsets.UTF_8);
        if (rawContent.startsWith("<!-- file_name:")) {
            int end = rawContent.indexOf("-->");
            if (end > 0) {
                String displayName = rawContent.substring("<!-- file_name:".length(), end).trim();
                if (!displayName.isEmpty()) {
                    fileName = displayName;
                    LOG.info("Using embedded display name: {}", fileName);
                }
            }
        }

        List<Document> finalChunks;

        if (ReportMarkdownChunker.isRgdReport(rawContent)) {
            // ====== RGD Report: section-aware chunking ======
            // Reports are already clean markdown from our converter — bypass Tika and preprocessor.
            // Section-aware chunker preserves heading context, table headers, and line boundaries.
            LOG.info("Detected RGD report — using section-aware chunking");

            List<String> textChunks = reportChunker.chunk(rawContent);
            LOG.info("Section-aware chunking produced {} chunks", textChunks.size());

            String fn = fileName;
            finalChunks = textChunks.stream()
                    .filter(c -> c.trim().length() >= 50) // min length only; skip formatting % check
                    .map(c -> {
                        Map<String, Object> meta = new HashMap<>();
                        meta.put("filename", fn);
                        return new Document(c, meta);
                    })
                    .toList();

            // Log sample chunks
            for (int i = 0; i < Math.min(3, finalChunks.size()); i++) {
                String content = finalChunks.get(i).getContent();
                LOG.info("Report chunk {}: {} chars - {}...",
                        i + 1, content.length(),
                        content.substring(0, Math.min(150, content.length())).replaceAll("\n", " "));
            }

            LOG.info("Report chunks after length filter: {}", finalChunks.size());
        } else {
            // ====== Normal file: Tika + preprocessor + TokenTextSplitter ======
            LOG.info("Non-report file — using standard chunking pipeline");

            TikaDocumentReader documentReader = new TikaDocumentReader(destinationFile.toUri().toString());
            List<Document> documents = documentReader.get();
            String finalFileName = fileName;
            documents.forEach(doc -> {
                doc.getMetadata().put("filename", finalFileName);
            });
            LOG.info("Read document with {} characters, file_name: {}", documents.get(0).getContent().length(), fileName);

            // Preprocessing: clean HTML, whitespace, etc.
            List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
            LOG.info("Preprocessed into {} clean documents", preprocessedDocs.size());

            if (preprocessedDocs.isEmpty()) {
                LOG.error("No usable content after preprocessing");
                throw new RuntimeException("Document preprocessing failed - no usable content found");
            }

            // Split into chunks
            TokenTextSplitter splitter = TokenTextSplitter.builder()
                    .withChunkSize(1000)               // Target chunk size in tokens
                    .withMinChunkSizeChars(200)        // Minimum characters per chunk
                    .withMinChunkLengthToEmbed(50)     // Minimum length to embed
                    .withMaxNumChunks(10000)           // Maximum number of chunks
                    .withKeepSeparator(true)           // Keep separators for readability
                    .build();

            List<Document> splitDocuments = splitter.apply(preprocessedDocs);
            LOG.info("Split into {} chunks after preprocessing", splitDocuments.size());

            // Log sample of processed content
            for (int i = 0; i < Math.min(3, splitDocuments.size()); i++) {
                String content = splitDocuments.get(i).getContent();
                LOG.info("Sample chunk {}: {} chars - {}...",
                        i + 1, content.length(),
                        content.substring(0, Math.min(150, content.length())).replaceAll("\n", " "));
            }

            // Quality check - filter out poor quality chunks
            finalChunks = splitDocuments.stream()
                    .filter(doc -> preprocessor.isQualityChunk(doc.getContent()))
                    .toList();

            LOG.info("Quality filtered: {} chunks retained out of {}", finalChunks.size(), splitDocuments.size());
        }

        if (finalChunks.isEmpty()) {
            LOG.error("No chunks to store after processing");
            throw new RuntimeException("No usable content found after processing");
        }

        // Add to OpenAI vector store
        openaiVectorStore.add(finalChunks);
        LOG.info("Successfully added {} chunks to OpenAI vector store", finalChunks.size());

        // Clean up temp file
        try {
            Files.deleteIfExists(destinationFile);
        } catch (IOException e) {
            LOG.warn("Failed to delete temp file: {}", e.getMessage());
        }

        return new UploadResponse(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }
}
