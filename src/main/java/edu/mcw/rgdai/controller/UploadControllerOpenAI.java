package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.UploadResponse;
import edu.mcw.rgdai.service.DocumentPreprocessor;
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
import java.util.List;

@RestController
@RequestMapping("/upload-openai")  //
public class UploadControllerOpenAI {
    private static final Logger LOG = LoggerFactory.getLogger(UploadControllerOpenAI.class);
    private final VectorStore openaiVectorStore;
    private final DocumentPreprocessor preprocessor;

    public UploadControllerOpenAI(@Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
                                  DocumentPreprocessor preprocessor) {
        this.openaiVectorStore = openaiVectorStore;
        this.preprocessor = preprocessor;
        LOG.info("UploadControllerOpenAI initialized with OpenAI vector store");
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

        // Read document
        TikaDocumentReader documentReader = new TikaDocumentReader(destinationFile.toUri().toString());
        List<Document> documents = documentReader.get();
        String finalFileName = fileName;
        documents.forEach(doc -> {
            doc.getMetadata().put("filename", finalFileName);
        });
        LOG.info("Read document with {} characters, file_name: {}", documents.get(0).getContent().length(), fileName);

        // STEP 1: Universal preprocessing for ANY document type
        List<Document> preprocessedDocs = preprocessor.preprocessDocuments(documents);
        LOG.info("Preprocessed into {} clean documents", preprocessedDocs.size());

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
        LOG.info("Split into {} chunks after preprocessing", splitDocuments.size());

        // Log sample of processed content
        for (int i = 0; i < Math.min(3, splitDocuments.size()); i++) {
            String content = splitDocuments.get(i).getContent();
            LOG.info("Sample chunk {}: {} chars - {}...",
                    i + 1, content.length(),
                    content.substring(0, Math.min(150, content.length())).replaceAll("\n", " "));
        }

        // STEP 3: Universal quality check - filter out poor quality chunks
        List<Document> qualityChunks = splitDocuments.stream()
                .filter(doc -> preprocessor.isQualityChunk(doc.getContent()))
                .toList();

        LOG.info("Quality filtered: {} chunks retained out of {}", qualityChunks.size(), splitDocuments.size());

        if (qualityChunks.isEmpty()) {
            LOG.error("No quality chunks after filtering");
            throw new RuntimeException("No quality content found after processing");
        }

        // STEP 4: Add to OpenAI vector store
        openaiVectorStore.add(qualityChunks);
        LOG.info(" Successfully added {} chunks to OpenAI vector store", qualityChunks.size());

        // Clean up temp file
        try {
            Files.deleteIfExists(destinationFile);
        } catch (IOException e) {
            LOG.warn("Failed to delete temp file: {}", e.getMessage());
        }

        return new UploadResponse(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }
}