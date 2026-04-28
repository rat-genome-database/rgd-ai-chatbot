package edu.mcw.rgdai.service;

import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportConverterService {

    private static final Logger LOG = LoggerFactory.getLogger(ReportConverterService.class);

    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String RGD_BASE = "https://rgd.mcw.edu";

    private final FlexmarkHtmlConverter mdConverter;
    private final HttpClient httpClient;

    public ReportConverterService() {
        MutableDataSet options = new MutableDataSet();
        options.set(TablesExtension.FORMAT_TABLE_ADJUST_COLUMN_WIDTH, false);
        options.set(TablesExtension.FORMAT_TABLE_TRIM_CELL_WHITESPACE, true);
        this.mdConverter = FlexmarkHtmlConverter.builder(options).build();
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // ================================================================
    // Inner classes
    // ================================================================

    public static class ReportMetadata {
        public String title = "";
        public String rgdId = "";
        public String reportType = "";
        public String entityName = "";
        public String sourceLink = "";
        public String displayName = "";
    }

    public static class SummaryExtraction {
        public Element infoTable;
        public String summaryMd = "";
        public String orthoMd = "";
        public String positionMd = "";
    }

    public static class ConversionResult {
        public String markdown;
        public String rawHtml;
        public String processedHtml;
        public ReportMetadata metadata;
        public long elapsedMs;
        public int charCount;
        public int lineCount;
    }

    // ================================================================
    // Public API
    // ================================================================

    public String fetchHtml(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + url);
        }
        return response.body();
    }

    public String fetchDamagingVariants(String baseUrl, int rgdId, String symbol)
            throws IOException, InterruptedException {
        String url = baseUrl + "/rgdweb/report/gene/damagingVariants.html?id=" + rgdId + "&s=" + symbol;
        return fetchHtml(url);
    }

    private String extractBaseUrl(String url) {
        try {
            URI uri = URI.create(url);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://" + uri.getHost() + port;
        } catch (Exception e) {
            return RGD_BASE;
        }
    }

    public ConversionResult convert(String html, String sourceUrl) {
        long start = System.currentTimeMillis();
        String originalHtml = html;

        // Fix self-closing table tags (<table ... />) that cause Jsoup to create
        // empty tables with all rows orphaned as plain text outside
        html = html.replaceAll("<table([^>]*)\\s*/>", "<table$1>");

        Document doc = Jsoup.parse(html);

        // 1. Extract metadata
        ReportMetadata meta = extractMetadata(doc, sourceUrl);

        // 1b. Fetch and inject AJAX-loaded sections (damaging variants for gene reports)
        if ("gene".equals(meta.reportType) && !meta.rgdId.isEmpty() && !meta.entityName.isEmpty()) {
            try {
                String baseUrl = extractBaseUrl(sourceUrl);
                int rgdId = Integer.parseInt(meta.rgdId);
                String dvHtml = fetchDamagingVariants(baseUrl, rgdId, meta.entityName);
                Element dvContainer = doc.getElementById("ajax_damaging_variants");
                if (dvContainer != null && dvHtml != null && !dvHtml.trim().isEmpty()) {
                    // Inject the AJAX fragment into the placeholder div (mirrors browser behavior)
                    Document dvDoc = Jsoup.parseBodyFragment(dvHtml);
                    for (Element child : dvDoc.body().children()) {
                        dvContainer.appendChild(child.clone());
                    }
                    LOG.info("Injected damaging variants HTML ({} chars)", dvHtml.length());
                }
            } catch (Exception e) {
                LOG.warn("Failed to fetch damaging variants: {}", e.getMessage());
            }
        }

        // 2. Extract summary, orthologs, position
        SummaryExtraction summary = extractSummaryTable(doc);

        // 3. Get content area
        Element content = doc.getElementById("contentArea");
        if (content == null) {
            LOG.error("No contentArea found");
            ConversionResult result = new ConversionResult();
            result.markdown = "ERROR: No contentArea found in HTML";
            result.metadata = meta;
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }
        LOG.info("Pipeline [contentArea]: {} children, {} text chars",
                content.children().size(), content.text().length());

        // 3b. Rescue orphaned sections
        rescueOrphanedSections(doc, content);

        // 3c. Deduplicate headings
        deduplicateHeadings(content);

        // Remove info-table from content (already extracted as summary)
        removeInfoTable(content, summary.infoTable);

        // 4. Toggle annotation view
        toggleAnnotationView(content);
        LOG.info("Pipeline [toggleAnnotation]: {} children, {} text chars",
                content.children().size(), content.text().length());

        // 5a. Remove <h1> elements from body (title is already in buildDocument header)
        content.select("h1").remove();

        // 5. Remove junk elements
        removeJunkElements(content);
        LOG.info("Pipeline [removeJunk]: {} children, {} text chars",
                content.children().size(), content.text().length());

        // Fix URLs and clean artifacts
        fixUrlsAndCleanArtifacts(content);

        // 6. Convert section headings
        convertSectionHeadings(doc, content);

        // 6b. Convert GO sub-headings (Biological Process, Cellular Component, etc.)
        // HTML: <span class="highlight"><u>Text</u></span> → plain text after span stripping.
        // Fix: convert to <h4> so they become proper markdown headings.
        for (Element highlightSpan : content.select("span.highlight")) {
            Element u = highlightSpan.selectFirst("u");
            if (u != null && !u.text().trim().isEmpty()) {
                Element h4 = doc.createElement("h4");
                h4.text(u.text().trim());
                highlightSpan.replaceWith(h4);
            }
        }

        // 7. Neutralize layout tables
        neutralizeLayoutTables(content);
        LOG.info("Pipeline [neutralizeTables]: {} children, {} text chars",
                content.children().size(), content.text().length());

        // 8. Flatten nested tables inside data table cells.
        // Jsoup preserves nested <table> in <td> (unlike html5lib which moves them out).
        // FlexmarkHtmlConverter can't handle tables-within-tables → broken output.
        flattenNestedTables(content);
        LOG.info("Pipeline [flattenNested]: {} children, {} text chars",
                content.children().size(), content.text().length());

        // 8b. Merge two-row table headers (rowspan/colspan → single row).
        // Extracted data tables (e.g. Comparative Map) often have multi-row headers
        // that Flexmark can't handle, producing duplicate header rows.
        mergeMultiRowHeaders(content);

        // 9. Remove br tags
        content.select("br").remove();

        // 9b. Remove empty <thead> elements (e.g. Nucleotide/Protein Sequences have
        // <thead><tr><th></th><th></th></tr></thead> with 2 empty cols but 5 data cols).
        // This prevents FlexmarkHtmlConverter from emitting a malformed separator row.
        for (Element thead : new ArrayList<>(content.select("thead"))) {
            boolean allEmpty = true;
            for (Element th : thead.select("th")) {
                if (!th.text().trim().isEmpty()) { allEmpty = false; break; }
            }
            if (allEmpty) thead.remove();
        }

        // 9c. Promote table headers: RGD annotation tables use <td> with
        // class='headerRow' instead of <th>. FlexmarkHtmlConverter requires
        // <th> to produce markdown table headers with separator rows.
        for (Element hr : content.select("tr.headerRow")) {
            for (Element td : hr.select("> td")) {
                td.tagName("th");
            }
        }

        // 9d. Promote header rows by content heuristic: if the first <tr> of a table
        // has ALL <td> cells with NO <a> links (pure text/bold labels), and the next
        // <tr> has at least one <a> link (data row), promote the first row to <th>.
        // This catches Damaging Variants, QTL Related QTLs, Variant GWAS headers.
        for (Element table : content.select("table")) {
            Elements rows = table.select("> tbody > tr, > tr");
            if (rows.size() < 2) continue;
            Element firstRow = rows.get(0);
            Element secondRow = rows.get(1);
            // Skip if first row already has <th>
            if (!firstRow.select("> th").isEmpty()) continue;
            Elements firstTds = firstRow.select("> td");
            if (firstTds.isEmpty()) continue;
            // Check: first row has NO links in any cell
            boolean firstRowHasLinks = !firstRow.select("> td a").isEmpty();
            if (firstRowHasLinks) continue;
            // Check: second row has at least one link
            boolean secondRowHasLinks = !secondRow.select("> td a").isEmpty();
            if (!secondRowHasLinks) continue;
            // Promote first row <td> to <th>
            for (Element td : firstTds) {
                td.tagName("th");
            }
        }

        // 10. Strip id, style, and class attributes to prevent {#id} noise
        // and unwanted FlexmarkHtmlConverter behavior
        for (Element el : content.getAllElements()) {
            el.removeAttr("id");
            el.removeAttr("style");
            el.removeAttr("class");
        }

        // Disable Jsoup pretty-printing to avoid adding whitespace inside elements
        doc.outputSettings().indentAmount(0).outline(false);

        // Get HTML string and fix table row breaks
        String htmlStr = content.html();
        htmlStr = htmlStr.replaceAll("</tr>\\s*<tr", "</tr>\n<tr");
        htmlStr = htmlStr.replaceAll("</thead>\\s*<tbody", "</thead>\n<tbody");

        // Strip div/span tags but keep their content — equivalent to Python's
        // markdownify strip=['div', 'span', 'br']. This exposes <table> elements
        // so FlexmarkHtmlConverter can properly convert them to markdown tables.
        // Use empty string (not \n) to avoid injecting newlines inside <td> cells
        // which would break FlexmarkHtmlConverter's table parsing.
        htmlStr = htmlStr.replaceAll("</?div[^>]*>", "");
        htmlStr = htmlStr.replaceAll("</?span[^>]*>", "");
        htmlStr = htmlStr.replaceAll("<br\\s*/?>", " ");

        LOG.info("Pipeline: htmlStr length = {} chars", htmlStr.length());

        // 11. Convert to markdown
        String bodyMd = htmlToMarkdown(htmlStr);

        LOG.info("Pipeline: bodyMd length = {} chars", bodyMd.length());
        if (bodyMd.trim().length() < 100) {
            LOG.warn("Pipeline: bodyMd suspiciously short! First 500 chars of htmlStr: {}",
                    htmlStr.substring(0, Math.min(500, htmlStr.length())));
        }

        // 12. Post-cleanup
        bodyMd = postCleanup(bodyMd);

        // Build final document
        String markdown = buildDocument(meta, summary.summaryMd, summary.orthoMd,
                summary.positionMd, bodyMd);

        ConversionResult result = new ConversionResult();
        result.markdown = markdown;
        result.rawHtml = originalHtml;
        result.processedHtml = htmlStr;
        result.metadata = meta;
        result.elapsedMs = System.currentTimeMillis() - start;
        result.charCount = markdown.length();
        result.lineCount = markdown.split("\n").length;

        LOG.info("Converted: {} chars, {} lines, {}ms - {}",
                result.charCount, result.lineCount, result.elapsedMs, meta.displayName);

        return result;
    }

    // ================================================================
    // Pipeline steps
    // ================================================================

    ReportMetadata extractMetadata(Document doc, String sourceUrl) {
        ReportMetadata meta = new ReportMetadata();

        // --- Report type ---
        // 1. Try JS variable: let reportTitle = "gene";
        for (Element script : doc.select("script")) {
            String data = script.data();
            if (data != null && !data.isEmpty()) {
                Matcher m = Pattern.compile("let\\s+reportTitle\\s*=\\s*[\"']([^\"']+)[\"']").matcher(data);
                if (m.find()) {
                    meta.reportType = m.group(1).trim().toLowerCase();
                    break;
                }
            }
        }
        // 2. Fallback: URL path /report/{type}/main.html
        if (meta.reportType.isEmpty() && sourceUrl != null) {
            Matcher m = Pattern.compile("/report/(\\w+)/main\\.html").matcher(sourceUrl);
            if (m.find()) {
                meta.reportType = m.group(1).toLowerCase();
            }
        }

        // --- RGD ID ---
        // 1. Try hidden input
        Element rgdInput = doc.selectFirst("input[name=rgdId]");
        if (rgdInput != null && !rgdInput.attr("value").isEmpty()) {
            meta.rgdId = rgdInput.attr("value");
        }
        // 2. Fallback: URL ?id= parameter
        if (meta.rgdId.isEmpty() && sourceUrl != null) {
            Matcher m = Pattern.compile("[?&]id=(\\d+)").matcher(sourceUrl);
            if (m.find()) {
                meta.rgdId = m.group(1);
            }
        }
        // 3. Fallback: JS variable
        if (meta.rgdId.isEmpty()) {
            for (Element script : doc.select("script")) {
                String data = script.data();
                if (data != null) {
                    Matcher m = Pattern.compile("(?:objectId|rgdId)\\s*=\\s*[\"']?(\\d+)").matcher(data);
                    if (m.find()) {
                        meta.rgdId = m.group(1);
                        break;
                    }
                }
            }
        }

        // --- Entity name ---
        // 1. Try td with font-size:20px
        for (Element td : doc.select("td[style]")) {
            if (td.attr("style").contains("font-size:20px")) {
                meta.title = cleanText(td.text());
                break;
            }
        }
        // 2. Fallback: h1 with colon
        if (meta.title.isEmpty()) {
            for (Element h1 : doc.select("h1")) {
                String text = cleanText(h1.text());
                if (text.contains(":") && text.length() < 300) {
                    meta.title = text;
                    break;
                }
            }
        }
        // 3. Fallback: h3 with colon or substantial text
        if (meta.title.isEmpty()) {
            for (Element h3 : doc.select("h3")) {
                String text = cleanText(h3.text());
                if (text.contains(":") && text.length() < 200) {
                    meta.title = text;
                    break;
                }
                if (text.length() > 10 && !text.startsWith("Related")) {
                    meta.title = text;
                    break;
                }
            }
        }
        // 4. Fallback: page title
        if (meta.title.isEmpty()) {
            Element titleTag = doc.selectFirst("title");
            if (titleTag != null) {
                meta.title = titleTag.text().trim();
                meta.title = meta.title.replaceAll("\\s*-\\s*Rat Genome Database$", "");
                meta.title = meta.title.replaceAll("\\s*Rat Genome Database$", "");
            }
        }

        // --- Entity short name ---
        if (!meta.title.isEmpty()) {
            Matcher m = Pattern.compile("(?:QTL|Strain|Marker|Gene|Variant|Cell Line):\\s*(\\S+)").matcher(meta.title);
            if (m.find()) {
                meta.entityName = m.group(1);
            } else {
                Matcher m2 = Pattern.compile("Project:\\s*(.+)").matcher(meta.title);
                if (m2.find()) {
                    meta.entityName = m2.group(1).trim();
                } else {
                    meta.entityName = meta.title.split("\\(")[0].trim();
                }
                if (meta.entityName.length() > 60) {
                    meta.entityName = meta.entityName.substring(0, 57) + "...";
                }
            }
        }

        // --- Source link ---
        // Always use production URL (rgd.mcw.edu) for source links in markdown,
        // even though we fetch HTML from pipelines.rgd.mcw.edu
        if (sourceUrl != null && !sourceUrl.isEmpty()) {
            meta.sourceLink = sourceUrl
                    .replace("pipelines.rgd.mcw.edu", "rgd.mcw.edu")
                    .replace("stage.rgd.mcw.edu", "rgd.mcw.edu");
        } else if (!meta.reportType.isEmpty() && !meta.rgdId.isEmpty()) {
            String urlType = meta.reportType;
            Map<String, String> typeMap = Map.of(
                    "cell line", "cellline",
                    "hgnc gene family", "geneFamily",
                    "expression study", "expressionStudy",
                    "rgdvariant", "variant"
            );
            if (typeMap.containsKey(meta.reportType)) {
                urlType = typeMap.get(meta.reportType);
            }
            meta.sourceLink = RGD_BASE + "/rgdweb/report/" + urlType + "/main.html?id=" + meta.rgdId;
        }

        // --- Display name ---
        if (!meta.reportType.isEmpty() && !meta.rgdId.isEmpty()) {
            String rtype = meta.reportType.substring(0, 1).toUpperCase() + meta.reportType.substring(1);
            String namePart = !meta.entityName.isEmpty() ? meta.entityName : meta.rgdId;
            meta.displayName = "RGD " + rtype + " Report - " + namePart + " (" + meta.rgdId + ")";
        }

        return meta;
    }

    SummaryExtraction extractSummaryTable(Document doc) {
        SummaryExtraction result = new SummaryExtraction();

        // Try gene-style info-table first
        Element infoTable = doc.getElementById("info-table");

        // Fallback: styled summary table
        if (infoTable == null) {
            Element contentArea = doc.getElementById("contentArea");
            if (contentArea != null) {
                // First try: table with background-color style
                for (Element table : contentArea.select("table[style]")) {
                    String style = table.attr("style");
                    if (style.contains("background-color") && table.outerHtml().length() < 200000) {
                        infoTable = table;
                        break;
                    }
                }
                // Second try: table with class="label" TDs
                if (infoTable == null) {
                    for (Element table : contentArea.select("table")) {
                        if (table.outerHtml().length() > 200000) continue;
                        if (table.closest("div.light-table-border") != null) continue;
                        if (table.closest("#associationsCurator") != null) continue;
                        Elements labelTds = table.select("td.label");
                        if (labelTds.size() >= 2) {
                            infoTable = table;
                            break;
                        }
                    }
                }
            }
        }

        result.infoTable = infoTable;
        if (infoTable == null) return result;

        // Extract summary fields
        Set<String> skipLabels = Set.of("RGD Orthologs", "Alliance Orthologs", "More Info", "Position");
        List<String[]> summaryFields = new ArrayList<>();

        // Get rows from this table only (not nested)
        List<Element> rows = new ArrayList<>();
        Elements tbodies = infoTable.select("> tbody");
        if (!tbodies.isEmpty()) {
            for (Element tbody : tbodies) {
                rows.addAll(tbody.select("> tr"));
            }
        }
        if (rows.isEmpty()) {
            rows.addAll(infoTable.select("> tr"));
        }

        for (Element tr : rows) {
            Elements tds = tr.select("> td");
            if (tds.size() >= 2) {
                String label = cleanText(tds.get(0).text()).replaceAll(":$", "");
                if (label.isEmpty() || skipLabels.contains(label)) continue;
                // Skip if value contains nested tables
                if (!tds.get(1).select("table").isEmpty()) continue;

                // Convert <a> tags to markdown links before extracting text
                Element valueTd = tds.get(1).clone();
                for (Element a : new ArrayList<>(valueTd.select("a[href]"))) {
                    String href = a.attr("href");
                    if (href.startsWith("/")) href = RGD_BASE + href;
                    if (href.startsWith("javascript:")) {
                        a.replaceWith(new TextNode(a.text()));
                    } else {
                        String linkText = cleanText(a.text());
                        if (!linkText.isEmpty()) {
                            a.replaceWith(new TextNode("[" + linkText + "](" + href + ")"));
                        } else {
                            a.remove();
                        }
                    }
                }
                String value = cleanText(valueTd.text());
                if (!value.isEmpty()) {
                    value = value.replace("|", "/");
                    summaryFields.add(new String[]{label, value});
                }
            }
        }

        // Capture colspan rows with substantial text (e.g. reference abstract)
        String abstractText = "";
        for (Element tr : rows) {
            Elements tds = tr.select("> td");
            if (tds.size() == 1) {
                Element td = tds.get(0);
                String colspan = td.attr("colspan");
                if (!colspan.isEmpty() && Integer.parseInt(colspan) >= 2) {
                    String text = cleanText(td.text());
                    if (text.length() > 100) {
                        abstractText = text;
                    }
                }
            }
        }

        if (!summaryFields.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("## Summary\n\n| Field | Value |\n|-------|-------|\n");
            for (String[] field : summaryFields) {
                sb.append("| **").append(field[0]).append("** | ").append(field[1]).append(" |\n");
            }
            sb.append("\n");
            if (!abstractText.isEmpty()) {
                sb.append("\n**Abstract:** ").append(abstractText).append("\n");
            }
            sb.append("\n---\n\n");
            result.summaryMd = sb.toString();
        }

        // Extract ortholog and position tables from info-table.
        // Jsoup auto-wraps <tr> inside <tbody>, so "> tr:has(> th)" misses them.
        // Must also check "> tbody > tr:has(> th)".
        // Multi-row headers: skip super-headers (colspan > 1) and decorative bars.
        for (Element nt : infoTable.select("table")) {
            List<String> headers = new ArrayList<>();
            for (Element tr : nt.select("> thead > tr, > tbody > tr, > tr")) {
                Elements ths = tr.select("> th");
                if (ths.isEmpty()) continue;
                for (Element th : ths) {
                    String cs = th.attr("colspan");
                    if (!cs.isEmpty()) {
                        try { if (Integer.parseInt(cs) > 1) continue; }
                        catch (NumberFormatException ignored) {}
                    }
                    if (th.classNames().contains("orthoExtBar")) continue;
                    headers.add(cleanText(th.text()));
                }
            }

            if (headers.size() < 3 || headers.size() > 15) continue;

            boolean isOrtho = headers.stream().anyMatch(h -> h.contains("Species") || h.contains("Gene symbol"));
            boolean isPosition = headers.stream().anyMatch(h -> h.contains("Assembly") || h.contains("Position"));

            if (isOrtho) {
                if (result.orthoMd.isEmpty()) result.orthoMd = "## Orthologs\n\n";
                result.orthoMd += "| " + String.join(" | ", headers) + " |\n";
                result.orthoMd += "| " + String.join(" | ", Collections.nCopies(headers.size(), "---")) + " |\n";
                for (Element tr : nt.select("> thead > tr, > tbody > tr, > tr")) {
                    Elements tds = tr.select("> td");
                    if (tds.isEmpty()) continue;
                    List<String> row = new ArrayList<>();
                    for (Element td : tds) row.add(extractCellWithLinks(td));
                    if (row.stream().anyMatch(c -> !c.trim().isEmpty())) {
                        while (row.size() < headers.size()) row.add("");
                        if (row.size() > headers.size()) row = row.subList(0, headers.size());
                        result.orthoMd += "| " + String.join(" | ", row) + " |\n";
                    }
                }
                result.orthoMd += "\n";
            } else if (isPosition) {
                if (result.positionMd.isEmpty()) result.positionMd = "## Genomic Position\n\n";
                result.positionMd += "| " + String.join(" | ", headers) + " |\n";
                result.positionMd += "| " + String.join(" | ", Collections.nCopies(headers.size(), "---")) + " |\n";
                for (Element tr : nt.select("> thead > tr, > tbody > tr, > tr")) {
                    Elements tds = tr.select("> td");
                    if (tds.isEmpty()) continue;
                    List<String> row = new ArrayList<>();
                    for (Element td : tds) row.add(extractCellWithLinks(td));
                    if (row.stream().anyMatch(c -> !c.trim().isEmpty())) {
                        while (row.size() < headers.size()) row.add("");
                        if (row.size() > headers.size()) row = row.subList(0, headers.size());
                        result.positionMd += "| " + String.join(" | ", row) + " |\n";
                    }
                }
                result.positionMd += "\n---\n\n";
            }
        }

        if (!result.orthoMd.isEmpty()) {
            result.orthoMd += "---\n\n";
        }

        return result;
    }

    void rescueOrphanedSections(Document doc, Element content) {
        Set<Integer> rescued = new HashSet<>();
        List<Element> orphans = new ArrayList<>();

        for (Element el : doc.select("div.sectionHeading, div.subTitle, div.light-table-border, div.reportTable")) {
            if (el.closest("#contentArea") != null || rescued.contains(System.identityHashCode(el))) {
                continue;
            }
            // Skip if inside an already-rescued wrapper
            boolean insideRescued = false;
            for (Element ancestor : el.parents()) {
                if (rescued.contains(System.identityHashCode(ancestor))) {
                    insideRescued = true;
                    break;
                }
            }
            if (insideRescued) continue;

            Set<String> classes = new HashSet<>(el.classNames());
            if ((classes.contains("light-table-border") || classes.contains("reportTable"))
                    && el.outerHtml().length() < 200000) {
                rescued.add(System.identityHashCode(el));
                orphans.add(el);
            } else if (classes.contains("sectionHeading") || classes.contains("subTitle")) {
                orphans.add(el);
            }
        }

        for (Element orphan : orphans) {
            content.appendChild(orphan);
        }
    }

    void deduplicateHeadings(Element content) {
        Set<String> seenHeadings = new HashSet<>();
        for (Element sh : new ArrayList<>(content.select("div.sectionHeading"))) {
            String text = cleanText(sh.text());
            text = text.replaceAll("Click to see Annotation (Detail|Summary) View", "").trim();
            if (seenHeadings.contains(text)) {
                Element wrapper = sh.parent();
                if (wrapper != null && wrapper.tagName().equals("div") && wrapper != content) {
                    wrapper.remove();
                } else {
                    sh.remove();
                }
            } else {
                seenHeadings.add(text);
            }
        }
    }

    private void removeInfoTable(Element content, Element infoTable) {
        if (infoTable == null) return;
        String infoId = infoTable.id();
        if (!infoId.isEmpty()) {
            Element inContent = content.getElementById(infoId);
            if (inContent != null) {
                inContent.remove();
                return;
            }
        }
        if (infoTable.closest("#contentArea") != null) {
            infoTable.remove();
        }
    }

    void toggleAnnotationView(Element content) {
        Element curator = content.getElementById("associationsCurator");
        Element standard = content.getElementById("associationsStandard");

        if (curator != null) {
            curator.attr("style", "display:block;");
        }

        if (standard != null) {
            Element firstSubtitle = standard.selectFirst("div.subTitle");
            if (firstSubtitle != null) {
                // Remove everything before the first subTitle
                List<Node> toRemove = new ArrayList<>();
                for (Node sibling : standard.childNodes()) {
                    if (sibling == firstSubtitle) break;
                    toRemove.add(sibling);
                }
                for (Node node : toRemove) node.remove();
                standard.unwrap();
            } else {
                standard.remove();
            }
        }

        // Handle project-specific variants
        Element curatorP = content.getElementById("associationsCuratorForProject");
        Element standardP = content.getElementById("associationsForProject");
        if (curatorP != null) curatorP.attr("style", "display:block;");
        if (standardP != null) {
            Element firstSubtitleP = standardP.selectFirst("div.subTitle");
            if (firstSubtitleP != null) {
                List<Node> toRemove = new ArrayList<>();
                for (Node sibling : standardP.childNodes()) {
                    if (sibling == firstSubtitleP) break;
                    toRemove.add(sibling);
                }
                for (Node node : toRemove) node.remove();
                standardP.unwrap();
            } else {
                standardP.remove();
            }
        }

        // Remove duplicate annotation summaries rescued from outside contentArea
        if (curator != null) {
            Set<String> curatorTexts = new HashSet<>();
            for (Element sh : curator.select("div.sectionHeading")) {
                String text = cleanText(sh.text());
                text = text.replaceAll("Click to see Annotation (Detail|Summary) View", "").trim();
                if (!text.isEmpty()) curatorTexts.add(text);
            }
            for (Element sh : new ArrayList<>(content.select("div.sectionHeading"))) {
                if (sh.closest("#associationsCurator") != null) continue;
                String text = cleanText(sh.text());
                text = text.replaceAll("Click to see Annotation (Detail|Summary) View", "").trim();
                if (curatorTexts.contains(text)) {
                    Element wrapper = sh.parent();
                    if (wrapper != null && wrapper.tagName().equals("div") && wrapper != content) {
                        wrapper.remove();
                    }
                }
            }
        }
    }

    void removeJunkElements(Element content) {
        // Remove title bar table (already extracted as metadata).
        // Find the td with font-size:20px, then remove its CLOSEST table (innermost),
        // not the outermost wrapper table that contains the entire page.
        Element titleTd = content.selectFirst("td[style*='font-size:20px'], td[style*='font-size: 20px']");
        if (titleTd != null) {
            Element titleTable = titleTd.closest("table");
            if (titleTable != null) titleTable.remove();
        }

        // Remove script, style, link, noscript, iframe, meta
        content.select("script, style, link, noscript, iframe, meta").remove();
        // Remove form, select, button
        content.select("form, select, button").remove();
        // Remove input, img
        content.select("input, img").remove();

        // Remove empty anchor tags (images removed, leaving empty links like [](url))
        for (Element a : new ArrayList<>(content.select("a"))) {
            if (a.text().trim().isEmpty()) {
                a.remove();
            }
        }

        // Remove orphaned table elements outside any table context
        for (Element el : new ArrayList<>(content.select("> tr, > td, > th, > thead, > tbody"))) {
            el.remove();
        }

        // Toolbar tables (Full Report / CSV / TAB / Printer)
        for (Element table : new ArrayList<>(content.select("table"))) {
            if (table.classNames().isEmpty() && table.id().isEmpty()) {
                String text = table.text().trim();
                if (text.contains("Full Report") && text.contains("Printer") && text.length() < 500) {
                    table.remove();
                }
            }
        }

        // Pager/model divs
        content.select("div.pager, div.modelsViewContent, div.search-and-pager").remove();

        // Annotation checkboxes/toggles
        content.select("div.only-show-annot-background").remove();
        content.select("a.associationsToggle").remove();

        // Checkbox labels
        content.select("label.hideEviText").remove();

        // Sidebar nav
        Element nav = content.getElementById("reportMainSidebar");
        if (nav != null) nav.remove();

        // Species image div
        Element speciesImg = content.getElementById("species-image");
        if (speciesImg != null) speciesImg.remove();

        // Tab menu
        Element searchHeader = content.getElementById("searchResultHeader");
        if (searchHeader != null) searchHeader.remove();

        // AngularJS artifacts — remove elements with Angular directives
        content.select("[ng-click], [ng-repeat], [ng-bind], [ng-bind-html], [ng-show], [ng-if]").remove();
        // Remove {{...}} template expressions and UI control text
        removeAngularExpressions(content);

        // Title bar table with "Analyze" button
        for (Element table : new ArrayList<>(content.select("table"))) {
            String text = table.text().trim();
            if (text.contains("Analyze") && text.length() < 200) {
                table.remove();
                break;
            }
        }

        // Variants summary artifact: single-cell table with ". Variants in <symbol>N total Variants"
        for (Element table : new ArrayList<>(content.select("table"))) {
            String text = table.text().trim();
            if (text.contains("total Variants") && text.contains("Variants in") && text.length() < 200) {
                table.remove();
            }
        }
    }

    private void removeAngularExpressions(Element content) {
        for (Element el : content.getAllElements()) {
            for (TextNode tn : new ArrayList<>(el.textNodes())) {
                String text = tn.getWholeText();
                // Remove {{...}} expressions (keep surrounding text)
                if (text.contains("{{")) {
                    text = text.replaceAll("\\{\\{[^}]*\\}\\}", "");
                    tn.text(text);
                }
                // Remove UI control text that leaks from Angular apps
                String trimmed = tn.getWholeText().trim();
                if (trimmed.equals("Loading...") ||
                    trimmed.startsWith("Too many to show") ||
                    trimmed.startsWith("Filter by Expression") ||
                    trimmed.startsWith("Download them if you would like") ||
                    trimmed.startsWith("Showing 0 of 0") ||
                    trimmed.equalsIgnoreCase("show sequence") ||
                    trimmed.equalsIgnoreCase("hide sequence")) {
                    tn.remove();
                }
            }
        }
    }

    void fixUrlsAndCleanArtifacts(Element content) {
        for (Element a : new ArrayList<>(content.select("a[href]"))) {
            String href = a.attr("href");
            if (href.startsWith("/")) {
                a.attr("href", RGD_BASE + href);
            } else if (href.startsWith("javascript:")) {
                a.replaceWith(new TextNode(a.text()));
            }
        }
    }

    void convertSectionHeadings(Document doc, Element content) {
        // subTitle divs -> h2
        for (Element div : new ArrayList<>(content.select("div.subTitle"))) {
            String headingText = cleanText(div.text());
            headingText = headingText.replaceAll("Click to see Annotation (Detail|Summary) View", "").trim();
            if (!headingText.isEmpty()) {
                Element h2 = doc.createElement("h2");
                h2.text(headingText);
                div.replaceWith(h2);
            }
        }

        // sectionHeading divs -> h3
        for (Element div : new ArrayList<>(content.select("div.sectionHeading"))) {
            String headingText = cleanText(div.text());
            headingText = headingText.replaceAll("Click to see Annotation (Detail|Summary) View", "").trim();
            if (!headingText.isEmpty()) {
                // Check if there's an h4 inside
                Element h4 = div.selectFirst("h4");
                if (h4 != null) headingText = h4.text().trim();

                Element h3 = doc.createElement("h3");
                h3.text(headingText);
                div.replaceWith(h3);
            }
        }
    }

    boolean isLayoutTable(Element table) {
        if (!table.classNames().isEmpty() || !table.id().isEmpty()) return false;
        if (table.closest("div.annotation-detail") != null) return false;
        // Tables inside annotation report sections are data tables
        if (table.closest("div.reportTable") != null) return false;
        if (table.closest("div.light-table-border") != null) return false;
        // Tables with their own header row are data tables
        if (!table.select("> thead > tr > th, > tr > th").isEmpty()) return false;
        return !table.select("table").isEmpty(); // contains nested tables
    }

    void neutralizeLayoutTables(Element content) {
        List<Element> layoutTables = new ArrayList<>();
        for (Element table : content.select("table")) {
            if (isLayoutTable(table)) layoutTables.add(table);
        }
        // Process innermost first
        Collections.reverse(layoutTables);

        for (Element table : layoutTables) {
            // Only convert this table's OWN children (not nested tables)
            for (Element child : table.select("> *")) {
                if (child.tagName().equals("tbody") || child.tagName().equals("thead") || child.tagName().equals("tfoot")) {
                    for (Element tr : child.select("> tr")) {
                        for (Element cell : tr.select("> td, > th")) {
                            cell.tagName("div");
                        }
                        tr.tagName("div");
                    }
                    child.tagName("div");
                } else if (child.tagName().equals("tr")) {
                    for (Element cell : child.select("> td, > th")) {
                        cell.tagName("div");
                    }
                    child.tagName("div");
                }
            }
            table.tagName("div");
        }
    }

    void flattenNestedTables(Element content) {
        // Jsoup preserves nested <table> elements inside <td> cells, unlike html5lib
        // which moves them out per HTML5 spec. FlexmarkHtmlConverter cannot handle
        // tables-within-tables, causing broken output or missing sections.
        // Fix: data sub-tables (with <th>) get extracted as siblings after parent table.
        //      Simple layout sub-tables (no <th>) get flattened to inline text.
        // Multi-pass: each pass handles only leaf nested tables (no further nesting).

        for (int pass = 0; pass < 5; pass++) {
            // Use CSS selector to find tables that are direct children of <td> or <th>.
            // This is more reliable than closest("td") which can have edge cases.
            // Note: don't filter by leaf-only (table.select("table").isEmpty()) because
            // Jsoup's DOM may show nested tables due to malformed HTML auto-correction.
            List<Element> nestedTables = new ArrayList<>(content.select("td > table, th > table"));
            if (nestedTables.isEmpty()) {
                LOG.info("Pipeline [flattenNested] pass {}: no nested tables found", pass);
                break;
            }

            int extracted = 0, flattened = 0;
            // Track insertion point per parent table to maintain document order
            Map<Element, Element> insertionPoints = new HashMap<>();
            Set<Element> rowsToRemove = new HashSet<>();
            for (Element nt : nestedTables) {
                if (!nt.select("th").isEmpty()) {
                    // Data sub-table (has <th> headers): extract to after parent table.
                    // Also grab the label text from sibling <td> in the same row
                    // (e.g. species name in Comparative Map, "Position:" in Reference Sequences).
                    Element parentCell = (Element) nt.parent();
                    Element parentRow = parentCell.parent();
                    Element parentTable = parentCell.closest("table");
                    if (parentTable != null) {
                        String labelText = "";
                        if (parentRow != null) {
                            for (Element sibling : parentRow.children()) {
                                if (!sibling.equals(parentCell) && !sibling.text().trim().isEmpty()) {
                                    labelText = sibling.text().trim();
                                    break;
                                }
                            }
                        }

                        nt.remove();
                        Element insertAfter = insertionPoints.getOrDefault(parentTable, parentTable);

                        if (!labelText.isEmpty()) {
                            Element label = new Element("h4");
                            label.html("<b>" + labelText + "</b>");
                            insertAfter.after(label);
                            label.after(nt);
                        } else {
                            insertAfter.after(nt);
                        }
                        insertionPoints.put(parentTable, nt);
                        extracted++;

                        // Mark parent row for removal — its label is now in the h4,
                        // and the nested table was extracted, leaving an empty row
                        if (parentRow != null && !labelText.isEmpty()) {
                            rowsToRemove.add(parentRow);
                        }
                    }
                } else {
                    // Simple layout sub-table: flatten to inline text
                    Elements cells = nt.select("td, th");
                    StringBuilder sb = new StringBuilder();
                    for (Element cell : cells) {
                        String cellHtml = cell.html().trim();
                        if (!cellHtml.isEmpty()) {
                            if (sb.length() > 0) sb.append(" ");
                            sb.append(cellHtml);
                        }
                    }
                    if (sb.length() > 0) {
                        Element wrapper = new Element("span");
                        wrapper.html(sb.toString());
                        nt.replaceWith(wrapper);
                    } else {
                        nt.remove();
                    }
                    flattened++;
                }
            }
            // Remove emptied rows that held nested tables + labels
            for (Element row : rowsToRemove) {
                row.remove();
            }
            LOG.info("Pipeline [flattenNested] pass {}: extracted {} data tables, flattened {} layout tables, removed {} label rows",
                    pass, extracted, flattened, rowsToRemove.size());
        }
    }

    /**
     * Merge two-row table headers (rowspan/colspan) into a single row.
     * HTML pattern: first row has th(rowspan=2) + th(colspan=N), second row has N individual th.
     * Flexmark can't render multi-row headers, producing duplicate rows.
     * Fix: replace colspan cell with individual sub-cells from second row, remove second row.
     */
    void mergeMultiRowHeaders(Element content) {
        int merged = 0;
        for (Element table : content.select("table")) {
            // Find first two consecutive header rows (both must have <th> cells)
            Elements headerRows = table.select("> thead > tr");
            if (headerRows.size() < 2) {
                headerRows = new Elements();
                for (Element tr : table.select("> tbody > tr, > tr")) {
                    if (!tr.select("> th").isEmpty()) {
                        headerRows.add(tr);
                        if (headerRows.size() == 2) break;
                    } else {
                        break;
                    }
                }
            }
            if (headerRows.size() < 2) continue;

            Element firstRow = headerRows.get(0);
            Element secondRow = headerRows.get(1);

            // First row must have at least one th with colspan > 1
            boolean hasColspan = false;
            for (Element th : firstRow.select("> th")) {
                String cs = th.attr("colspan");
                if (!cs.isEmpty()) {
                    try { if (Integer.parseInt(cs) > 1) { hasColspan = true; break; } }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (!hasColspan) continue;

            // Merge: replace colspan cells with sub-cells, keep rowspan cells
            Elements subCells = secondRow.select("> th");
            int subIdx = 0;
            for (Element th : new ArrayList<>(firstRow.select("> th"))) {
                int colspan = 1;
                String cs = th.attr("colspan");
                if (!cs.isEmpty()) {
                    try { colspan = Integer.parseInt(cs); } catch (NumberFormatException ignored) {}
                }
                if (colspan > 1) {
                    for (int i = 0; i < colspan && subIdx < subCells.size(); i++) {
                        Element sub = subCells.get(subIdx++).clone();
                        sub.removeAttr("colspan");
                        sub.removeAttr("rowspan");
                        th.before(sub);
                    }
                    th.remove();
                } else {
                    th.removeAttr("rowspan");
                }
            }
            secondRow.remove();
            merged++;
        }
        if (merged > 0) {
            LOG.info("Pipeline [mergeMultiRowHeaders]: merged {} tables", merged);
        }
    }

    String htmlToMarkdown(String htmlStr) {
        return mdConverter.convert(htmlStr);
    }

    String postCleanup(String markdown) {
        // Unescape markdown characters
        markdown = markdown.replace("\\-", "-");
        markdown = markdown.replace("\\(", "(");
        markdown = markdown.replace("\\)", ")");
        markdown = markdown.replace("\\_", "_");
        markdown = markdown.replace("\\[", "[");
        markdown = markdown.replace("\\]", "]");
        markdown = markdown.replace("\\*", "*");
        markdown = markdown.replace("\\|", "|");
        markdown = markdown.replace("\\+", "+");
        markdown = markdown.replace("\\.", ".");
        markdown = markdown.replace("&nbsp;", " ");
        markdown = markdown.replace("\u00a0", " ");

        // Remove {#id} attribute markup from headings and other elements
        markdown = markdown.replaceAll("\\s*\\{#[^}]*}", "");

        // Remove ++text++ (FlexmarkHtmlConverter's insert/underline markup)
        markdown = markdown.replaceAll("\\+\\+(.+?)\\+\\+", "$1");

        // Remove leaked HTML tags
        markdown = markdown.replaceAll("<br\\s*/?>", "");

        // Safety: remove any remaining Angular expressions and UI junk
        markdown = markdown.replaceAll("\\{\\{[^}]*\\}\\}", "");
        markdown = markdown.replaceAll("\\*\\*Loading\\.\\.\\.\\*\\*", "");
        markdown = markdown.replaceAll("Loading\\.\\.\\.", "");

        // Convert setext headings to ATX style (matching Python's heading_style='atx')
        markdown = markdown.replaceAll("(?m)^(.+)\\n={2,}\\s*$", "# $1");
        markdown = markdown.replaceAll("(?m)^(.+)\\n-{2,}\\s*$", "## $1");

        // Remove truly empty table rows
        markdown = markdown.replaceAll("(?m)^\\|(?:\\s*\\|\\s*)+$\\n", "");

        // Normalize table separator rows: reduce overly-wide separator cells to minimal |---|.
        // FlexmarkHtmlConverter may pad separator cells to match column widths despite
        // FORMAT_TABLE_ADJUST_COLUMN_WIDTH=false, producing 48k+ char separator rows.
        markdown = markdown.replaceAll("-{4,}", "---");

        // Replace FlexmarkHtmlConverter's <hr> rendering with standard markdown hr
        markdown = markdown.replaceAll("\\*{3}\\s+\\*{2}\\s+\\*\\s+\\*{2}\\s+\\*{3}", "---");

        // Remove orphaned table separator rows (separator at start of table, no header above).
        // These appear when FlexmarkHtmlConverter generates a separator for headerless tables.
        markdown = markdown.replaceAll("\\n\\n\\|[-: |]+\\|[ \\t]*\\n(?=\\|)", "\n\n");

        // Remove excessive whitespace
        markdown = markdown.replaceAll("[ \\t]+\\n", "\n");
        markdown = markdown.replaceAll("\\n{3,}", "\n\n");

        return markdown;
    }

    String buildDocument(ReportMetadata meta, String summaryMd, String orthoMd,
                         String positionMd, String bodyMd) {
        StringBuilder sb = new StringBuilder();

        if (!meta.displayName.isEmpty()) {
            sb.append("<!-- file_name: ").append(meta.displayName).append(" -->\n");
        }
        sb.append("# ").append(meta.title).append("\n\n");
        if (!meta.sourceLink.isEmpty()) {
            sb.append("> **Source:** [Rat Genome Database (RGD)](").append(meta.sourceLink).append(")\n\n---\n\n");
        }
        sb.append(summaryMd);
        sb.append(orthoMd);
        sb.append(positionMd);
        sb.append(bodyMd.trim());
        sb.append("\n\n---\n\n*This report was extracted from the [Rat Genome Database (RGD)](https://rgd.mcw.edu), Medical College of Wisconsin.*\n");

        return sb.toString();
    }

    // ================================================================
    // Utility
    // ================================================================

    private static String cleanText(String text) {
        if (text == null) return "";
        return text.replace("\u00a0", " ").trim();
    }

    /** Convert &lt;a&gt; tags in an element to markdown link syntax, then return cleaned text. */
    private String extractCellWithLinks(Element td) {
        Element clone = td.clone();
        for (Element a : new ArrayList<>(clone.select("a[href]"))) {
            String href = a.attr("href");
            if (href.startsWith("/")) href = RGD_BASE + href;
            if (href.startsWith("javascript:")) {
                a.replaceWith(new TextNode(a.text()));
            } else {
                String linkText = cleanText(a.text());
                if (!linkText.isEmpty()) {
                    a.replaceWith(new TextNode("[" + linkText + "](" + href + ")"));
                } else {
                    a.remove();
                }
            }
        }
        return cleanText(clone.text()).replace("|", "/");
    }
}
