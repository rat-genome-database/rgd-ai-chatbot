package edu.mcw.rgdai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.repository.ReportObjectProjection;
import edu.mcw.rgdai.vectorstore.PostgresVectorStoreOpenAI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The RGD page a chat was opened from: {@code /assistant/chat?page=gene-report&id=2004}.
 *
 * <p>Page types are listed in {@code page-context.json}. A page names its type and, for a report
 * page, the record it shows. Both come from the browser, so neither is trusted as text: the type
 * must be a configured key and the ID must look like an RGD ID or an ontology accession. The
 * record is then looked up in the index, and only what the index holds reaches the prompt.</p>
 */
@Service
public class PageContextService {

    private static final Logger LOG = LoggerFactory.getLogger(PageContextService.class);

    private static final Pattern RGD_ID = Pattern.compile("\\d{1,10}");
    private static final Pattern ACCESSION = Pattern.compile("[A-Za-z]{1,12}:\\d{1,12}");

    /** A page type from page-context.json. */
    public record PageType(String key, String label, String idKind, List<String> help,
                           List<String> objectSuggestions, List<String> pageSuggestions) {}

    /** A resolved page: its type, the record it shows (if any), and the reports to draw on. */
    public static final class PageContext {
        public final PageType type;
        /** The validated ID from the page, or null. */
        public final String id;
        /** Symbol for genes, QTLs, strains and cell lines; title for terms and references. */
        public final String subject;
        public final String species;
        /** Gene, Qtl, Strain, Cellline or Reference when the ID is in report_object, else null. */
        public final String objectType;
        /** Reports for the record on the page; empty when it has none or the page names none. */
        public final List<String> reportNames;
        /** The page's help articles that are in the index. */
        public final List<String> helpNames;

        PageContext(PageType type, String id, String subject, String species, String objectType,
                    List<String> reportNames, List<String> helpNames) {
            this.type = type;
            this.id = id;
            this.subject = subject;
            this.species = species;
            this.objectType = objectType;
            this.reportNames = reportNames;
            this.helpNames = helpNames;
        }

        public boolean hasSubject() {
            return subject != null && !subject.isBlank();
        }

        /** How the page is described to the model, e.g. "the RGD gene report page for A2m (rat, RGD ID 2004)". */
        public String describe() {
            StringBuilder sb = new StringBuilder("the RGD ").append(type.label());
            if (hasSubject()) {
                sb.append(" for ").append(subject).append(" (");
                if (species != null && !species.isBlank()) {
                    sb.append(species.toLowerCase()).append(", ");
                }
                sb.append("rgd".equals(type.idKind()) ? "RGD ID " : "").append(id).append(")");
            }
            return sb.toString();
        }

        /** Example questions for the welcome card: about the record first, then about the page. */
        public List<String> suggestions() {
            List<String> out = new ArrayList<>();
            if (hasSubject()) {
                for (String s : type.objectSuggestions()) {
                    out.add(s.replace("{name}", subject));
                }
            }
            out.addAll(type.pageSuggestions());
            return out.size() > 4 ? out.subList(0, 4) : out;
        }
    }

    private final DocumentEmbeddingOpenAIRepository repository;
    private final VectorStore vectorStore;
    private final Map<String, PageType> pageTypes;

    public PageContextService(DocumentEmbeddingOpenAIRepository repository,
                              @Qualifier("openaiVectorStore") VectorStore vectorStore) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.pageTypes = load();
    }

    private static Map<String, PageType> load() {
        Map<String, PageType> types = new LinkedHashMap<>();
        try (InputStream in = new ClassPathResource("page-context.json").getInputStream()) {
            JsonNode pages = new ObjectMapper().readTree(in).path("pages");
            pages.fields().forEachRemaining(e -> {
                JsonNode p = e.getValue();
                types.put(e.getKey(), new PageType(e.getKey(), p.path("label").asText(e.getKey()),
                        p.path("idKind").asText("none"), strings(p.path("help")),
                        strings(p.path("objectSuggestions")), strings(p.path("pageSuggestions"))));
            });
            LOG.info("Page context: {} page type(s) loaded", types.size());
        } catch (Exception e) {
            LOG.error("Could not read page-context.json; chats opened from RGD pages will have no page context: {}",
                    e.getMessage());
        }
        return Collections.unmodifiableMap(types);
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return List.copyOf(out);
    }

    /**
     * The context for a page, or null when {@code page} is absent or not a configured type.
     * An ID that is malformed, or that the index does not hold, leaves the page's help in place
     * and drops the record.
     */
    public PageContext resolve(String page, String id) {
        if (page == null || page.isBlank()) {
            return null;
        }
        PageType type = pageTypes.get(page.trim());
        if (type == null) {
            LOG.info("Page context: unknown page type '{}' ignored", page.length() > 40 ? page.substring(0, 40) : page);
            return null;
        }
        if (!(vectorStore instanceof PostgresVectorStoreOpenAI store)) {
            return null;
        }

        String validId = validId(type, id);
        String subject = null;
        String species = null;
        String objectType = null;
        List<String> reportNames = new ArrayList<>();

        if (validId != null) {
            if ("rgd".equals(type.idKind())) {
                Optional<ReportObjectProjection> object = repository.findReportObjectByRgdId(Long.parseLong(validId));
                if (object.isPresent()) {
                    ReportObjectProjection o = object.get();
                    subject = o.getSymbol();
                    species = o.getSpecies();
                    objectType = o.getObjectType();
                    if (o.getFileName() != null) {
                        reportNames.add(o.getFileName());
                    }
                }
            }
            if (reportNames.isEmpty()) {
                reportNames.addAll(store.reportNamesForId(validId));
            }
            if (subject == null && !reportNames.isEmpty()) {
                subject = titleOf(reportNames.get(0));
            }
            if (reportNames.isEmpty()) {
                LOG.info("Page context: {} '{}' is not in the index", type.key(), validId);
            }
        }

        List<String> helpNames = new ArrayList<>();
        for (String wp : type.help()) {
            List<String> names = store.reportNamesForId(wp);
            if (names.isEmpty()) {
                LOG.warn("Page context: help article {} for '{}' is not in the index", wp, type.key());
            } else {
                helpNames.add(names.get(0));
            }
        }

        return new PageContext(type, reportNames.isEmpty() ? null : validId,
                reportNames.isEmpty() ? null : subject, species, objectType, reportNames, helpNames);
    }

    /** The ID if it has the shape the page type takes, else null. */
    private static String validId(PageType type, String id) {
        if (id == null) {
            return null;
        }
        String trimmed = id.trim();
        boolean ok = switch (type.idKind()) {
            case "rgd" -> RGD_ID.matcher(trimmed).matches();
            case "accession" -> ACCESSION.matcher(trimmed).matches();
            default -> false;
        };
        if (!ok && !trimmed.isEmpty()) {
            LOG.info("Page context: id '{}' does not fit page type '{}'",
                    trimmed.length() > 40 ? trimmed.substring(0, 40) : trimmed, type.key());
        }
        return ok ? trimmed.toUpperCase() : null;
    }

    /**
     * The record's title from its report name: "RGD Ontology Term Report - hypertension (DOID:10763)"
     * gives "hypertension". Report names put the title between the first " - " and the final " (".
     */
    static String titleOf(String reportName) {
        int dash = reportName.indexOf(" - ");
        int paren = reportName.lastIndexOf(" (");
        if (dash < 0 || paren <= dash + 3) {
            return reportName;
        }
        return reportName.substring(dash + 3, paren).trim();
    }
}
