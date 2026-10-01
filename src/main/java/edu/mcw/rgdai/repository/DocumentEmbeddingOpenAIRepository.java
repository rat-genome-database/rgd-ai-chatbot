package edu.mcw.rgdai.repository;

import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface DocumentEmbeddingOpenAIRepository extends JpaRepository<DocumentEmbeddingOpenAI, Long> {

    // Find nearest neighbors using cosine distance (same as Ollama)
    @Query(value = "SELECT * FROM document_embeddings ORDER BY embedding <=> CAST(:queryEmbedding AS vector) LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingOpenAI> findNearestNeighbors(@Param("queryEmbedding") float[] queryEmbedding, @Param("k") int k);

    // Find nearest neighbors with minimum similarity threshold
    @Query(value = "SELECT * FROM document_embeddings " +
            "WHERE (1 - (embedding <=> CAST(:queryEmbedding AS vector))) >= :threshold " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingOpenAI> findNearestNeighborsWithThreshold(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("threshold") double threshold
    );

    // Lightweight search: returns only needed columns + DB-computed similarity score (no embedding transfer)
    //
    // "section IS NULL OR section NOT IN (...)" — the IS NULL half is load-bearing, not
    // defensive. Most chunks predate the section column, and NULL NOT IN (...) evaluates to
    // NULL rather than true, so without it every un-backfilled chunk would silently drop out
    // of retrieval. Callers must pass a non-empty list; see the vector store's sentinel.
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
            "rgd_id AS rgdId, section, " +
            "(1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS similarityScore " +
            "FROM document_embeddings " +
            "WHERE (section IS NULL OR section NOT IN (:excludedSections)) " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findNearestLight(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("excludedSections") Collection<String> excludedSections
    );

    // Lightweight search with threshold: returns only needed columns + DB-computed similarity score
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
            "rgd_id AS rgdId, section, " +
            "(1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS similarityScore " +
            "FROM document_embeddings " +
            "WHERE (1 - (embedding <=> CAST(:queryEmbedding AS vector))) >= :threshold " +
            "AND (section IS NULL OR section NOT IN (:excludedSections)) " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findNearestLightWithThreshold(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("threshold") double threshold,
            @Param("excludedSections") Collection<String> excludedSections
    );

    /**
     * Exact chunk lookup by symbol — the path that replaces one vector search per named
     * record. A question naming 22 genes becomes a single indexed query instead of 22
     * embedding calls, and cannot miss a record that is actually in the index.
     *
     * <p>Symbols must be lower-cased by the caller to match LOWER(o.symbol). A fixed
     * similarity of 1.0 keeps these ahead of anything the re-ranker scores, which is
     * correct: the user named these records explicitly.</p>
     */
    @Query(value = "SELECT e.id, e.chunk, e.file_name AS fileName, e.created_at AS createdAt, " +
            "e.rgd_id AS rgdId, e.section, " +
            "CAST(1.0 AS double precision) AS similarityScore " +
            "FROM report_object o " +
            "JOIN document_embeddings e ON e.rgd_id = o.rgd_id " +
            "WHERE regexp_replace(LOWER(o.symbol), '<[^>]*>|[\\^\\[\\]]', '', 'g') IN (:symbols) " +
            "AND (:objectType IS NULL OR o.object_type = :objectType) " +
            "AND (:section IS NULL OR e.section = :section) " +
            "ORDER BY o.symbol, e.id", nativeQuery = true)
    List<DocumentEmbeddingProjection> findChunksBySymbols(
            @Param("symbols") Collection<String> symbols,
            @Param("objectType") String objectType,
            @Param("section") String section
    );

    /**
     * Positions for a batch of named records on one assembly.
     *
     * <p>"Give me the positions of them" is a question about specific records, and the answer
     * is a column in {@code report_position} — not something to hope surfaces in a retrieved
     * chunk. Going to the table means every named record gets an answer or is visibly absent,
     * instead of however many happened to be retrieved.</p>
     *
     * <p>Symbols must be lower-cased by the caller to match LOWER(o.symbol).</p>
     */
    @Query(value = "SELECT o.rgd_id AS rgdId, o.symbol AS symbol, o.name AS name, " +
            "p.chromosome AS chromosome, p.start_pos AS startPos, p.stop_pos AS stopPos " +
            "FROM report_object o " +
            "JOIN report_position p ON p.rgd_id = o.rgd_id " +
            "WHERE regexp_replace(LOWER(o.symbol), '<[^>]*>|[\\^\\[\\]]', '', 'g') IN (:symbols) AND p.assembly = :assembly " +
            "ORDER BY o.symbol", nativeQuery = true)
    List<RegionMemberProjection> findPositionsBySymbols(
            @Param("symbols") Collection<String> symbols,
            @Param("assembly") String assembly
    );

    /**
     * The span of one named record on an assembly — the anchor for a region question.
     *
     * <p>Returns a row per matching object, so a symbol shared across species comes back more
     * than once and the caller can decline to guess which was meant.</p>
     */
    @Query(value = "SELECT o.rgd_id AS rgdId, o.symbol AS symbol, o.name AS name, " +
            "p.chromosome AS chromosome, p.start_pos AS startPos, p.stop_pos AS stopPos " +
            "FROM report_object o " +
            "JOIN report_position p ON p.rgd_id = o.rgd_id " +
            "WHERE regexp_replace(LOWER(o.symbol), '<[^>]*>|[\\^\\[\\]]', '', 'g') = :symbol AND p.assembly = :assembly " +
            "ORDER BY o.rgd_id", nativeQuery = true)
    List<RegionMemberProjection> findAnchorPosition(
            @Param("symbol") String symbol,
            @Param("assembly") String assembly
    );

    /**
     * Every record of a type whose span overlaps a window — "which QTLs lie over this gene".
     *
     * <p>Two intervals overlap when each starts before the other ends, which is the whole of
     * the predicate here. A gene report's "QTLs in Region" table names the overlapping QTLs
     * but carries none of their coordinates; this supplies them from the QTLs' own rows.</p>
     */
    @Query(value = "SELECT o.rgd_id AS rgdId, o.symbol AS symbol, o.name AS name, " +
            "p.chromosome AS chromosome, p.start_pos AS startPos, p.stop_pos AS stopPos " +
            "FROM report_object o " +
            "JOIN report_position p ON p.rgd_id = o.rgd_id " +
            "WHERE o.object_type = :objectType AND p.assembly = :assembly " +
            "AND p.chromosome = :chromosome " +
            "AND p.start_pos <= :windowStop AND p.stop_pos >= :windowStart " +
            "ORDER BY p.start_pos", nativeQuery = true)
    List<RegionMemberProjection> findOverlappingInRegion(
            @Param("objectType") String objectType,
            @Param("assembly") String assembly,
            @Param("chromosome") String chromosome,
            @Param("windowStart") long windowStart,
            @Param("windowStop") long windowStop
    );

    /**
     * Which species each named symbol exists for.
     *
     * <p>One row per (symbol, species): a symbol present for several species comes back
     * several times, which is exactly the signal that the user's question is ambiguous.
     * Symbols must be lower-cased by the caller to match LOWER(o.symbol).</p>
     */
    @Query(value = "SELECT DISTINCT o.symbol AS symbol, o.species AS species " +
            "FROM report_object o " +
            "WHERE regexp_replace(LOWER(o.symbol), '<[^>]*>|[\\^\\[\\]]', '', 'g') IN (:symbols) AND o.species IS NOT NULL " +
            "ORDER BY o.symbol, o.species", nativeQuery = true)
    List<SymbolSpeciesProjection> findSpeciesBySymbols(@Param("symbols") Collection<String> symbols);

    // Full-text search using GIN index + ts_rank with OR semantics.
    // plainto_tsquery uses AND (all terms must match) — too restrictive for natural language queries.
    // Convert & to | so chunks matching ANY query term are found, ranked by how many they match.
    //
    // A query of nothing but stop words reduces to an empty tsquery, which matches no rows
    // rather than erroring — so no guard is needed on the caller's side.
    @Query(value = "SELECT de.id, de.chunk, de.file_name AS fileName, de.created_at AS createdAt, " +
            "de.rgd_id AS rgdId, de.section, " +
            "ts_rank(de.tsv, q) AS similarityScore " +
            "FROM document_embeddings de, " +
            "to_tsquery('english', replace(CAST(plainto_tsquery('english', :query) AS text), ' & ', ' | ')) AS q " +
            "WHERE de.tsv @@ q " +
            "AND (de.section IS NULL OR de.section NOT IN (:excludedSections)) " +
            "ORDER BY ts_rank(de.tsv, q) DESC " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findByFullTextSearch(
            @Param("query") String query,
            @Param("k") int k,
            @Param("excludedSections") Collection<String> excludedSections
    );

    // Find chunks from a specific file, ordered by vector similarity to query embedding
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
            "rgd_id AS rgdId, section, " +
            "(1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS similarityScore " +
            "FROM document_embeddings " +
            "WHERE file_name = :fileName " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findByFileNameOrderedBySimilarity(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("fileName") String fileName,
            @Param("k") int k
    );

    // Find by filename
    List<DocumentEmbeddingOpenAI> findByFileName(String fileName);

    // Get all unique filenames
    @Query("SELECT DISTINCT d.fileName FROM DocumentEmbeddingOpenAI d")
    List<String> findDistinctFileNames();

    // Count documents by filename
    @Query("SELECT COUNT(d) FROM DocumentEmbeddingOpenAI d WHERE d.fileName = :fileName")
    long countByFileName(@Param("fileName") String fileName);

    // Find by chunk containing text (case-insensitive)
    @Query("SELECT d FROM DocumentEmbeddingOpenAI d WHERE LOWER(d.chunk) LIKE LOWER(CONCAT('%', :text, '%'))")
    List<DocumentEmbeddingOpenAI> findByChunkContainingIgnoreCase(@Param("text") String text);

    // Get the most recent documents
    @Query("SELECT d FROM DocumentEmbeddingOpenAI d ORDER BY d.createdAt DESC")
    List<DocumentEmbeddingOpenAI> findAllOrderByCreatedAtDesc();
}