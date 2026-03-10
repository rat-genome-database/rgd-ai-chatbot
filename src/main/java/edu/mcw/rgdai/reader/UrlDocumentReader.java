package edu.mcw.rgdai.reader;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.ai.document.DocumentReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;


public class UrlDocumentReader implements DocumentReader {
    private static final Logger LOG = LoggerFactory.getLogger(UrlDocumentReader.class);
    private final String url;
    private final int timeout;

    public UrlDocumentReader(String url) {
        this(url, 30000);
    }

    public UrlDocumentReader(String url, int timeout) {
        this.url = url;
        this.timeout = timeout;
    }

    @Override
    public List<org.springframework.ai.document.Document> get() {
        try {
            LOG.info("Fetching content from URL: {}", url);
            Document jsoupDoc = Jsoup.connect(url)
                    .timeout(timeout)
                    .get();

            // Remove junk elements
            jsoupDoc.select("script, style, iframe, noscript, nav, footer, .navbar, #messageVue, .chat-popup").remove();

            // Detect page type and extract accordingly
            String content = extractContent(jsoupDoc);

            String title = jsoupDoc.title();

            Map<String, Object> metadata = Map.of(
                    "source", url,
                    "title", title,
                    "type", "url"
            );

            LOG.info("Successfully fetched content from URL: {}, content length: {} chars",
                    url, content.length());

            return List.of(new org.springframework.ai.document.Document(content, metadata));
        }
        catch (IOException e) {
            LOG.error("Failed to fetch URL content: {}", url, e);
            return Collections.emptyList();
        }
    }

    /**
     * Detect page type and route to appropriate extractor
     */
    private String extractContent(Document doc) {
        if (isClinicalTrialPage(doc)) {
            LOG.info("Detected clinical trial page, using specialized extractor");
            return extractClinicalTrialContent(doc);
        } else {
            LOG.info("Generic webpage detected, using generic extractor");
            return extractGenericContent(doc);
        }
    }

    /**
     * Detect if this is a clinical trial page
     */
    private boolean isClinicalTrialPage(Document doc) {
        return doc.select("table.ctReportTable").size() > 0 ||
               doc.select("h3.ctSubHeading").size() > 0 ||
               doc.select("h2.brief-title").size() > 0 ||
               url.contains("/clinicalTrials/report/") ||
               url.contains("/report/clinicalTrials/");
    }

    /**
     * Extract structured content from clinical trial pages
     */
    private String extractClinicalTrialContent(Document doc) {
        StringBuilder content = new StringBuilder();

        // Remove navigation elements (but NOT form - data is inside form!)
        doc.select(".sidenav").remove();

        // Extract NCTID
        String nctId = extractNCTID(doc);
        LOG.debug("Extracted NCTID: {}", nctId);
        if (nctId != null) {
            content.append("--- CLINICAL TRIAL: ").append(nctId).append(" ---\n\n");
        }

        // Extract title
        Element mainHeading = doc.select("h2.brief-title").first();
        if (mainHeading != null) {
            String title = mainHeading.text().trim();
            LOG.debug("Extracted title: {}", title);
            if (!title.isEmpty()) {
                content.append("Title: ").append(title).append("\n\n");
            }
        } else {
            LOG.warn("No h2.brief-title found");
        }

        // Extract all sections with their tables
        Elements sections = doc.select("div.dynamic-heading");
        LOG.debug("Found {} sections", sections.size());

        for (Element sectionDiv : sections) {
            Element sectionHeading = sectionDiv.select("h3.ctSubHeading").first();
            if (sectionHeading != null) {
                String sectionTitle = sectionHeading.text().trim();
                LOG.debug("Processing section: {}", sectionTitle);
                if (!sectionTitle.isEmpty() && !sectionTitle.equalsIgnoreCase("Summary")) {
                    content.append("\n=== ").append(sectionTitle).append(" ===\n");
                }
            }

            // Find tables after this section
            Element nextElement = sectionDiv.nextElementSibling();
            int tableCount = 0;
            while (nextElement != null) {
                if (nextElement.hasClass("dynamic-heading")) {
                    break;
                }

                if (nextElement.tagName().equals("table") && nextElement.hasClass("ctReportTable")) {
                    String tableData = extractTableData(nextElement);
                    LOG.debug("Extracted {} chars from table", tableData.length());
                    content.append(tableData);
                    tableCount++;
                }

                nextElement = nextElement.nextElementSibling();
            }
            LOG.debug("Extracted {} tables for section", tableCount);
        }

        // Extract external links
        String links = extractExternalLinks(doc);
        LOG.debug("Extracted {} chars from external links", links.length());
        content.append(links);

        String result = content.toString().trim();
        LOG.info("Total extracted content length: {} chars", result.length());
        return result;
    }

