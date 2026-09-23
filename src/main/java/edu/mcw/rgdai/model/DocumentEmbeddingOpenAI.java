package edu.mcw.rgdai.model;

import jakarta.persistence.*;
import com.pgvector.PGvector;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import edu.mcw.rgdai.config.types.PGvectorType;

@Entity
@Table(name = "document_embeddings")
public class DocumentEmbeddingOpenAI {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Type(PGvectorType.class)
    @Column(name = "embedding", columnDefinition = "vector(1536)")
    private PGvector embedding;

    @Column(columnDefinition = "text")
    private String chunk;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** The object this chunk's report describes; null for reports with no numeric RGD ID. */
    @Column(name = "rgd_id")
    private Long rgdId;

    /**
     * Heading path this chunk came from, e.g. {@code "## Genomic Position"}. Declared as
     * text to match the column — leaving it to the default would have Hibernate expect a
     * varchar(255) and, under ddl-auto=update, complain about the existing column.
     */
    @Column(name = "section", columnDefinition = "text")
    private String section;

    public DocumentEmbeddingOpenAI() {}

    public Long getRgdId() {
        return rgdId;
    }

    public void setRgdId(Long rgdId) {
        this.rgdId = rgdId;
    }

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public PGvector getEmbedding() {
        return embedding;
    }

    public void setEmbedding(PGvector embedding) {
        this.embedding = embedding;
    }

    public String getChunk() {
        return chunk;
    }

    public void setChunk(String chunk) {
        this.chunk = chunk;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}