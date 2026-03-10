package edu.mcw.rgdai.repository;

import edu.mcw.rgdai.model.DocumentEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentEmbeddingRepository extends JpaRepository<DocumentEmbedding, Long> {

    // Find nearest neighbors using cosine distance
    @Query(value = "SELECT * FROM document_embeddings_ollama ORDER BY embedding <=> CAST(:queryEmbedding AS vector) LIMIT :k", nativeQuery = true)
    List<DocumentEmbedding> findNearestNeighbors(@Param("queryEmbedding") float[] queryEmbedding, @Param("k") int k);

    // Find nearest neighbors with minimum similarity threshold
    @Query(value = "SELECT * FROM document_embeddings_ollama " +
            "WHERE (1 - (embedding <=> CAST(:queryEmbedding AS vector))) >= :threshold " +
            "ORDER BY embedding <=> CAST(:queryEmbedding AS vector) " +
            "LIMIT :k", nativeQuery = true)
    List<DocumentEmbedding> findNearestNeighborsWithThreshold(
            @Param("queryEmbedding") float[] queryEmbedding,
            @Param("k") int k,
            @Param("threshold") double threshold
    );

    // Find by filename
    List<DocumentEmbedding> findByFileName(String fileName);

    // Get all unique filenames
    @Query("SELECT DISTINCT d.fileName FROM DocumentEmbedding d")
    List<String> findDistinctFileNames();

    // Count documents by filename
    @Query("SELECT COUNT(d) FROM DocumentEmbedding d WHERE d.fileName = :fileName")
    long countByFileName(@Param("fileName") String fileName);

    // Find by chunk containing text (case-insensitive)
    @Query("SELECT d FROM DocumentEmbedding d WHERE LOWER(d.chunk) LIKE LOWER(CONCAT('%', :text, '%'))")
    List<DocumentEmbedding> findByChunkContainingIgnoreCase(@Param("text") String text);

    // Get the most recent documents
    @Query("SELECT d FROM DocumentEmbedding d ORDER BY d.createdAt DESC")
    List<DocumentEmbedding> findAllOrderByCreatedAtDesc();
}

//old code
//package edu.mcw.rgdai.repository;
//
//import edu.mcw.rgdai.model.DocumentEmbedding;
//import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.Query;
//import org.springframework.data.repository.query.Param;
//import org.springframework.stereotype.Repository;
//
//import java.util.List;
//
//@Repository
//public interface DocumentEmbeddingRepository extends JpaRepository<DocumentEmbedding, Long> {
//
//    // Find nearest neighbors using cosine distance
//    @Query(value = "SELECT * FROM document_embeddings_ollama ORDER BY embedding <=> CAST(:queryEmbedding AS vector) LIMIT :k", nativeQuery = true)
//    List<DocumentEmbedding> findNearestNeighbors(@Param("queryEmbedding") float[] queryEmbedding, @Param("k") int k);
//
//    // Find by filename
//    List<DocumentEmbedding> findByFileName(String fileName);
//
//    // Optional: Find by chunk containing text (case-insensitive)
//    @Query("SELECT d FROM DocumentEmbedding d WHERE LOWER(d.chunk) LIKE LOWER(CONCAT('%', :text, '%'))")
//    List<DocumentEmbedding> findByChunkContainingIgnoreCase(@Param("text") String text);
//}