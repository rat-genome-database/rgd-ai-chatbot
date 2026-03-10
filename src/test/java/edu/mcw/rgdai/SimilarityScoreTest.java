//package edu.mcw.rgdai;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.beans.factory.annotation.Qualifier;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.ai.embedding.EmbeddingModel;
//import org.springframework.ai.embedding.EmbeddingResponse;
//
//import java.util.List;
//import java.util.Map;
//import java.util.stream.Collectors;
//import java.util.Arrays;
//
//@SpringBootTest
//public class SimilarityScoreTest {
//
//    @Autowired
//    private JdbcTemplate jdbcTemplate;
//
//    @Autowired
//    @Qualifier("openAiEmbeddingModel")
//    private EmbeddingModel embeddingModel;
//
//    @Test
//    public void testSimilarityScores() {
//        // The query from the logs with correct spelling
//        String query = "what routes of administration are used in gene therapies for Huntington's disease?";
//
//        System.out.println("Query: " + query);
//        System.out.println("Getting embedding for query...");
//
//        // Get embedding for the query
//        EmbeddingResponse embeddingResponse = embeddingModel.embedForResponse(List.of(query));
//        float[] queryEmbeddingArray = embeddingResponse.getResult().getOutput();
//
//        // Convert float[] to List<Double> and then to PostgreSQL vector format
//        List<Double> queryEmbedding = new java.util.ArrayList<>();
//        for (float f : queryEmbeddingArray) {
//            queryEmbedding.add((double) f);
//        }
//
//        // Convert to PostgreSQL vector format
//        String vectorString = queryEmbedding.toString().replace(" ", "");
//
//        System.out.println("Querying database for Huntington's trials...");
//
//        // Query for the three Huntington's trials with similarity scores
//        String sql = """
//            SELECT
//                file_name,
//                1 - (embedding <=> CAST(? AS vector)) as similarity_score,
//                LEFT(chunk, 200) as chunk_preview
//            FROM document_embeddings
//            WHERE file_name IN (
//                'CLINICAL TRIAL: NCT05243017',
//                'CLINICAL TRIAL: NCT05541627',
//                'CLINICAL TRIAL: NCT06826612'
//            )
//            ORDER BY embedding <=> CAST(? AS vector)
//            LIMIT 20;
//        """;
//
//        List<Map<String, Object>> results = jdbcTemplate.queryForList(sql, vectorString, vectorString);
//
//        System.out.println("\n=== Similarity Scores for Huntington's Trials ===");
//        System.out.println(String.format("%-35s %-20s %s", "File Name", "Similarity Score", "Chunk Preview"));
//        System.out.println("-".repeat(150));
//
//        for (Map<String, Object> row : results) {
//            String fileName = (String) row.get("file_name");
//            Double score = ((Number) row.get("similarity_score")).doubleValue();
//            String preview = ((String) row.get("chunk_preview")).replace("\n", " ");
//
//            System.out.println(String.format("%-35s %-20.6f %s...", fileName, score, preview));
//        }
//
//        // Now get the top 80 documents overall to see where NCT05243017 ranks
//        System.out.println("\n\n=== Top 80 Documents Overall ===");
//
//        String sql2 = """
//            SELECT
//                file_name,
//                1 - (embedding <=> CAST(? AS vector)) as similarity_score
//            FROM document_embeddings
//            WHERE (1 - (embedding <=> CAST(? AS vector))) >= 0.35
//            ORDER BY embedding <=> CAST(? AS vector)
//            LIMIT 80;
//        """;
//
//        List<Map<String, Object>> topResults = jdbcTemplate.queryForList(sql2, vectorString, vectorString, vectorString);
//
//        System.out.println(String.format("%-5s %-40s %s", "Rank", "File Name", "Similarity Score"));
//        System.out.println("-".repeat(100));
//
//        int rank = 1;
//        for (Map<String, Object> row : topResults) {
//            String fileName = (String) row.get("file_name");
//            Double score = ((Number) row.get("similarity_score")).doubleValue();
//
//            String marker = "";
//            if (fileName.contains("NCT05243017")) marker = " *** AMT-130 ***";
//            else if (fileName.contains("NCT05541627")) marker = " *** VIBRANT-HD ***";
//            else if (fileName.contains("NCT06826612")) marker = " *** rAAV-HTT ***";
//
//            System.out.println(String.format("%-5d %-40s %.6f%s", rank, fileName, score, marker));
//            rank++;
//        }
//
//        // Count chunks per trial
//        System.out.println("\n\n=== Chunk Counts ===");
//        String sql3 = """
//            SELECT file_name, COUNT(*) as chunk_count
//            FROM document_embeddings
//            WHERE file_name LIKE '%NCT%'
//            GROUP BY file_name
//            HAVING file_name IN (
//                'CLINICAL TRIAL: NCT05243017',
//                'CLINICAL TRIAL: NCT05541627',
//                'CLINICAL TRIAL: NCT06826612'
//            )
//            ORDER BY file_name;
//        """;
//
//        List<Map<String, Object>> countResults = jdbcTemplate.queryForList(sql3);
//        for (Map<String, Object> row : countResults) {
//            System.out.println(row.get("file_name") + ": " + row.get("chunk_count") + " chunks");
//        }
//    }
//}
