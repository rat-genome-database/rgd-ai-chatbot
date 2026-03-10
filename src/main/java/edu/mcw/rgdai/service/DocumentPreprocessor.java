package edu.mcw.rgdai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class DocumentPreprocessor {
    private static final Logger LOG = LoggerFactory.getLogger(DocumentPreprocessor.class);

    // Universal patterns for cleaning ANY document content
    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern TABLE_SEPARATORS = Pattern.compile("\\|[-:]+\\|");
    private static final Pattern EXCESSIVE_WHITESPACE = Pattern.compile("\\s{3,}");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\n{3,}");
    private static final Pattern MARKDOWN_LINKS = Pattern.compile("\\[([^\\]]+)\\]\\([^\\)]+\\)");
    private static final Pattern PAGE_REFERENCES = Pattern.compile("(?i)(page\\s+\\d+|\\d+\\s*$)");
    private static final Pattern FOOTNOTE_REFS = Pattern.compile("\\[\\d+\\]");


    public List<Document> preprocessDocuments(List<Document> documents) {
        List<Document> processedDocs = new ArrayList<>();

        for (Document doc : documents) {
            String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
            LOG.info("Preprocessing document: {}", filename);

            String cleanedContent = cleanContent(doc.getContent());

            // Skip if content is too short after cleaning
            if (cleanedContent.length() < 50) {
                LOG.warn("Skipping document chunk - too short after cleaning: {} chars", cleanedContent.length());
                continue;
            }

            // Create new document with cleaned content
            Document cleanedDoc = new Document(cleanedContent, doc.getMetadata());
            processedDocs.add(cleanedDoc);

            LOG.debug("Cleaned content length: {} -> {}", doc.getContent().length(), cleanedContent.length());
        }

        LOG.info("Preprocessed {} documents into {} clean documents", documents.size(), processedDocs.size());
        return processedDocs;
    }

    private String cleanContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "";
        }

        String cleaned = content;

        // Step 1: Remove HTML/XML tags (from web content, exported docs, etc.)
        cleaned = HTML_TAGS.matcher(cleaned).replaceAll("");

        // Step 2: Convert markdown links to readable text [text](url) -> text
        cleaned = MARKDOWN_LINKS.matcher(cleaned).replaceAll("$1");

        // Step 3: Extract content from tables instead of removing them
        cleaned = extractTableContent(cleaned);

        // Step 4: Remove footnote references [1], [2], etc.
        cleaned = FOOTNOTE_REFS.matcher(cleaned).replaceAll("");

        // Step 5: Remove page references and numbers at end of lines
        cleaned = PAGE_REFERENCES.matcher(cleaned).replaceAll("");

        // Step 6: Clean up whitespace
        cleaned = EXCESSIVE_WHITESPACE.matcher(cleaned).replaceAll(" ");
        cleaned = MULTIPLE_NEWLINES.matcher(cleaned).replaceAll("\n\n");

        // Step 7: Process line by line to remove empty lines
        cleaned = cleaned.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");

        return cleaned.trim();
    }

    /**
     * Extract meaningful content from table structures
     */
    private String extractTableContent(String content) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n");

        for (String line : lines) {
            // Skip table separator lines (|---|---|)
            if (TABLE_SEPARATORS.matcher(line).find()) {
                continue;
            }

            // If it's a table row, extract the content
            if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                String extractedContent = extractFromTableRow(line);
                if (!extractedContent.trim().isEmpty()) {
                    result.append(extractedContent).append("\n");
                }
            } else {
                // Regular content line
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /**
     * Extract meaningful content from a single table row
     */
    private String extractFromTableRow(String tableLine) {
        // Split by pipes and extract non-empty, meaningful content
        String[] cells = tableLine.split("\\|");
        StringBuilder content = new StringBuilder();

        for (String cell : cells) {
            String trimmed = cell.trim();

            // Skip empty cells, numbers only, or cells with just formatting
            if (!trimmed.isEmpty() &&
                    !trimmed.matches("\\d+") && // Skip pure numbers like "1", "2", "3"
                    !trimmed.matches("[\\s\\-_=\\*]+") && // Skip formatting chars
                    trimmed.length() > 1) {

                content.append(trimmed).append(" ");
            }
        }

        return content.toString().trim();
    }

    /**
     * Check if a line is mostly formatting (works for any document type)
     */
    private boolean isFormattingOnlyLine(String line) {
        if (line == null) return true;

        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;

        // Lines that are mostly punctuation/formatting characters
        long formatChars = trimmed.chars()
                .filter(c -> "|-_=*+~^<>[]{}().,;:!?\"'`".indexOf(c) >= 0 || Character.isWhitespace(c))
                .count();

        // If more than 80% is formatting, consider it a formatting line
        return formatChars > (trimmed.length() * 0.8);
    }

    /**
     * Check if line has minimum meaningful content
     */
    private boolean hasMinimumContent(String line) {
        if (line == null) return false;

        String trimmed = line.trim();
        if (trimmed.length() < 3) return false;

        // Count actual words (sequences of letters/numbers)
        long wordCount = trimmed.split("\\s+").length;

        // Need at least 2 words or be a meaningful single term
        return wordCount >= 2 || (wordCount == 1 && trimmed.length() > 10);
    }

    /**
     * Universal quality check for document chunks
     */
    public boolean isQualityChunk(String content) {
        if (content == null || content.trim().length() < 50) {
            return false;
        }

        String trimmed = content.trim();

        // Count meaningful words (not just numbers or single characters)
        String[] words = trimmed.split("\\s+");
        long meaningfulWords = 0;
        for (String word : words) {
            if (word.length() > 1 && !word.matches("\\d+") && !word.matches("[\\p{Punct}]+")) {
                meaningfulWords++;
            }
        }

        // Need at least 10 meaningful words
        if (meaningfulWords < 10) {
            LOG.debug("Rejecting chunk - too few meaningful words: {}", meaningfulWords);
            return false;
        }

        // Check that it's not mostly formatting characters
        long formatChars = trimmed.chars()
                .filter(c -> "|-_=*+~^<>[]{}()".indexOf(c) >= 0)
                .count();

        if (formatChars > trimmed.length() * 0.6) {
            LOG.debug("Rejecting chunk - too much formatting: {}%", (formatChars * 100) / trimmed.length());
            return false;
        }

        return true;
    }
}