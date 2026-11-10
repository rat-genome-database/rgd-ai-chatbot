package edu.mcw.rgdai.vectorstore;

import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The distinct report names (file_name) in the index, held in memory for the file-name arm.
 *
 * <p>The arm used to ask Postgres, for every word of the question,
 * {@code SELECT DISTINCT file_name ... WHERE LOWER(file_name) LIKE '%word%' LIMIT 11}. A word
 * that matches some report stops early, but one that matches none (most words of a question)
 * reads the whole table: about 0.9 s per word on rgd_rag_dev, growing with the index.
 * Matching against the names in memory takes milliseconds whatever the word.</p>
 *
 * <p>The names are read with one index-only scan (0.2 s for 95,000 names; about 2 s and
 * 70 MB at 800,000) in the background at startup and every {@code refreshHours}, so reports
 * embedded since are picked up without a restart. Until the first read finishes,
 * {@link #snapshot()} is null and the caller queries Postgres as before.</p>
 */
class FileNameIndex {

    private static final Logger LOG = LoggerFactory.getLogger(FileNameIndex.class);

    private final EntityManager entityManager;
    private final long refreshMillis;

    /** The current snapshot; null until the first read finishes. */
    private volatile Snapshot snapshot;
    private volatile long loadedAt;
    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "file-name-index");
        t.setDaemon(true);
        return t;
    });

    FileNameIndex(EntityManager entityManager, double refreshHours) {
        this.entityManager = entityManager;
        this.refreshMillis = (long) (refreshHours * 3_600_000L);
        refreshInBackground();
    }

    /** The names as last read, or null while the first read is still running. */
    Snapshot snapshot() {
        if (System.currentTimeMillis() - loadedAt > refreshMillis) {
            refreshInBackground();
        }
        return snapshot;
    }

    /**
     * The report names, sorted (so the names a word picks are the same on every run), and the
     * same names lowercased and joined by newlines into one string. Matching a word is then
     * one {@code indexOf} pass over that string, about 1 ms per word at 95,000 names, where
     * comparing name by name took about 19 ms (180 ms at 760,000).
     */
    static final class Snapshot {
        private final String[] names;
        private final String text;     // lowercased names, '\n' after each
        private final int[] starts;    // offset of each name in text

        Snapshot(String[] names) {
            this.names = names;
            this.starts = new int[names.length];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < names.length; i++) {
                starts[i] = sb.length();
                sb.append(names[i].toLowerCase(Locale.ROOT).replace('\n', ' ')).append('\n');
            }
            this.text = sb.toString();
        }

        int size() {
            return names.length;
        }

        /**
         * Up to {@code limit} names containing {@code word} (lowercase) anywhere: what
         * {@code LOWER(file_name) LIKE '%word%'} matched, except that {@code _} and {@code %}
         * in the word are literal rather than wildcards.
         */
        List<String> containing(String word, int limit) {
            return find(word, limit, false);
        }

        /**
         * Up to {@code limit} names containing {@code word} (lowercase) as a whole word: what
         * the Postgres regex {@code \mword\M} matched. A word character is a letter, digit or
         * underscore, as in Postgres.
         */
        List<String> containingWord(String word, int limit) {
            return find(word, limit, true);
        }

        private List<String> find(String word, int limit, boolean wholeWord) {
            List<String> out = new ArrayList<>();
            if (word.isEmpty() || word.indexOf('\n') >= 0) {
                return out;
            }
            int from = 0;
            while (out.size() < limit) {
                int at = text.indexOf(word, from);
                if (at < 0) {
                    break;
                }
                int end = at + word.length();
                if (wholeWord && ((at > 0 && isWordChar(text.charAt(at - 1)))
                        || (end < text.length() && isWordChar(text.charAt(end))))) {
                    from = at + 1;   // not a whole word here; look further in the same name
                    continue;
                }
                int name = nameAt(at);
                out.add(names[name]);
                // One entry per name: carry on from the next name.
                from = name + 1 < starts.length ? starts[name + 1] : text.length();
            }
            return out;
        }

        /** The name whose text contains offset {@code at}. */
        private int nameAt(int at) {
            int i = Arrays.binarySearch(starts, at);
            return i >= 0 ? i : -i - 2;
        }

        private static boolean isWordChar(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }
    }

    private void refreshInBackground() {
        if (!loading.compareAndSet(false, true)) {
            return;
        }
        loader.submit(() -> {
            try {
                load();
            } catch (Exception e) {
                // Keep the names read before, if any; retry in 5 minutes.
                LOG.error("Could not read report names for file-name matching: {}", e.getMessage());
                loadedAt = System.currentTimeMillis() - refreshMillis + 300_000L;
            } finally {
                loading.set(false);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void load() {
        long start = System.currentTimeMillis();
        List<String> rows = entityManager.createNativeQuery(
                        "SELECT DISTINCT file_name FROM document_embeddings WHERE file_name IS NOT NULL ORDER BY file_name")
                .getResultList();
        Snapshot loaded = new Snapshot(rows.toArray(new String[0]));
        snapshot = loaded;
        loadedAt = System.currentTimeMillis();
        LOG.info("File-name index: {} report names loaded in {} ms", loaded.size(), loadedAt - start);
    }
}