    /**
     * Extract content from generic webpages
     */
    private String extractGenericContent(Document doc) {
        StringBuilder content = new StringBuilder();

        Element mainContent = doc.select("#main, .main, .content, main, article, .container").first();
        if (mainContent == null) {
            mainContent = doc.body();
        }

        // Extract main heading
        Element h1 = mainContent.select("h1").first();
        if (h1 != null) {
            content.append("=== ").append(h1.text().trim()).append(" ===\n\n");
        }

        // Extract tables
        Elements tables = mainContent.select("table");
        for (Element table : tables) {
            content.append(extractGenericTableData(table));
        }

        // Extract definition lists
        Elements dls = mainContent.select("dl");
        for (Element dl : dls) {
            Elements terms = dl.select("dt");
            Elements definitions = dl.select("dd");
            for (int i = 0; i < Math.min(terms.size(), definitions.size()); i++) {
                String term = terms.get(i).text().trim();
                String definition = definitions.get(i).text().trim();
                if (!term.isEmpty() && !definition.isEmpty()) {
                    content.append(term).append(": ").append(definition).append("\n");
                }
            }
        }

        // Extract sections with headers
        Elements headers = mainContent.select("h2, h3, h4");
        for (Element header : headers) {
            String headerText = header.text().trim();
            if (!headerText.isEmpty()) {
                content.append("\n").append(headerText).append(":\n");

                Element next = header.nextElementSibling();
                while (next != null && !next.tagName().matches("h[1-6]")) {
                    String text = next.text().trim();
                    if (!text.isEmpty() && text.length() < 500) {
                        content.append(text).append("\n");
                    }

                    if (next.tagName().equals("ul") || next.tagName().equals("ol")) {
                        Elements items = next.select("li");
                        for (Element item : items) {
                            content.append("- ").append(item.text().trim()).append("\n");
                        }
                    }

                    next = next.nextElementSibling();
                }
            }
        }

        String result = content.toString().trim();
        if (result.length() < 100) {
            result = mainContent.text();
        }

        return result;
    }

    /**
     * Extract NCTID
     */
    private String extractNCTID(Document doc) {
        Elements rows = doc.select("table.ctReportTable tr");
        for (Element row : rows) {
            Elements cells = row.select("td");
            if (cells.size() >= 2) {
                String label = cells.get(0).text().trim();
                if (label.equalsIgnoreCase("NCTID")) {
                    String value = cells.get(1).text();
                    return value.replaceAll("\\(.*?\\)", "").trim();
                }
            }
        }
        return null;
    }

    /**
     * Extract ALL data from clinical trial table - no filtering
     */
    private String extractTableData(Element table) {
        StringBuilder tableContent = new StringBuilder();
        Elements rows = table.select("tr");

        for (Element row : rows) {
            Elements cells = row.select("td");

            if (cells.size() >= 2) {
                String label = cells.get(0).text().trim();
                String value = cells.get(1).text().trim();

                // Clean up common junk from values
                value = value.replaceAll("\\(View at.*?\\)", "").trim();
                value = value.replaceAll("\\(Click here for.*?\\)", "").trim();

                // Output EVERYTHING - even if value is empty
                if (!label.isEmpty()) {
                    tableContent.append(label).append(": ").append(value).append("\n");
                }
            }
        }

        return tableContent.toString();
    }

    /**
     * Extract data from generic tables
     */
    private String extractGenericTableData(Element table) {
        StringBuilder tableContent = new StringBuilder();
        Elements rows = table.select("tr");

        for (Element row : rows) {
            Elements cells = row.select("td, th");

            if (cells.size() == 2) {
                String label = cells.get(0).text().trim();
                String value = cells.get(1).text().trim();

                if (!label.isEmpty()) {
                    tableContent.append(label).append(": ").append(value).append("\n");
                }
            } else if (cells.size() > 0) {
                for (Element cell : cells) {
                    String text = cell.text().trim();
                    if (!text.isEmpty()) {
                        tableContent.append(text).append(" | ");
                    }
                }
                tableContent.append("\n");
            }
        }

        return tableContent.toString();
    }

    /**
     * Extract external links grouped by type
     */
    private String extractExternalLinks(Document doc) {
        StringBuilder linksContent = new StringBuilder();
        Elements linkHeadings = doc.select("h5.link-type-heading");

        if (linkHeadings.isEmpty()) {
            return "";
        }

        linksContent.append("\n=== Resources/Links ===\n");

        for (Element heading : linkHeadings) {
            String linkType = heading.text().trim();
            if (!linkType.isEmpty()) {
                linksContent.append("\n").append(linkType).append(":\n");

                Element listElement = heading.nextElementSibling();
                if (listElement != null && listElement.tagName().equals("ul")
                        && listElement.hasClass("external-links-list")) {

                    Elements listItems = listElement.select("li");
                    for (Element li : listItems) {
                        String itemText = li.text().trim();
                        if (!itemText.isEmpty()) {
                            linksContent.append("- ").append(itemText).append("\n");
                        }
                    }
                }
            }
        }

        return linksContent.toString();
    }
}