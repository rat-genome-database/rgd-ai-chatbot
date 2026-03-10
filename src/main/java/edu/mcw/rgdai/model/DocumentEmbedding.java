package edu.mcw.rgdai.model;


import jakarta.persistence.*;
import com.pgvector.PGvector;
import java.time.LocalDateTime;
import org.hibernate.annotations.Type;
import edu.mcw.rgdai.config.types.PGvectorType;

@Entity
//@Table(name = "document_embeddings")
@Table(name = "document_embeddings_ollama")
public class DocumentEmbedding {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

//    @Column(name = "embedding", columnDefinition = "vector(1536)")
//    private PGvector embedding;
    @Type(PGvectorType.class)
//    @Column(name = "embedding", columnDefinition = "vector(1536)")
    @Column(name = "embedding", columnDefinition = "vector(1024)")
    private PGvector embedding;

    @Column(columnDefinition = "text")
    private String chunk;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public DocumentEmbedding() {}

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