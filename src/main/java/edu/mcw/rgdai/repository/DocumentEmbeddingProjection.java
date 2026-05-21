package edu.mcw.rgdai.repository;

import java.time.LocalDateTime;

/**
 * Lightweight projection for vector search results.
 * Returns only needed columns + DB-computed similarity score.
 * Avoids transferring the large embedding vector column (~6KB per row).
 */
public interface DocumentEmbeddingProjection {
    Long getId();
    String getChunk();
    String getFileName();
    LocalDateTime getCreatedAt();
    Double getSimilarityScore();
}
