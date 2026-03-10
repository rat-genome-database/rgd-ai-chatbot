//package edu.mcw.rgdai;
//import edu.mcw.rgdai.model.DocumentEmbedding;
//import edu.mcw.rgdai.repository.DocumentEmbeddingRepository;
//import edu.mcw.rgdai.vectorstore.PostgresVectorStore;
//import org.junit.jupiter.api.Test;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.embedding.Embedding;
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.embedding.EmbeddingRequest;
//import org.springframework.ai.embedding.EmbeddingResponse;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.boot.test.mock.mockito.MockBean;
//
//import java.util.Arrays;
//import java.util.List;
//import java.util.Map;
//
//import static org.mockito.ArgumentMatchers.any;
//import static org.mockito.Mockito.*;
//import static org.junit.jupiter.api.Assertions.*;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.boot.test.context.SpringBootTest;
//
//@SpringBootTest
//class SpringAiChatRagApplicationTests {
//
//    @Test
//    void contextLoads() {
//    }
//    @MockBean
//    private DocumentEmbeddingRepository repository;
//
//    @MockBean
//    private EmbeddingModel embeddingModel;
//
//    @Test
//    void testAddDocument() {
//        PostgresVectorStore vectorStore = new PostgresVectorStore(repository, embeddingModel);
//        Document doc = new Document("test content", Map.of("filename", "test.txt"));
//        vectorStore.add(List.of(doc));
//
//        List<DocumentEmbedding> saved = repository.findAll();
//        assertFalse(saved.isEmpty());
//    }
//
//    @Test
//    void testSimilaritySearch() {
//        PostgresVectorStore vectorStore = new PostgresVectorStore(repository, embeddingModel);
//        // Create a mock embedding with 1536 dimensions (OpenAI's dimension size)
//        float[] mockEmbedding = new float[1536];
//        for (int i = 0; i < 1536; i++) {
//            mockEmbedding[i] = 0.1f;  // Fill with some value
//        }
//
//        List<DocumentEmbedding> mockResults = Arrays.asList(
//                new DocumentEmbedding() {{
//                    setChunk("test content");
//                    setFileName("test.txt");
//                }}
//        );
//
//        // Create mock response with correct dimension embedding
//        EmbeddingResponse mockResponse = new EmbeddingResponse(
//                List.of(new Embedding(mockEmbedding, 0))
//        );
//
//        when(embeddingModel.embedForResponse(List.of("test query")))
//                .thenReturn(mockResponse);
//
//        when(repository.findNearestNeighbors(mockEmbedding, 5))
//                .thenReturn(mockResults);
//
//        SearchRequest searchRequest = SearchRequest.query("test query")
//                .withTopK(5)
//                .withSimilarityThreshold(0.5);
//
//        List<Document> results = vectorStore.similaritySearch(searchRequest);
//
//        assertNotNull(results);
//        assertFalse(results.isEmpty());
//    }
//
//}
