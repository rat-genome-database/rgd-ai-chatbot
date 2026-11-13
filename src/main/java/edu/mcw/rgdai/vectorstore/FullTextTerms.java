package edu.mcw.rgdai.vectorstore;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decides which words of a question the full-text arm searches on.
 *
 * <p>The old lexical query OR-ed every word of the question and ranked every chunk that
 * matched any of them. Words such as "rat", "gene" or "rgd" occur in nearly every chunk, so
 * a question like "which rat genes are associated with hypertension" ranked about 95% of
 * the table and took up to 44 s. Those words cannot tell chunks apart anyway; the arm exists
 * to catch exact terms the embedding misses (gene symbols, strain names, IDs, rare words).</p>
 *
 * <p>So each question is planned from how often its words occur in the index:</p>
 * <ul>
 *   <li>words in more than {@code commonFraction} of chunks are dropped;</li>
 *   <li>the rest are searched together (AND), which narrows to a few hundred chunks;</li>
 *   <li>words in fewer than {@code rareFraction} of chunks are also searched on their own
 *       (OR), to top up the results when the AND finds too few.</li>
 * </ul>
 * <p>On rgd_rag_dev (1.14M chunks) this took the lexical arm from 0.4–44 s to 3–310 ms.</p>
 *
 * <p>Frequencies come from {@code ts_stat} over a sample of about 200,000 chunks, read in the
 * background at startup and every {@code refreshHours}. Until the first read finishes the
 * plan is empty and the arm is skipped; vector and file-name search still run.</p>
 */
class FullTextTerms {

    private static final Logger LOG = LoggerFactory.getLogger(FullTextTerms.class);

    /** Rows to sample for word frequencies; enough for stable shares, a few seconds to read. */
    private static final int SAMPLE_ROWS = 200_000;

    /**
     * Words of how people ask, not what they ask about. They are rare in the index, so left in
     * they would count as rare terms: "tell me about the SHR strain" would AND "tell" with
     * "shr", which almost no chunk has, then top up with chunks that merely say "tell".
     * Stemmed through the same configuration as the index when frequencies load.
     */
    private static final String QUESTION_WORDS =
            "tell show list give explain describe find know want please information info details "
            + "help question get look need could would also related relating summarize summary";

    /** The words to search: all non-common words (AND) and the rare ones (OR); null = none. */
    record Plan(String allTerms, String rareTerms, List<String> searched) {}

    private final EntityManager entityManager;
    private final double commonFraction;
    private final double rareFraction;
    private final long refreshMillis;

    /** Share of chunks for every word at or above {@code rareFraction}; absent = rare. */
    private volatile Map<String, Double> frequent;
    /** {@link #QUESTION_WORDS} as lexemes. */
    private volatile java.util.Set<String> questionWords = java.util.Set.of();
    private volatile long loadedAt;
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "full-text-terms");
        t.setDaemon(true);
        return t;
    });

    FullTextTerms(EntityManager entityManager, double commonFraction, double rareFraction, double refreshHours) {
        this.entityManager = entityManager;
        this.commonFraction = commonFraction;
        this.rareFraction = rareFraction;
        this.refreshMillis = (long) (refreshHours * 3_600_000L);
        refreshInBackground();
    }

    /** The plan for one question, or null when the frequencies are not loaded yet. */
    @SuppressWarnings("unchecked")
    Plan plan(String question) {
        if (System.currentTimeMillis() - loadedAt > refreshMillis) {
            refreshInBackground();
        }
        Map<String, Double> shares = frequent;
        if (shares == null || question == null || question.isBlank()) {
            return null;
        }
        // The question's words as the index stores them: the same 'english' configuration
        // that built the tsv column, so stemming and stop words agree.
        List<String> lexemes = entityManager.createNativeQuery(
                        "SELECT lexeme FROM unnest(to_tsvector('english', ?1))")
                .setParameter(1, question)
                .getResultList();

        List<String> kept = new ArrayList<>();
        List<String> rare = new ArrayList<>();
        for (String lexeme : lexemes) {
            if (questionWords.contains(lexeme)) {
                continue;
            }
            Double share = shares.get(lexeme);
            if (share == null) {
                kept.add(lexeme);
                rare.add(lexeme);
            } else if (share < commonFraction) {
                kept.add(lexeme);
            }
        }
        if (kept.isEmpty()) {
            return new Plan(null, null, kept);
        }
        return new Plan(join(kept, " & "), rare.isEmpty() ? null : join(rare, " | "), kept);
    }

    /** Lexemes as a tsquery for the 'simple' configuration, each quoted so none is re-parsed. */
    private static String join(List<String> lexemes, String operator) {
        List<String> quoted = new ArrayList<>(lexemes.size());
        for (String lexeme : lexemes) {
            quoted.add("'" + lexeme.replace("\\", "\\\\").replace("'", "''") + "'");
        }
        return String.join(operator, quoted);
    }

    private void refreshInBackground() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        loader.submit(() -> {
            try {
                load();
            } catch (Exception e) {
                // Keep whatever was loaded before; retry on a later question.
                LOG.error("Could not read full-text word frequencies: {}", e.getMessage());
                loadedAt = System.currentTimeMillis() - refreshMillis + 300_000L;   // retry in 5 min
            } finally {
                loading.set(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void load() {
        long start = System.currentTimeMillis();
        // to_regclass(), not a ::regclass cast: Hibernate reads "::" in a native query as an escaped
        // colon and sent Postgres "'document_embeddings':regclass", a syntax error.
        Number estimate = (Number) entityManager.createNativeQuery(
                "SELECT reltuples FROM pg_class WHERE oid = to_regclass('document_embeddings')")
                .getSingleResult();
        double rows = estimate == null ? 0 : estimate.doubleValue();
        // reltuples is -1 (never analyzed) or 0 on an empty or new table: read it all.
        double percent = rows <= SAMPLE_ROWS ? 100.0 : Math.max(0.1, 100.0 * SAMPLE_ROWS / rows);
        String sample = String.format(java.util.Locale.ROOT,
                "SELECT tsv FROM document_embeddings TABLESAMPLE SYSTEM (%.3f) REPEATABLE (42)", percent);

        Number sampled = (Number) entityManager.createNativeQuery(
                "SELECT count(*) FROM (" + sample + ") s").getSingleResult();
        long sampleRows = sampled == null ? 0 : sampled.longValue();

        Map<String, Double> shares = new HashMap<>();
        if (sampleRows > 0) {
            List<Object[]> stats = entityManager.createNativeQuery(
                            "SELECT word, ndoc FROM ts_stat(?1) WHERE ndoc >= ?2")
                    .setParameter(1, sample)
                    .setParameter(2, Math.max(1.0, rareFraction * sampleRows))
                    .getResultList();
            for (Object[] row : stats) {
                shares.put((String) row[0], ((Number) row[1]).doubleValue() / sampleRows);
            }
        }
        List<String> stems = entityManager.createNativeQuery(
                        "SELECT lexeme FROM unnest(to_tsvector('english', ?1))")
                .setParameter(1, QUESTION_WORDS)
                .getResultList();
        questionWords = java.util.Set.copyOf(stems);
        frequent = shares;
        loadedAt = System.currentTimeMillis();
        long common = shares.values().stream().filter(s -> s >= commonFraction).count();
        LOG.info("Full-text word frequencies: {} sampled chunks ({}%), {} common words dropped from queries, "
                        + "{} more not treated as rare; {} ms",
                sampleRows, String.format(java.util.Locale.ROOT, "%.1f", percent), common,
                shares.size() - common, loadedAt - start);
    }
}
