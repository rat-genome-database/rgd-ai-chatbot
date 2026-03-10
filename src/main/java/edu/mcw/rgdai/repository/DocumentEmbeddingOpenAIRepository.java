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

    // Find nearest neighbors with minimum similarity threshold (same as Ollama)
    @Query(value = "SELECT * FROM document_embeddings " +
            "WHERE (1 - (embedding <=> CAST(:queryEmbedding AS vector))) >= :threshold " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbeddingOpenAI> findNearestNeighborsWithThreshold(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("threshold") double threshold
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