package edu.mcw.rgdai.vectorstore;

import com.pgvector.PGvector;
import edu.mcw.rgdai.model.DocumentEmbedding;
import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

//@Component
public class PostgresVectorStore implements VectorStore {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresVectorStore.class);
    private final DocumentEmbeddingRepository repository;
    private final EmbeddingModel embeddingModel;

    public PostgresVectorStore(DocumentEmbeddingRepository repository, EmbeddingModel embeddingModel) {
        this.repository = repository;
        this.embeddingModel = embeddingModel;
    }

    @Override
    public void add(List<Document> documents) {
        LOG.info("Adding {} documents to vector store", documents.size());

        for (Document doc : documents) {
            try {
                // Generate embedding for the document content
                float[] embedding = embeddingModel.embed(List.of(doc.getContent())).get(0);

                // Create and save the document embedding
                DocumentEmbedding docEmbedding = new DocumentEmbedding();
                docEmbedding.setChunk(doc.getContent());
                docEmbedding.setEmbedding(new PGvector(embedding));
                docEmbedding.setFileName(doc.getMetadata().getOrDefault("filename", "unknown").toString());
                docEmbedding.setCreatedAt(LocalDateTime.now());

                repository.save(docEmbedding);
                LOG.debug("Saved document chunk: {} characters from {}",
                        doc.getContent().length(), docEmbedding.getFileName());

            } catch (Exception e) {
                LOG.error("Failed to add document to vector store: {}", e.getMessage(), e);
                throw new RuntimeException("Failed to add document to vector store", e);
            }
        }

        LOG.info("Successfully added all {} documents to vector store", documents.size());
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        LOG.info("Starting similarity search for query: '{}'", request.getQuery());
        LOG.info("Search parameters - TopK: {}, Similarity threshold: {}",
                request.getTopK(), request.getSimilarityThreshold());

        try {
            // Generate embedding for the search query
            EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
            float[] queryEmbedding = response.getResults().get(0).getOutput();
            LOG.debug("Generated query embedding vector of size: {}", queryEmbedding.length);

            // Find nearest neighbors from the database
            List<DocumentEmbedding> nearest;
            if (request.getSimilarityThreshold() > 0) {
                nearest = repository.findNearestNeighborsWithThreshold(
                        queryEmbedding, request.getTopK(), request.getSimilarityThreshold());
                LOG.info("Using similarity threshold: {}", request.getSimilarityThreshold());
            } else {
                nearest = repository.findNearestNeighbors(queryEmbedding, request.getTopK());
            }

            LOG.info("Found {} documents in database", nearest.size());

            // Convert to Document objects
            List<Document> results = nearest.stream()
                    .map(de -> {
                        Map<String, Object> metadata = Map.of(
                                "filename", de.getFileName(),
                                "id", de.getId(),
                                "created_at", de.getCreatedAt()
                        );
                        return new Document(de.getChunk(), metadata);
                    })
                    .collect(Collectors.toList());

            // Log some details about the returned documents
            for (int i = 0; i < Math.min(3, results.size()); i++) {
                Document doc = results.get(i);
                LOG.debug("Result {}: {} characters from {}",
                        i + 1, doc.getContent().length(),
                        doc.getMetadata().get("filename"));
            }

            LOG.info("Returning {} documents from similarity search", results.size());
            return results;

        } catch (Exception e) {
            LOG.error("Error during similarity search: {}", e.getMessage(), e);
            throw new RuntimeException("Similarity search failed", e);
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
        LOG.info("Vector store contains {} documents", count);
        return count;
    }

    // Method to get unique filenames in the vector store
    public List<String> getAvailableFiles() {
        return repository.findDistinctFileNames();
    }
}

//old code
//package edu.mcw.rgdai.vectorstore;
//
//import com.pgvector.PGvector;
//import edu.mcw.rgdai.model.DocumentEmbedding;
//import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.embedding.EmbeddingResponse;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.stereotype.Component;
//
//import java.time.LocalDateTime;
//import java.util.List;
//import java.util.Map;
//import java.util.Optional;
//import java.util.stream.Collectors;
//
//@Component
//public class PostgresVectorStore implements VectorStore {
//    private final DocumentEmbeddingRepository repository;
//    private final EmbeddingModel embeddingModel;
//
//    public PostgresVectorStore(DocumentEmbeddingRepository repository, EmbeddingModel embeddingModel) {
//        this.repository = repository;
//        this.embeddingModel = embeddingModel;
//    }
//
//    @Override
//    public void add(List<Document> documents) {
//        for (Document doc : documents) {
//            float[] embedding = embeddingModel.embed(List.of(doc.getContent())).get(0);
//            DocumentEmbedding docEmbedding = new DocumentEmbedding();
//            docEmbedding.setChunk(doc.getContent());
//            docEmbedding.setEmbedding(new PGvector(embedding));
//            docEmbedding.setFileName(doc.getMetadata().getOrDefault("filename", "unknown").toString());
//            docEmbedding.setCreatedAt(LocalDateTime.now());
//
//            repository.save(docEmbedding);
//        }
//    }
//
//    @Override
//    public List<Document> similaritySearch(SearchRequest request) {
//        System.out.println("Starting similarity search for query: {}"+request.getQuery());
//        EmbeddingResponse response = embeddingModel.embedForResponse(List.of(request.getQuery()));
//        float[] queryEmbedding = response.getResults().get(0).getOutput();
//        System.out.println("Generated embedding vector of size: {}"+queryEmbedding.length);
//        List<DocumentEmbedding> nearest = repository.findNearestNeighbors(queryEmbedding, request.getTopK());
//        System.out.println("Found {} nearest documents in database"+nearest.size());
//        return nearest.stream()
//                .map(de -> new Document(de.getChunk(), Map.of("filename", de.getFileName())))
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public Optional<Boolean> delete(List<String> ids) {
//        throw new UnsupportedOperationException("Delete operation not implemented");
//    }
//}