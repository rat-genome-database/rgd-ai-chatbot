package edu.mcw.rgdai.vectorstore;

import com.pgvector.PGvector;
import edu.mcw.rgd.process.ReportMetadata;
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
                String fileName = doc.getMetadata().getOrDefault("filename", "unknown").toString();
                docEmbedding.setChunk(doc.getContent());
                docEmbedding.setEmbedding(new PGvector(embedding));
                docEmbedding.setFileName(fileName);
                docEmbedding.setCreatedAt(LocalDateTime.now());

                // Same metadata the pipeline writes. Without this, anything ingested through
                // the chatbot's own upload or bulk-embed path would be invisible to exact
                // symbol lookup and section filtering, even though its text embeds fine.
                ReportMetadata.Identity identity = ReportMetadata.parseDisplayName(fileName);
                docEmbedding.setRgdId(identity == null ? null : identity.rgdId);
                docEmbedding.setSection(ReportMetadata.sectionOf(doc.getContent()));

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
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold(),
                        excludedOrSentinel(null));
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestLight(queryEmbedding, request.getTopK(), excludedOrSentinel(null));
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
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold(),
                        excludedOrSentinel(null));
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestLight(queryEmbedding, request.getTopK(), excludedOrSentinel(null));
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
     * Fetch chunks for records the user named outright, by exact symbol match.
     *
     * <p>Similarity search is the wrong instrument once a record is named: embedding a list
     * of symbols produces a centroid near none of them, and each lookup costs an embedding
     * call. This is a single indexed query that cannot miss a record present in the index.</p>
     *
     * @param symbols    record symbols; matched case-insensitively
     * @param objectType optional Gene/Qtl/Strain filter, null for any
     * @param section    optional section filter (e.g. "## Genomic Position"), null for all
     * @return chunks in symbol order, empty when nothing resolves
     */
    @Transactional(readOnly = true)
    public List<Document> findBySymbols(Collection<String> symbols, String objectType, String section) {
        if (symbols == null || symbols.isEmpty()) {
            return new ArrayList<>();
        }

        Set<String> lowered = new LinkedHashSet<>();
        for (String s : symbols) {
            if (s != null && !s.isBlank()) {
                lowered.add(normalizeSymbol(s));
            }
        }
        if (lowered.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            List<DocumentEmbeddingProjection> rows =
                    repository.findChunksBySymbols(lowered, objectType, section);
            LOG.info("Exact symbol lookup: {} symbol(s) -> {} chunk(s)", lowered.size(), rows.size());
            return toDocuments(rows);
        } catch (Exception e) {
            LOG.error("Exact symbol lookup failed, caller should fall back to search: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * Placeholder for "exclude nothing".
     *
     * <p>{@code NOT IN ()} is not valid SQL, so the exclusion list is never allowed to be
     * empty. An empty string can never equal a real section — every section starts with
     * {@code ##} — so passing it is equivalent to no filter while keeping one query path
     * instead of two.</p>
     */
    private static final String NO_EXCLUSIONS = "";

    /**
     * RRF smoothing constant. 60 is the value from the original rank-fusion work and the de
     * facto default: large enough that the top few ranks are not overwhelmingly dominant,
     * small enough that deep results still fade out.
     */
    private static final double RRF_K = 60.0;

    /** Whether the lexical arm runs; off restores pure vector + file-name behaviour. */
    private boolean fullTextEnabled = true;

    public void setFullTextEnabled(boolean fullTextEnabled) {
        this.fullTextEnabled = fullTextEnabled;
    }

    /** Add 1/(k + rank) for each result, so a document ranked well by either arm rises. */
    private static void accumulateRrf(Map<Long, Double> scores, List<DocumentEmbeddingProjection> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            Long id = ranked.get(i).getId();
            scores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }

    /**
     * Reduce a symbol to the form the repository's symbol queries compare against.
     *
     * <p>Must stay in step with the SQL side, which applies
     * {@code regexp_replace(LOWER(symbol), '<[^>]*>|[\^\[\]]', '', 'g')}. Stored symbols carry
     * presentation markup — 1,502 with HTML tags, 890 with the caret form — so
     * {@code LH-<i>C17h6orf52<sup>em1Aek</sup></i>} and
     * {@code LH-Chr 17^[LN]-C17h6orf52^[em2Mcwi]} are what a person means when they type
     * {@code LH-C17h6orf52em1Aek}. Dropping the markup from both sides is what lets the two
     * meet; without it, exact lookup silently misses every marked-up strain.</p>
     */
    public static String normalizeSymbol(String symbol) {
        if (symbol == null) {
            return "";
        }
        return symbol.toLowerCase()
                .replaceAll("<[^>]*>", "")
                .replaceAll("[\\^\\[\\]]", "")
                .trim();
    }

    /** Never hand the repository an empty collection; substitute the sentinel instead. */
    private static Collection<String> excludedOrSentinel(Collection<String> excludedSections) {
        if (excludedSections == null || excludedSections.isEmpty()) {
            return List.of(NO_EXCLUSIONS);
        }
        return excludedSections;
    }

    /** Projection rows to Spring AI Documents, carrying the metadata the re-ranker reads. */
    private List<Document> toDocuments(List<DocumentEmbeddingProjection> rows) {
        List<Document> results = new ArrayList<>();
        for (DocumentEmbeddingProjection p : rows) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("filename", p.getFileName());
            metadata.put("id", p.getId());
            metadata.put("created_at", p.getCreatedAt());
            metadata.put("similarity", p.getSimilarityScore());
            metadata.put("distance", 1.0 - p.getSimilarityScore());
            metadata.put("rgd_id", p.getRgdId());
            metadata.put("section", p.getSection());
            results.add(new Document(p.getChunk(), metadata));
        }
        return results;
    }

    /**
     * Hybrid search: vector search (primary) + file-name matching (supplementary).
     * Vector search finds semantically similar chunks.
     * File-name matching ensures chunks from the queried entity's report are included.
     * Results are merged, deduplicated, and passed to Stage 2 keyword reranker.
     */
    @Transactional(readOnly = true)
    public List<Document> hybridSearch(SearchRequest request) {
        return hybridSearch(request, null);
    }

    /**
     * Hybrid search with section exclusions applied to the vector arm.
     *
     * <p>Region-listing sections are the bulk of a report and semantically bland, which
     * makes them ideal filler for a candidate set: on QTL reports
     * {@code ## Genes in Region} alone is roughly two-thirds of the chunks. Dropping them
     * at the SQL level rather than in the re-ranker is what matters — by the time results
     * are ranked, the boilerplate has already consumed the topK slots.</p>
     *
     * <p>Only the vector arm is filtered. The file-name arm is already scoped to a
     * specific matched report and capped per file, so it cannot flood the candidate set
     * the same way, and a user asking about a named report may well want those rows.</p>
     */
    @Transactional(readOnly = true)
    public List<Document> hybridSearch(SearchRequest request, Collection<String> excludedSections) {
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
                vectorResults = repository.findNearestLightWithThreshold(
                        queryEmbedding, topK, threshold, excludedOrSentinel(excludedSections));
            } else {
                vectorResults = repository.findNearestLight(
                        queryEmbedding, topK, excludedOrSentinel(excludedSections));
            }

            // 2. Full-text search — catches exact terms the embedding misses. A symbol like
            // "LH/Mav" has almost no semantic signal, so cosine similarity drifts toward
            // unrelated text while a lexical match lands it exactly.
            List<DocumentEmbeddingProjection> textResults = new ArrayList<>();
            if (fullTextEnabled) {
                try {
                    textResults = repository.findByFullTextSearch(
                            query, topK, excludedOrSentinel(excludedSections));
                } catch (Exception e) {
                    // Never let the lexical arm take down the search; vector results still stand.
                    LOG.error("Full-text arm failed, continuing with vector results only: {}", e.getMessage());
                }
            }

            // 3. File-name matching (supplementary) — ensures entity-specific chunks are in candidate set
            List<DocumentEmbeddingProjection> fileNameResults = fileNameSearch(query, queryEmbedding);

            LOG.info("Vector search returned {}, full-text returned {}, file-name match returned {}",
                    vectorResults.size(), textResults.size(), fileNameResults.size());

            // 4. Reciprocal Rank Fusion across the two scored arms.
            //
            // Cosine similarity and ts_rank are not comparable numbers — ts_rank here tops out
            // around 0.05 while cosine sits near 0.5 — so fusing the scores directly would let
            // the vector arm win every time. RRF throws the magnitudes away and fuses ranks
            // instead, which is the whole point of the technique.
            Map<Long, Double> rrf = new HashMap<>();
            accumulateRrf(rrf, vectorResults);
            accumulateRrf(rrf, textResults);

            Map<Long, DocumentEmbeddingProjection> mergedMap = new LinkedHashMap<>();
            for (DocumentEmbeddingProjection p : vectorResults) {
                mergedMap.put(p.getId(), p);
            }
            Set<Long> vectorIds = new HashSet<>(mergedMap.keySet());
            for (DocumentEmbeddingProjection p : textResults) {
                mergedMap.putIfAbsent(p.getId(), p);
            }
            // The file-name arm is unranked relative to the other two, so it joins the candidate
            // set without an RRF contribution and lets the stage-2 re-ranker judge it.
            for (DocumentEmbeddingProjection p : fileNameResults) {
                mergedMap.putIfAbsent(p.getId(), p);
            }

            // 5. Convert to Documents, ordered by fused rank.
            List<DocumentEmbeddingProjection> ordered = new ArrayList<>(mergedMap.values());
            ordered.sort((a, b) -> Double.compare(
                    rrf.getOrDefault(b.getId(), 0.0), rrf.getOrDefault(a.getId(), 0.0)));

            List<Document> results = new ArrayList<>();
            for (DocumentEmbeddingProjection p : ordered) {
                boolean fromVector = vectorIds.contains(p.getId());
                Map<String, Object> metadata = new HashMap<>();
                metadata.put("filename", p.getFileName());
                metadata.put("id", p.getId());
                metadata.put("created_at", p.getCreatedAt());
                metadata.put("rgd_id", p.getRgdId());
                metadata.put("section", p.getSection());
                metadata.put("rrf_score", rrf.getOrDefault(p.getId(), 0.0));

                // The stage-2 re-ranker reads "distance" as 1 - cosine. Only vector rows carry a
                // real cosine; a text-only row's ts_rank on that scale would read as "almost no
                // semantic match" and bury a chunk that matched the query terms exactly. Such
                // rows get the search threshold instead — at the bar, not above it — leaving the
                // re-ranker's keyword component to decide, which is what actually found them.
                double similarity = fromVector ? p.getSimilarityScore() : Math.max(threshold, 0.0);
                metadata.put("similarity", similarity);
                metadata.put("distance", 1.0 - similarity);
                results.add(new Document(p.getChunk(), metadata));
            }

            LOG.info("Hybrid search returning {} merged results ({} vector-backed)",
                    results.size(), vectorIds.size());
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
                // Two characters is a real symbol here, not noise: LH, LN, LL, BN, SS, GK and
                // WF are all rat strains, and 2,163 indexed records have a symbol this short.
                // Skipping them meant a question about the LH rat gave the file-name arm
                // nothing to match on at all, leaving only an embedding of "LH" — which
                // carries almost no semantic signal — to find LH strains with.
                // Single characters stay out; the stop-word list above already covers the
                // common two-letter English words.
                if (lower.length() < 2) continue;
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