package edu.mcw.rgdai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;
import java.util.Map;

@SpringBootTest
public class SimilarityScoreTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("openAiEmbeddingModel")
    private EmbeddingModel embeddingModel;

    private String getVectorString(String query) {
        EmbeddingResponse embeddingResponse = embeddingModel.embedForResponse(List.of(query));
        float[] arr = embeddingResponse.getResult().getOutput();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * Brute-force scan (ignores HNSW index) — shows true similarity scores for all chunks of a target file.
     */
    @Test
    public void testBruteForceStrain() {
        String query = "what phenotype data exists for BN/NHsdMcwi";
        String targetFileName = "RGD Strain Report - BN/NHsdMcwi (61498)";

        System.out.println("=== BRUTE FORCE (no index) ===");
        System.out.println("Query: " + query);
        System.out.println("Target: " + targetFileName + "\n");

        String vectorString = getVectorString(query);

        // All chunks for target — sequential scan, no HNSW
        String sql = """
            SELECT
                id,
                1 - (embedding <=> CAST(? AS vector)) as similarity_score,
                LEFT(chunk, 150) as chunk_preview
            FROM document_embeddings
            WHERE file_name = ?
            ORDER BY embedding <=> CAST(? AS vector)
        """;

        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(sql, vectorString, targetFileName, vectorString);

        System.out.println(String.format("%-8s %-12s %s", "ID", "Score", "Chunk Preview"));
        System.out.println("-".repeat(120));
        for (Map<String, Object> row : chunks) {
            Double score = ((Number) row.get("similarity_score")).doubleValue();
            String preview = ((String) row.get("chunk_preview")).replace("\n", " ");
            System.out.println(String.format("%-8s %-12.6f %s", row.get("id"), score, preview));
        }

        long passed = chunks.stream()
                .filter(r -> ((Number) r.get("similarity_score")).doubleValue() >= 0.35)
                .count();
        System.out.println(String.format("\nBrute force: %d/%d chunks above 0.35", passed, chunks.size()));
    }

    /**
     * HNSW search with DEFAULT ef_search (40) — simulates what the app did BEFORE the fix.
     * Then HNSW search with ef_search=400 — simulates what the app does AFTER the fix.
     */
    @Test
    public void testHnswBeforeAndAfter() {
        String query = "what phenotype data exists for BN/NHsdMcwi";
        String targetFileName = "RGD Strain Report - BN/NHsdMcwi (61498)";

        System.out.println("Query: " + query);
        System.out.println("Target: " + targetFileName + "\n");

        String vectorString = getVectorString(query);

        String searchSql = """
            SELECT
                file_name,
                1 - (embedding <=> CAST(? AS vector)) as similarity_score
            FROM document_embeddings
            WHERE (1 - (embedding <=> CAST(? AS vector))) >= 0.35
            ORDER BY embedding <=> CAST(? AS vector)
            LIMIT 80
        """;

        // --- BEFORE: default ef_search (40) ---
        System.out.println("=== HNSW with ef_search = 40 (DEFAULT — BEFORE fix) ===");
        jdbcTemplate.execute("SET hnsw.ef_search = 40");
        List<Map<String, Object>> beforeResults = jdbcTemplate.queryForList(searchSql, vectorString, vectorString, vectorString);

        long beforeTarget = beforeResults.stream()
                .filter(r -> targetFileName.equals(r.get("file_name")))
                .count();
        System.out.println(String.format("Total candidates: %d", beforeResults.size()));
        System.out.println(String.format("Target chunks found: %d", beforeTarget));

        int rank = 1;
        System.out.println(String.format("\n%-5s %-60s %s", "Rank", "File Name", "Score"));
        System.out.println("-".repeat(90));
        for (Map<String, Object> row : beforeResults) {
            String fn = (String) row.get("file_name");
            Double score = ((Number) row.get("similarity_score")).doubleValue();
            String marker = fn.equals(targetFileName) ? " ***" : "";
            System.out.println(String.format("%-5d %-60s %.6f%s", rank++, fn, score, marker));
        }

        // --- AFTER: ef_search = 400 ---
        System.out.println("\n\n=== HNSW with ef_search = 400 (AFTER fix) ===");
        jdbcTemplate.execute("SET hnsw.ef_search = 400");
        List<Map<String, Object>> afterResults = jdbcTemplate.queryForList(searchSql, vectorString, vectorString, vectorString);

        long afterTarget = afterResults.stream()
                .filter(r -> targetFileName.equals(r.get("file_name")))
                .count();
        System.out.println(String.format("Total candidates: %d", afterResults.size()));
        System.out.println(String.format("Target chunks found: %d", afterTarget));

        rank = 1;
        System.out.println(String.format("\n%-5s %-60s %s", "Rank", "File Name", "Score"));
        System.out.println("-".repeat(90));
        for (Map<String, Object> row : afterResults) {
            String fn = (String) row.get("file_name");
            Double score = ((Number) row.get("similarity_score")).doubleValue();
            String marker = fn.equals(targetFileName) ? " ***" : "";
            System.out.println(String.format("%-5d %-60s %.6f%s", rank++, fn, score, marker));
        }

        // --- COMPARISON ---
        System.out.println("\n\n=== COMPARISON ===");
        System.out.println(String.format("ef_search=40:  %d total candidates, %d from target", beforeResults.size(), beforeTarget));
        System.out.println(String.format("ef_search=400: %d total candidates, %d from target", afterResults.size(), afterTarget));
    }

    /**
     * Brute-force scan for gene query — baseline comparison.
     */
    @Test
    public void testBruteForceGene() {
        String query = "What are all the gene-chemical interactions for Htt?";
        String targetFileName = "RGD Gene Report - Htt (68337)";

        System.out.println("=== BRUTE FORCE (no index) ===");
        System.out.println("Query: " + query);
        System.out.println("Target: " + targetFileName + "\n");

        String vectorString = getVectorString(query);

        String sql = """
            SELECT
                id,
                1 - (embedding <=> CAST(? AS vector)) as similarity_score,
                LEFT(chunk, 150) as chunk_preview
            FROM document_embeddings
            WHERE file_name = ?
            ORDER BY embedding <=> CAST(? AS vector)
        """;

        List<Map<String, Object>> chunks = jdbcTemplate.queryForList(sql, vectorString, targetFileName, vectorString);

        System.out.println(String.format("%-8s %-12s %s", "ID", "Score", "Chunk Preview"));
        System.out.println("-".repeat(120));
        for (Map<String, Object> row : chunks) {
            Double score = ((Number) row.get("similarity_score")).doubleValue();
            String preview = ((String) row.get("chunk_preview")).replace("\n", " ");
            System.out.println(String.format("%-8s %-12.6f %s", row.get("id"), score, preview));
        }

        long passed = chunks.stream()
                .filter(r -> ((Number) r.get("similarity_score")).doubleValue() >= 0.35)
                .count();
        System.out.println(String.format("\nBrute force: %d/%d chunks above 0.35", passed, chunks.size()));
    }
}
