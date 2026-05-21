package edu.mcw.rgdai.repository;

import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
            "(1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS similarityScore " +
            "FROM document_embeddings " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findNearestLight(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k
    );

    // Lightweight search with threshold: returns only needed columns + DB-computed similarity score
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
            "(1 - (embedding <=> CAST(:queryEmbedding AS vector))) AS similarityScore " +
            "FROM document_embeddings " +
            "WHERE (1 - (embedding <=> CAST(:queryEmbedding AS vector))) >= :threshold " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findNearestLightWithThreshold(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("threshold") double threshold
    );

    // Full-text search using GIN index + ts_rank with OR semantics.
    // plainto_tsquery uses AND (all terms must match) — too restrictive for natural language queries.
    // Convert & to | so chunks matching ANY query term are found, ranked by how many they match.
    @Query(value = "SELECT de.id, de.chunk, de.file_name AS fileName, de.created_at AS createdAt, " +
            "ts_rank(de.tsv, q) AS similarityScore " +
            "FROM document_embeddings de, " +
            "to_tsquery('english', replace(CAST(plainto_tsquery('english', :query) AS text), ' & ', ' | ')) AS q " +
            "WHERE de.tsv @@ q " +
            "ORDER BY ts_rank(de.tsv, q) DESC " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingProjection> findByFullTextSearch(
            @Param("query") String query,
            @Param("k") int k
    );

    // Find chunks from a specific file, ordered by vector similarity to query embedding
    @Query(value = "SELECT id, chunk, file_name AS fileName, created_at AS createdAt, " +
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