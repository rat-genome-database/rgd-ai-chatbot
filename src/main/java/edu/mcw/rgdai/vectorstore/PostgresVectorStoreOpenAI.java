package edu.mcw.rgdai.vectorstore;

import com.pgvector.PGvector;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.repository.DocumentEmbeddingProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

//@Component
public class PostgresVectorStoreOpenAI implements VectorStore {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresVectorStoreOpenAI.class);
    private final DocumentEmbeddingOpenAIRepository repository;
    private final EmbeddingModel embeddingModel;

    @PersistenceContext
    private EntityManager entityManager;

    public PostgresVectorStoreOpenAI(DocumentEmbeddingOpenAIRepository repository, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        LOG.info("Adding {} documents to OpenAI vector store", documents.size());

        for (Document doc : documents) {
            try {
                // Generate embedding for the document content
                float[] embedding = embeddingModel.embed(List.of(doc.getContent())).get(0);

                // Create and save the document embedding
                DocumentEmbeddingOpenAI docEmbedding = new DocumentEmbeddingOpenAI();
                docEmbedding.setChunk(doc.getContent());
                docEmbedding.setEmbedding(new PGvector(embedding));
                docEmbedding.setFileName(doc.getMetadata().getOrDefault("filename", "unknown").toString());
                docEmbedding.setCreatedAt(LocalDateTime.now());

                repository.save(docEmbedding);
                LOG.debug("Saved document chunk: {} characters from {}",
                        doc.getContent().length(), docEmbedding.getFileName());

            } catch (Exception e) {
                LOG.error("Failed to add document to OpenAI vector store: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to add document to OpenAI vector store", e);
            }
        }

        LOG.info("Successfully added all {} documents to OpenAI vector store", documents.size());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> similaritySearch(SearchRequest request) {
        LOG.info("Starting OpenAI similarity search for query: '{}'", request.getQuery());
        LOG.info("Search parameters - TopK: {}, Similarity threshold: {}",
                request.getTopK(), request.getSimilarityThreshold());

        try {
            // Increase HNSW ef_search for better recall on 2M+ vectors (default 40 misses results)
            entityManager.createNativeQuery("SET hnsw.ef_search = 400").executeUpdate();

            // Generate embedding for the search query
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
            float[] queryEmbedding = response.getResults().get(0).getOutput();
            LOG.debug("Generated query embedding vector of size: {}", queryEmbedding.length);

            // Lightweight query — no embedding column transferred
            List<DocumentEmbeddingProjection> nearest;
            if (request.getSimilarityThreshold() > 0) {
                nearest = repository.findNearestLightWithThreshold(
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold());
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestLight(queryEmbedding, request.getTopK());
            }

            LOG.info("Found {} documents in OpenAI database", nearest.size());

            // Convert to Document objects
            List<Document> results = nearest.stream()
                    .map(p -> {
                        Map<String, Object> metadata = Map.of(
                                "filename", p.getFileName(),
                                "id", p.getId(),
                                "created_at", p.getCreatedAt()
                        );
                        return new Document(p.getChunk(), metadata);
                    })
                    .collect(Collectors.toList());

            // Log some details about the returned documents
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                Document doc = results.get(i);
                LOG.debug("Result {}: {} characters from {}",
                        i + 1, doc.getContent().length(),
                        doc.getMetadata().get("filename"));
            }

            LOG.info("Returning {} documents from OpenAI similarity search", results.size());
            return results;

        } catch (Exception e) {
            LOG.error("Error during OpenAI similarity search: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI similarity search failed", e);
        }
    }

    @Override
    public Optional<Boolean> delete(List<String> ids) {
        LOG.warn("Delete operation called but not implemented");
        throw new UnsupportedOperationException("Delete operation not implemented");
    }

    // Additional helper method to check vector store health
    public long getDocumentCount() {
        long count = repository.count();
        LOG.info("OpenAI vector store contains {} documents", count);
        return count;
    }

    // Method to get unique filenames in the vector store
    public List<String> getAvailableFiles() {
        return repository.findDistinctFileNames();
    }

    /**
     * Enhanced similarity search that includes DB-computed similarity scores in metadata.
     * Uses lightweight projection — no embedding vectors transferred from DB.
     */
    @Transactional(readOnly = true)
    public List<Document> similaritySearchWithScores(SearchRequest request) {
        LOG.info("Starting OpenAI similarity search WITH SCORES for query: '{}'", request.getQuery());
        LOG.info("Search parameters - TopK: {}, Similarity threshold: {}",
                request.getTopK(), request.getSimilarityThreshold());

        try {
            // Increase HNSW ef_search for better recall on 2M+ vectors (default 40 misses results)
            entityManager.createNativeQuery("SET hnsw.ef_search = 400").executeUpdate();

            // Generate embedding for the search query
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
            float[] queryEmbedding = response.getResults().get(0).getOutput();
            LOG.debug("Generated query embedding vector of size: {}", queryEmbedding.length);

            // Lightweight query — similarity score computed in DB, no embedding column transferred
            List<DocumentEmbeddingProjection> nearest;
            if (request.getSimilarityThreshold() > 0) {
                nearest = repository.findNearestLightWithThreshold(
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold());
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestLight(queryEmbedding, request.getTopK());
            }

            LOG.info("Found {} documents in OpenAI database", nearest.size());

            // Convert to Document objects with DB-computed similarity scores
            List<Document> results = new java.util.ArrayList<>();
            for (int i = 0; i < nearest.size(); i++) {
                DocumentEmbeddingProjection p = nearest.get(i);
                double similarity = p.getSimilarityScore();

                Map<String, Object> metadata = new java.util.HashMap<>();
                metadata.put("filename", p.getFileName());
                metadata.put("id", p.getId());
                metadata.put("created_at", p.getCreatedAt());
                metadata.put("similarity", similarity);
                metadata.put("distance", 1.0 - similarity);

                results.add(new Document(p.getChunk(), metadata));

                if (i < 3) {
                    LOG.debug("Result {}: similarity={}, from {}", i + 1,
                            String.format("%.4f", similarity), p.getFileName());
                }
            }

            LOG.info("Returning {} documents with similarity scores from OpenAI search", results.size());
            return results;

        } catch (Exception e) {
            LOG.error("Error during OpenAI similarity search with scores: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI similarity search failed", e);
        }
    }

    /**
     * Hybrid search: vector search (primary) + file-name matching (supplementary).
     * Vector search finds semantically similar chunks.
     * File-name matching ensures chunks from the queried entity's report are included.
     * Results are merged, deduplicated, and passed to Stage 2 keyword reranker.
     */
    @Transactional(readOnly = true)
    public List<Document> hybridSearch(SearchRequest request) {
        LOG.info("Starting HYBRID search for query: '{}'", request.getQuery());
        String query = request.getQuery();
        int topK = request.getTopK();
        double threshold = request.getSimilarityThreshold();

        try {
            // Set HNSW ef_search on this connection — must be same connection as vector search
            entityManager.createNativeQuery("SET hnsw.ef_search = 400").executeUpdate();

            // Generate embedding for vector search
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(query));
            float[] queryEmbedding = response.getResults().get(0).getOutput();

            // 1. Vector search (primary)
            List<DocumentEmbeddingProjection> vectorResults;
            if (threshold > 0) {
                vectorResults = repository.findNearestLightWithThreshold(queryEmbedding, topK, threshold);
            } else {
                vectorResults = repository.findNearestLight(queryEmbedding, topK);
            }

            // 2. File-name matching (supplementary) — ensures entity-specific chunks are in candidate set
            List<DocumentEmbeddingProjection> fileNameResults = fileNameSearch(query, queryEmbedding);

            LOG.info("Vector search returned {} results, file-name match returned {} results",
                    vectorResults.size(), fileNameResults.size());

            // 3. Merge + deduplicate by id (vector results take priority for similarity scores)
            Map<Long, DocumentEmbeddingProjection> mergedMap = new LinkedHashMap<>();
            for (DocumentEmbeddingProjection p : vectorResults) {
                mergedMap.put(p.getId(), p);
            }
            for (DocumentEmbeddingProjection p : fileNameResults) {
                mergedMap.putIfAbsent(p.getId(), p);
            }

            // 4. Convert to Document objects with similarity scores
            List<Document> results = new ArrayList<>();
            for (DocumentEmbeddingProjection p : mergedMap.values()) {
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("filename", p.getFileName());
                metadata.put("id", p.getId());
                metadata.put("created_at", p.getCreatedAt());
                metadata.put("similarity", p.getSimilarityScore());
                metadata.put("distance", 1.0 - p.getSimilarityScore());
                results.add(new Document(p.getChunk(), metadata));
            }

            LOG.info("Hybrid search returning {} merged results", results.size());
            return results;

        } catch (Exception e) {
            LOG.error("Error during hybrid search: {}", e.getMessage(), e);
            throw new RuntimeException("Hybrid search failed", e);
        }
    }

    private static final int FILE_NAME_CHUNK_LIMIT = 30;
    private static final int MAX_FILES_PER_TERM = 10;

    // Common English stop words — skipped during file-name matching to avoid junk hits
    // (e.g. "are" matching Areg, Arel1, Garem1 via LIKE '%are%')
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "the", "this", "that", "these", "those",
            "i", "me", "my", "we", "our", "you", "your", "he", "him", "his",
            "she", "her", "it", "its", "they", "them", "their",
            "is", "are", "was", "were", "be", "been", "being",
            "have", "has", "had", "do", "does", "did",
            "will", "would", "shall", "should", "may", "might", "must",
            "can", "could",
            "to", "of", "in", "for", "on", "with", "at", "by", "from",
            "as", "into", "about", "between", "through", "after", "before",
            "and", "or", "but", "nor", "so", "if", "when", "where",
            "not", "no", "very", "just", "also", "now", "here", "there",
            "then", "than", "how", "what", "which", "who", "whom", "why",
            "give", "get", "show", "tell", "find", "list", "many", "much",
            "any", "all", "some", "each", "every", "other", "only",
            "please", "want", "need", "like", "know"
    );

    /**
     * Find chunks from reports whose file_name matches query terms.
     * Extracts tokens from query, checks each against file_name column.
     * Uses LIKE for substring matching; falls back to word-boundary regex (\m...\M)
     * when LIKE returns too many results (e.g. "ace" matching Pcare, Grace, etc.).
     * Returns chunks ordered by vector similarity to the query.
     */
    @SuppressWarnings("unchecked")
    private List<DocumentEmbeddingProjection> fileNameSearch(String query, float[] queryEmbedding) {
        try {
            String[] tokens = query.trim().split("\\s+");

            // Deduplicate + filter tokens (stop words, short tokens)
            Set<String> uniqueTokens = new LinkedHashSet<>();
            for (String token : tokens) {
                String lower = token.toLowerCase().replaceAll("[,\\.\\?!;:]+$", "");
                if (lower.length() <= 2) continue;
                if (STOP_WORDS.contains(lower)) continue;
                uniqueTokens.add(lower);
            }

            Set<String> matchedFileNames = new LinkedHashSet<>();

            for (String lower : uniqueTokens) {
                // First try: LIKE substring match
                List<String> matches = entityManager.createNativeQuery(
                        "SELECT DISTINCT file_name FROM document_embeddings " +
                        "WHERE LOWER(file_name) LIKE ?1 LIMIT " + (MAX_FILES_PER_TERM + 1))
                        .setParameter(1, "%" + lower + "%")
                        .getResultList();

                if (!matches.isEmpty() && matches.size() <= MAX_FILES_PER_TERM) {
                    matchedFileNames.addAll(matches);
                    LOG.info("File-name match: '{}' → {} file(s)", lower, matches.size());
                } else if (matches.size() > MAX_FILES_PER_TERM && lower.matches("[a-z0-9]+")) {
                    // LIKE too broad (e.g. "ace" matching Pcare, Grace, etc.)
                    // Fallback: Postgres word-boundary regex (\m = word-start, \M = word-end)
                    List<String> tightMatches = entityManager.createNativeQuery(
                            "SELECT DISTINCT file_name FROM document_embeddings " +
                            "WHERE file_name ~* ?1 LIMIT " + (MAX_FILES_PER_TERM + 1))
                            .setParameter(1, "\\m" + lower + "\\M")
                            .getResultList();

                    if (!tightMatches.isEmpty() && tightMatches.size() <= MAX_FILES_PER_TERM) {
                        matchedFileNames.addAll(tightMatches);
                        LOG.info("File-name match (word-boundary): '{}' → {} file(s)", lower, tightMatches.size());
                    } else {
                        LOG.debug("File-name match: '{}' skipped (LIKE={}, word-boundary={})",
                                lower, matches.size(), tightMatches.size());
                    }
                }
            }

            if (matchedFileNames.isEmpty()) {
                LOG.info("File-name match: no entity matches found in query");
                return Collections.emptyList();
            }

            // Pull chunks from matched files, ordered by vector similarity to query
            List<DocumentEmbeddingProjection> results = new ArrayList<>();
            for (String fileName : matchedFileNames) {
                List<DocumentEmbeddingProjection> chunks =
                        repository.findByFileNameOrderedBySimilarity(queryEmbedding, fileName, FILE_NAME_CHUNK_LIMIT);
                results.addAll(chunks);
                LOG.info("File-name match: '{}' → {} chunks retrieved", fileName, chunks.size());
            }

            return results;

        } catch (Exception e) {
            LOG.error("File-name search failed: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

}