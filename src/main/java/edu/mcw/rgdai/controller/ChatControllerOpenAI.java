package edu.mcw.rgdai.controller;

import edu.mcw.rgd.dao.impl.DocumentEmbeddingDAO;
import edu.mcw.rgd.datamodel.ReportObjectDE;
import edu.mcw.rgd.datamodel.ReportPositionDE;
import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
import edu.mcw.rgdai.repository.RegionMemberProjection;
import edu.mcw.rgdai.repository.SymbolSpeciesProjection;
import edu.mcw.rgdai.vectorstore.PostgresVectorStoreOpenAI;
import edu.mcw.rgdai.service.RecaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.openai.OpenAiChatOptions;

@RestController
@RequestMapping("/chat-openai")
public class ChatControllerOpenAI {

    private static final Logger LOG = LoggerFactory.getLogger(ChatControllerOpenAI.class);
    private static final Logger TIMING_LOG = LoggerFactory.getLogger("TIMING");

    private ChatClient chatClient;
    private ChatClient rewriteClient;
    private final VectorStore openaiVectorStore;
    private final String configuredModel;
    private final String rewriteModel;
    private final boolean rewriteEnabled;
    private final int rewriteHistoryMessages;
    private final boolean fanoutEnabled;
    private final int fanoutMaxEntities;
    private final int fanoutMaxDocs;
    private final int fanoutMinDocsPerEntity;
    private final int fanoutMaxDocsPerEntity;
    private final int maxContextDocs;
    private final boolean exactLookupEnabled;
    /** Sections kept out of the candidate set unless the question is about a region. */
    private final List<String> excludedSections;
    /** Object types whose tables are fully populated, so an exhaustive answer is trustworthy. */
    private final List<String> enumerationTypes;
    private final String enumerationAssembly;
    private final int enumerationMaxRows;
    private final boolean speciesDisambiguationEnabled;
    private final DocumentEmbeddingDAO reportDAO = new DocumentEmbeddingDAO();
    private final String corpusCoverageNote;
    private static final ObjectMapper JSON = new ObjectMapper();
    private InMemoryChatMemory chatMemory;
    private final ChatModel openAiChatModel;
    private final DocumentEmbeddingOpenAIRepository repository;
    private final RecaptchaService recaptchaService;
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();
    private final ExecutorService fanoutExecutor;

    public ChatControllerOpenAI(
            ApplicationContext context,
            @Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
            @Value("${spring.ai.openai.model}") String configuredModel,
            @Value("${chatbot.query-rewrite.model:gpt-4o-mini}") String rewriteModel,
            @Value("${chatbot.query-rewrite.enabled:true}") boolean rewriteEnabled,
            @Value("${chatbot.query-rewrite.history-messages:8}") int rewriteHistoryMessages,
            @Value("${chatbot.fanout.enabled:true}") boolean fanoutEnabled,
            @Value("${chatbot.fanout.max-entities:25}") int fanoutMaxEntities,
            @Value("${chatbot.fanout.max-docs:60}") int fanoutMaxDocs,
            @Value("${chatbot.fanout.min-docs-per-entity:2}") int fanoutMinDocsPerEntity,
            @Value("${chatbot.fanout.max-docs-per-entity:8}") int fanoutMaxDocsPerEntity,
            @Value("${chatbot.fanout.threads:5}") int fanoutThreads,
            @Value("${chatbot.retrieval.max-context-docs:40}") int maxContextDocs,
            @Value("${chatbot.exact-lookup.enabled:true}") boolean exactLookupEnabled,
            @Value("${chatbot.retrieval.excluded-sections:}") String excludedSectionsCsv,
            @Value("${chatbot.enumeration.object-types:}") String enumerationTypesCsv,
            @Value("${chatbot.enumeration.default-assembly:GRCr8}") String enumerationAssembly,
            @Value("${chatbot.enumeration.max-rows:200}") int enumerationMaxRows,
            @Value("${chatbot.species-disambiguation.enabled:true}") boolean speciesDisambiguationEnabled,
            @Value("${chatbot.corpus.coverage-note:The knowledge base currently contains RAT (Rattus norvegicus) records ONLY. Human, mouse and all other species are not loaded yet.}") String corpusCoverageNote,
            DocumentEmbeddingOpenAIRepository repository,
            RecaptchaService recaptchaService) {

        LOG.info("Initializing OpenAI ChatController with system messages for doc context");
        this.openaiVectorStore = openaiVectorStore;
        this.configuredModel = configuredModel;
        this.rewriteModel = rewriteModel;
        this.rewriteEnabled = rewriteEnabled;
        this.rewriteHistoryMessages = rewriteHistoryMessages;
        this.fanoutEnabled = fanoutEnabled;
        this.fanoutMaxEntities = fanoutMaxEntities;
        this.fanoutMaxDocs = fanoutMaxDocs;
        this.fanoutMinDocsPerEntity = fanoutMinDocsPerEntity;
        this.fanoutMaxDocsPerEntity = fanoutMaxDocsPerEntity;
        // Bounded on purpose: each fan-out search is an embedding API call plus a DB
        // query, and a 25-entity question would otherwise open 25 of each at once.
        this.fanoutExecutor = Executors.newFixedThreadPool(Math.max(1, fanoutThreads));
        this.maxContextDocs = maxContextDocs;
        this.exactLookupEnabled = exactLookupEnabled;
        // Section names contain commas nowhere, so a plain CSV split is safe and keeps the
        // list editable in config as the corpus grows new boilerplate sections.
        this.excludedSections = List.copyOf(splitCsv(excludedSectionsCsv));
        this.enumerationTypes = List.copyOf(splitCsv(enumerationTypesCsv));
        this.enumerationAssembly = enumerationAssembly;
        this.enumerationMaxRows = Math.max(1, enumerationMaxRows);
        this.speciesDisambiguationEnabled = speciesDisambiguationEnabled;
        this.corpusCoverageNote = corpusCoverageNote;
        this.repository = repository;
        this.recaptchaService = recaptchaService;
        this.chatMemory = new InMemoryChatMemory();

        LOG.info("Configured OpenAI Model from properties: {}", configuredModel);

        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        ChatModel foundModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("openai")) {
                foundModel = entry.getValue();
                LOG.info("Found OpenAI ChatModel: {}", entry.getKey());
                LOG.info("ChatModel Class: {}", entry.getValue().getClass().getName());
                break;
            }
        }
        if (foundModel == null) {
            throw new RuntimeException("OpenAI ChatModel not found!");
        }
        this.openAiChatModel = foundModel;
        this.chatClient = buildClient(openAiChatModel, this.chatMemory);
        // Deliberately advisor-free: the query-rewrite call must NOT be written into
        // conversation memory, or rewritten queries would pollute the transcript.
        this.rewriteClient = ChatClient.builder(openAiChatModel).build();
        LOG.info("OpenAI ChatClient initialized successfully with model: {}", configuredModel);
        LOG.info("Query rewriting enabled={} using model: {}", rewriteEnabled, rewriteModel);
    }

    /** Split a comma-separated config value, dropping blanks. Never returns null. */
    private static List<String> splitCsv(String csv) {
        List<String> values = new ArrayList<>();
        if (csv != null) {
            for (String s : csv.split(",")) {
                String trimmed = s.trim();
                if (!trimmed.isEmpty()) {
                    values.add(trimmed);
                }
            }
        }
        return values;
    }

    private ChatClient buildClient(ChatModel model, InMemoryChatMemory memory) {
        return ChatClient.builder(model)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(memory)
                )
                .build();
    }

    @PostMapping
    public Answer chat(@RequestBody Question question,
                       Authentication user,
                       HttpServletRequest request) {

        LOG.info("OpenAI - Received question: {}", question.getQuestion());
        LOG.info("Processing with model: {}", configuredModel);

        String conversationId = getOrCreateConversationId(user, request);
        LOG.info("Using conversation ID: {}", conversationId);

        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the RGD AI Assistant. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            PreProcessResult pp = preProcess(question, request, conversationId);

            String response = chatClient.prompt()
                    .system(pp.systemMessage)
                    .user(question.getQuestion())
                    .options(OpenAiChatOptions.builder()
                            .withStreamUsage(false)
                            .withModel(configuredModel)
                            .withTemperature(1.0)
                            .build())
                    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                    .call()
                    .content();

            long t6 = System.currentTimeMillis();
            LOG.info("OpenAI - Generated response with system message approach using model: {}", configuredModel);

            // Post-process response to wrap filenames with [[...]] markers for frontend linking
            response = wrapFilenamesInResponse(response, pp.usedFilenames);

            long t7 = System.currentTimeMillis();

            // Log timing summary
            long total = t7 - pp.t0;
            long queryRewrite = pp.tRewrite - pp.t0;
            long vectorSearch = pp.t1 - pp.tRewrite;
            long rerank = pp.t2 - pp.t1;
            long contextBuild = pp.t3 - pp.t2;
            long openaiApi = t6 - pp.t3;
            long postProcess = t7 - t6;

            TIMING_LOG.info("TIMING: [Q: \"{}\"] [Search Q: \"{}\"]", question.getQuestion(), pp.searchQuery);
            TIMING_LOG.info("  Query Rewrite:       {}ms ({}s)", queryRewrite, String.format("%.2f", queryRewrite / 1000.0));
            TIMING_LOG.info("  Vector Search:       {}ms ({}s)", vectorSearch, String.format("%.2f", vectorSearch / 1000.0));
            TIMING_LOG.info("  Re-ranking:          {}ms ({}s)", rerank, String.format("%.2f", rerank / 1000.0));
            TIMING_LOG.info("  Context Building:    {}ms ({}s)", contextBuild, String.format("%.2f", contextBuild / 1000.0));
            TIMING_LOG.info("  OpenAI API Call:     {}ms ({}s)", openaiApi, String.format("%.2f", openaiApi / 1000.0));
            TIMING_LOG.info("  Post-processing:     {}ms ({}s)", postProcess, String.format("%.2f", postProcess / 1000.0));
            TIMING_LOG.info("  -----------------------------");
            TIMING_LOG.info("  TOTAL:               {}ms ({}s)", total, String.format("%.2f", total / 1000.0));

            return new Answer(response);

        } catch (Exception e) {
            LOG.error("OpenAI - Error generating response with model {}: {}", configuredModel, e.getMessage(), e);
            return new Answer("OpenAI Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody Question question,
                                  Authentication user,
                                  HttpServletRequest request) {

        LOG.info("OpenAI STREAM - Received question: {}", question.getQuestion());
        LOG.info("Processing with model: {}", configuredModel);

        SseEmitter emitter = new SseEmitter(180_000L); // 3 minute timeout

        String conversationId = getOrCreateConversationId(user, request);
        HttpSession session = request.getSession();

        // Handle greetings immediately
        if (isGreeting(question.getQuestion())) {
            streamExecutor.execute(() -> {
                try {
                    String greeting = "Hello! I'm the RGD AI Assistant. I can help you with questions about the documents in my knowledge base. What would you like to know?";
                    emitter.send(SseEmitter.event().name("done")
                            .data("{\"fullResponse\":\"" + escapeJson(greeting) + "\"}"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        // Pre-processing (synchronous - vector search, re-ranking, context building)
        PreProcessResult pp;
        try {
            pp = preProcess(question, request, conversationId);
        } catch (Exception e) {
            LOG.error("OpenAI STREAM - Error in pre-processing: {}", e.getMessage(), e);
            streamExecutor.execute(() -> {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("Error: " + e.getMessage()));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            });
            return emitter;
        }

        // Capture references for async use
        final PreProcessResult ppFinal = pp;

        emitter.onTimeout(() -> LOG.warn("SSE stream timed out"));
        emitter.onError(e -> LOG.error("SSE stream error: {}", e.getMessage()));

        // Async streaming
        streamExecutor.execute(() -> {
            StringBuilder fullResponse = new StringBuilder();

            try {
                chatClient.prompt()
                        .system(ppFinal.systemMessage)
                        .user(question.getQuestion())
                        .options(OpenAiChatOptions.builder()
                                .withModel(configuredModel)
                                .withTemperature(1.0)
                                .build())
                        .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                        .stream()
                        .content()
                        .doOnNext(token -> {
                            try {
                                fullResponse.append(token);
                                emitter.send(SseEmitter.event().name("token").data(token));
                            } catch (IOException e) {
                                LOG.error("Error sending SSE token", e);
                            }
                        })
                        .doOnError(error -> {
                            LOG.error("OpenAI STREAM error: {}", error.getMessage(), error);
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data("Error: " + error.getMessage()));
                            } catch (IOException ignored) {}
                            emitter.completeWithError(error);
                        })
                        .doOnComplete(() -> {
                            try {
                                long t6 = System.currentTimeMillis();

                                // Server-side post-processing
                                String processed = wrapFilenamesInResponse(
                                        fullResponse.toString(), ppFinal.usedFilenames);

                                // Send final done event with processed response
                                emitter.send(SseEmitter.event().name("done")
                                        .data("{\"fullResponse\":\"" + escapeJson(processed) + "\"}"));
                                emitter.complete();

                                // Timing
                                long t7 = System.currentTimeMillis();
                                long total = t7 - ppFinal.t0;
                                long openaiApi = t6 - ppFinal.t3;
                                long postProcess = t7 - t6;
                                long queryRewrite = ppFinal.tRewrite - ppFinal.t0;
                                long vectorSearch = ppFinal.t1 - ppFinal.tRewrite;
                                long rerank = ppFinal.t2 - ppFinal.t1;
                                long contextBuild = ppFinal.t3 - ppFinal.t2;

                                TIMING_LOG.info("STREAM TIMING: [Q: \"{}\"] [Search Q: \"{}\"]", question.getQuestion(), ppFinal.searchQuery);
                                TIMING_LOG.info("  Query Rewrite:       {}ms ({}s)", queryRewrite, String.format("%.2f", queryRewrite / 1000.0));
                                TIMING_LOG.info("  Vector Search:       {}ms ({}s)", vectorSearch, String.format("%.2f", vectorSearch / 1000.0));
                                TIMING_LOG.info("  Re-ranking:          {}ms ({}s)", rerank, String.format("%.2f", rerank / 1000.0));
                                TIMING_LOG.info("  Context Building:    {}ms ({}s)", contextBuild, String.format("%.2f", contextBuild / 1000.0));
                                TIMING_LOG.info("  OpenAI API Stream:   {}ms ({}s)", openaiApi, String.format("%.2f", openaiApi / 1000.0));
                                TIMING_LOG.info("  Post-processing:     {}ms ({}s)", postProcess, String.format("%.2f", postProcess / 1000.0));
                                TIMING_LOG.info("  -----------------------------");
                                TIMING_LOG.info("  TOTAL:               {}ms ({}s)", total, String.format("%.2f", total / 1000.0));

                            } catch (IOException e) {
                                LOG.error("Error sending done event", e);
                                emitter.completeWithError(e);
                            }
                        })
                        .subscribe();

            } catch (Exception e) {
                LOG.error("Error setting up stream: {}", e.getMessage(), e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data("Error: " + e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }

    @PostMapping("/reset-memory")
    public ResponseEntity<Map<String, String>> resetChatMemory(
            Authentication user,
            HttpServletRequest request) {

        LOG.info("OpenAI - Reset chat memory requested");
        try {
            String oldId = getOrCreateConversationId(user, request);
            chatMemory.clear(oldId);
            LOG.info("OpenAI - Cleared memory for conversation ID: {}", oldId);

            request.getSession().removeAttribute("openai_conversation_id");

            String newId = "reset_" + System.currentTimeMillis();
            request.getSession().setAttribute("openai_conversation_id", newId);
            LOG.info("OpenAI - Started new conversation with ID: {}", newId);

            Map<String, String> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "Chat memory cleared successfully");
            resp.put("oldConversationId", oldId);
            resp.put("newConversationId", newId);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            LOG.error("OpenAI - Error resetting chat memory: {}", e.getMessage(), e);
            Map<String, String> resp = new HashMap<>();
            resp.put("status", "error");
            resp.put("message", "Failed to reset chat memory");
            return ResponseEntity.status(500).body(resp);
        }
    }

    /**
     * Rewrite a follow-up question into a standalone retrieval query using conversation
     * history.
     *
     * Retrieval embeds the query text directly, so an anaphoric follow-up such as
     * "are there any others" or "why did you not find those" carries no entities and
     * matches nothing above the similarity threshold. This resolves those references
     * against the recent transcript before the vector search runs.
     *
     * Falls back to the original question on any failure - a degraded query is far
     * better than a failed request.
     */
    /**
     * Give coordinates for every record the user named, from the position table.
     *
     * <p>Asked "what are the positions of them" about four genes, retrieval can only answer for
     * whichever gene reports it happened to surface — and the assistant then positions two of
     * the four and explains that the others weren't in its context. The coordinates are a
     * column, so this reads them directly: every named record gets an answer, or is visibly
     * missing from the table rather than quietly dropped.</p>
     *
     * <p>Not gated on populated types: this answers only about records the user named, so a
     * partially loaded corpus can make it silent but never wrong.</p>
     *
     * @return a formatted table to prepend to the context, or null when nothing resolves
     */
    private String tryNamedPositions(QueryAnalysis analysis) {
        if (!analysis.positionQuery || analysis.entities == null || analysis.entities.isEmpty()) {
            return null;
        }

        Set<String> lowered = new LinkedHashSet<>();
        for (String e : analysis.entities) {
            if (e != null && !e.isBlank()) {
                lowered.add(e.trim().toLowerCase());
            }
        }
        if (lowered.isEmpty()) {
            return null;
        }

        String assembly = (analysis.enumAssembly == null || analysis.enumAssembly.isBlank())
                ? enumerationAssembly : analysis.enumAssembly.trim();

        try {
            List<RegionMemberProjection> rows = repository.findPositionsBySymbols(lowered, assembly);
            if (rows.isEmpty()) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("--- COMPLETE DATABASE LIST: positions on %s ---%n", assembly));
            sb.append("| Symbol | Name | Chromosome | Start | Stop |\n");
            Set<String> found = new LinkedHashSet<>();
            for (RegionMemberProjection m : rows) {
                found.add(m.getSymbol().toLowerCase());
                sb.append(String.format("| %s | %s | %s | %s | %s |%n",
                        m.getSymbol(),
                        m.getName() == null ? "" : m.getName(),
                        m.getChromosome() == null ? "" : m.getChromosome(),
                        m.getStartPos() == null ? "" : m.getStartPos().toString(),
                        m.getStopPos() == null ? "" : m.getStopPos().toString()));
            }

            // Name what is genuinely absent, so a missing record is stated rather than skipped.
            List<String> missing = lowered.stream().filter(s -> !found.contains(s)).toList();
            if (!missing.isEmpty()) {
                sb.append(String.format("NOT FOUND on %s: %s%n", assembly, String.join(", ", missing)));
            }
            sb.append(String.format("--- END COMPLETE DATABASE LIST ---%n%n"));

            LOG.info("Named positions: {} of {} requested record(s) resolved on {}",
                    found.size(), lowered.size(), assembly);
            return sb.toString();

        } catch (Exception e) {
            LOG.error("Named position lookup failed, retrieving normally: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Resolve a model-supplied object type to the exact spelling stored in {@code report_object}.
     *
     * <p>{@code object_type} is compared case-sensitively in SQL, but the type arrives from the
     * query-analysis model, which may well answer "QTL" or "genes" where the column holds
     * "Qtl" and "Gene". Matching the configured list case-insensitively and then querying with
     * the CONFIGURED spelling keeps a harmless wording difference from turning into an empty
     * result that quietly falls back to ordinary retrieval.</p>
     *
     * @return the configured spelling, or null when the type is not enabled
     */
    private String canonicalObjectType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return null;
        }
        String trimmed = rawType.trim();
        for (String configured : enumerationTypes) {
            if (configured.equalsIgnoreCase(trimmed)) {
                return configured;
            }
        }
        return null;
    }

    /**
     * Answer "what lies in the region of X" with real coordinates.
     *
     * <p>A gene report's "QTLs in Region" table names the overlapping QTLs but carries none of
     * their positions — those live on each QTL's own report. Asked for the positions of the
     * QTLs around A2m, retrieval can therefore only ever produce the names, and the assistant
     * correctly but unhelpfully explains that the coordinates are not in the excerpt. Resolving
     * the anchor's span and intersecting it against {@code report_position} answers it outright.</p>
     *
     * <p>Gated on the same populated-types list as enumeration: a partial overlap list reads
     * just as authoritative as a complete one.</p>
     *
     * @return a formatted table to prepend to the context, or null to retrieve normally
     */
    private String tryRegionOverlap(QueryAnalysis analysis) {
        if (!analysis.regionQuery
                || analysis.regionAnchor == null || analysis.regionAnchor.isBlank()
                || analysis.regionTargetType == null || analysis.regionTargetType.isBlank()) {
            return null;
        }

        String targetType = canonicalObjectType(analysis.regionTargetType);
        if (targetType == null) {
            LOG.info("Region overlap wanted '{}' but that type is not enabled " +
                    "(chatbot.enumeration.object-types={}) - retrieving normally",
                    analysis.regionTargetType, enumerationTypes);
            return null;
        }

        String assembly = (analysis.enumAssembly == null || analysis.enumAssembly.isBlank())
                ? enumerationAssembly : analysis.enumAssembly.trim();
        String anchorSymbol = analysis.regionAnchor.trim();

        try {
            List<RegionMemberProjection> anchors = repository.findAnchorPosition(anchorSymbol, assembly);
            if (anchors.isEmpty()) {
                LOG.info("Region anchor '{}' has no position on {} - retrieving normally",
                        anchorSymbol, assembly);
                return null;
            }
            if (anchors.size() > 1) {
                // Several records share the symbol; picking one would silently answer about the
                // wrong organism. Species disambiguation handles asking which was meant.
                LOG.info("Region anchor '{}' matches {} records - leaving it to disambiguation",
                        anchorSymbol, anchors.size());
                return null;
            }

            RegionMemberProjection anchor = anchors.get(0);
            if (anchor.getChromosome() == null || anchor.getStartPos() == null || anchor.getStopPos() == null) {
                return null;
            }

            List<RegionMemberProjection> members = repository.findOverlappingInRegion(
                    targetType, assembly, anchor.getChromosome(),
                    anchor.getStartPos(), anchor.getStopPos());
            if (members.isEmpty()) {
                LOG.info("No {} overlaps {} on {} - retrieving normally", targetType, anchorSymbol, assembly);
                return null;
            }

            // A wide QTL can span thousands of genes — Srcrt1 alone covers ~61 Mb and 1,455 of
            // them. Emitting every row would swamp the prompt, so past the cap the count is
            // reported honestly and only the first rows are listed, in position order. Saying
            // "1,455, here are the first 200" is useful; silently showing 200 as if that were
            // all of them is the failure this whole feature exists to prevent.
            boolean truncated = members.size() > enumerationMaxRows;
            List<RegionMemberProjection> shown = truncated
                    ? members.subList(0, enumerationMaxRows) : members;

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "--- COMPLETE DATABASE LIST: every %s overlapping %s (%s chr%s:%d-%d) ---%n",
                    targetType, anchor.getSymbol(), assembly, anchor.getChromosome(),
                    anchor.getStartPos(), anchor.getStopPos()));
            if (truncated) {
                sb.append(String.format(
                        "NOTE: %d %s(s) overlap this region in total. Only the first %d are listed "
                        + "below, in position order. State the total when answering and offer to "
                        + "narrow the interval or filter by function.%n",
                        members.size(), targetType, shown.size()));
            }
            sb.append("| Symbol | Name | Start | Stop |\n");
            for (RegionMemberProjection m : shown) {
                sb.append(String.format("| %s | %s | %s | %s |%n",
                        m.getSymbol(),
                        m.getName() == null ? "" : m.getName(),
                        m.getStartPos() == null ? "" : m.getStartPos().toString(),
                        m.getStopPos() == null ? "" : m.getStopPos().toString()));
            }
            sb.append(String.format("Total overlapping: %d (listed here: %d)%n"
                    + "--- END COMPLETE DATABASE LIST ---%n%n", members.size(), shown.size()));

            LOG.info("Region overlap: {} {}(s) overlap {} on {} chr{}",
                    members.size(), targetType, anchor.getSymbol(), assembly, anchor.getChromosome());
            return sb.toString();

        } catch (Exception e) {
            LOG.error("Region overlap query failed, falling back to retrieval: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Tell the model which species each named record exists for, so it can ask instead of guess.
     *
     * <p>"Where is Mapk10" has no single right answer once more than one species is loaded —
     * the rat and human genes sit in different places. Rather than silently picking whichever
     * species retrieval happened to surface, the available species are put in front of the
     * model and it asks which one the user meant.</p>
     *
     * <p>Costs one indexed query and only produces a note when a symbol is genuinely ambiguous.
     * With a single-species corpus nothing is ever ambiguous, so this stays silent and the
     * behaviour is unchanged — it wakes up on its own as other species load.</p>
     *
     * @return a note to prepend to the context, or null when nothing is ambiguous
     */
    private String speciesDisambiguation(List<String> entities) {
        if (!speciesDisambiguationEnabled || entities == null || entities.isEmpty()) {
            return null;
        }

        Set<String> lowered = new LinkedHashSet<>();
        for (String e : entities) {
            if (e != null && !e.isBlank()) {
                lowered.add(e.trim().toLowerCase());
            }
        }
        if (lowered.isEmpty()) {
            return null;
        }

        try {
            // Keyed on the stored spelling, so the note shows the symbol as RGD writes it
            // rather than however the user happened to type it.
            Map<String, Set<String>> speciesBySymbol = new LinkedHashMap<>();
            for (SymbolSpeciesProjection row : repository.findSpeciesBySymbols(lowered)) {
                speciesBySymbol
                        .computeIfAbsent(row.getSymbol(), k -> new LinkedHashSet<>())
                        .add(row.getSpecies());
            }

            boolean anyAmbiguous = speciesBySymbol.values().stream().anyMatch(s -> s.size() > 1);
            if (!anyAmbiguous) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("--- SPECIES AVAILABLE FOR THE NAMED RECORDS ---\n");
            for (Map.Entry<String, Set<String>> e : speciesBySymbol.entrySet()) {
                sb.append(String.format("%s: %s%n", e.getKey(), String.join(", ", e.getValue())));
            }
            sb.append("--- END SPECIES AVAILABLE ---\n\n");

            LOG.info("Species disambiguation: {} of {} named record(s) exist for more than one species",
                    speciesBySymbol.values().stream().filter(s -> s.size() > 1).count(),
                    speciesBySymbol.size());
            return sb.toString();

        } catch (Exception e) {
            LOG.error("Species lookup failed, answering without disambiguation: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Answer "every X on chromosome Y" from the object tables instead of from retrieval.
     *
     * <p>Similarity search cannot do this correctly at any topK: a QTL report averages ~64
     * chunks, so chromosome 14's QTLs do not fit in a 40-chunk context however it is tuned.
     * The assistant then answers confidently from whatever fraction it retrieved — it once
     * listed 12 of the 52 QTL actually on chromosome 14.</p>
     *
     * <p>Only runs for object types named in {@code chatbot.enumeration.object-types}. That
     * gate exists because a complete-sounding answer from a half-populated table is worse
     * than no answer at all: until a type is fully backfilled, "there are no genes on
     * chromosome 14" would be both confident and false. An empty result also falls back
     * rather than asserting emptiness.</p>
     *
     * @return a formatted table to prepend to the context, or null to retrieve normally
     */
    private String tryEnumeration(QueryAnalysis analysis) {
        if (!analysis.enumeration
                || analysis.enumObjectType == null || analysis.enumObjectType.isBlank()
                || analysis.enumChromosome == null || analysis.enumChromosome.isBlank()) {
            return null;
        }

        String objectType = canonicalObjectType(analysis.enumObjectType);
        if (objectType == null) {
            LOG.info("Enumeration requested for '{}' but that type is not enabled " +
                    "(chatbot.enumeration.object-types={}) - retrieving normally",
                    analysis.enumObjectType, enumerationTypes);
            return null;
        }

        String assembly = (analysis.enumAssembly == null || analysis.enumAssembly.isBlank())
                ? enumerationAssembly : analysis.enumAssembly.trim();
        String chromosome = analysis.enumChromosome.trim();

        try {
            List<ReportObjectDE> objects =
                    reportDAO.getObjectsOnChromosome(objectType, assembly, chromosome);
            if (objects.isEmpty()) {
                LOG.info("Enumeration for {} on chr{} ({}) returned nothing - falling back to retrieval",
                        objectType, chromosome, assembly);
                return null;
            }

            // Both queries order by start_pos and the (rgd_id, assembly) key means at most one
            // position per object here, so the map is a safe way to pair them up.
            Map<Long, ReportPositionDE> positions = new HashMap<>();
            for (ReportPositionDE p : reportDAO.getPositionsOnChromosome(objectType, assembly, chromosome)) {
                positions.put(p.getRgdId(), p);
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format(
                    "--- COMPLETE DATABASE LIST: every %s on chromosome %s (%s) ---%n",
                    objectType, chromosome, assembly));
            sb.append("| Symbol | Name | Start | Stop |\n");
            for (ReportObjectDE o : objects) {
                ReportPositionDE p = positions.get(o.getRgdId());
                sb.append(String.format("| %s | %s | %s | %s |%n",
                        o.getSymbol(),
                        o.getName() == null ? "" : o.getName(),
                        p == null || p.getStartPos() == null ? "" : p.getStartPos().toString(),
                        p == null || p.getStopPos() == null ? "" : p.getStopPos().toString()));
            }
            sb.append(String.format("Total: %d%n--- END COMPLETE DATABASE LIST ---%n%n", objects.size()));

            LOG.info("Enumeration: {} {}(s) on chr{} ({}) returned from the database, ordered by position",
                    objects.size(), objectType, chromosome, assembly);
            return sb.toString();

        } catch (Exception e) {
            LOG.error("Enumeration query failed, falling back to retrieval: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Fetch chunks for named records by exact symbol, skipping similarity search entirely.
     *
     * <p>Returns empty when the feature is off, no records were named, the store does not
     * support it, or nothing resolved — every one of which means the caller should fall
     * back to searching. Resolving only some of the named symbols still counts as a hit:
     * partial exact results beat a centroid embedding that matches none of them.</p>
     */
    private List<Document> exactLookup(List<String> entities) {
        if (!exactLookupEnabled || entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        if (!(openaiVectorStore instanceof PostgresVectorStoreOpenAI)) {
            return new ArrayList<>();
        }

        List<Document> hits = ((PostgresVectorStoreOpenAI) openaiVectorStore)
                .findBySymbols(entities, null, null);

        if (hits.isEmpty()) {
            LOG.info("Exact lookup resolved none of {} named record(s) - falling back to search",
                    entities.size());
        } else {
            Set<String> files = new HashSet<>();
            for (Document d : hits) {
                Object f = d.getMetadata().get("filename");
                if (f != null) {
                    files.add(f.toString());
                }
            }
            LOG.info("Exact lookup: {} named record(s) -> {} chunk(s) from {} file(s), no embedding calls",
                    entities.size(), hits.size(), files.size());
        }
        return hits;
    }

    /**
     * Single hybrid (vector + full-text) retrieval pass.
     */
    private List<Document> runSearch(String query, int topK) {
        return runSearch(query, topK, excludedSections);
    }

    private List<Document> runSearch(String query, int topK, List<String> excluded) {
        SearchRequest req = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(0.35);
        if (openaiVectorStore instanceof PostgresVectorStoreOpenAI) {
            return ((PostgresVectorStoreOpenAI) openaiVectorStore).hybridSearch(req, excluded);
        }
        return openaiVectorStore.similaritySearch(req);
    }

    /**
     * Stable per-chunk key for de-duplicating hits across fan-out searches.
     */
    private Object docKey(Document doc) {
        Object id = doc.getMetadata().get("id");
        return (id != null) ? id : doc.getContent();
    }

    /**
     * Run one retrieval per named record and merge the results, giving every record its
     * own share of the context budget.
     *
     * A single search for a question naming 20+ symbols embeds to a centroid near none of
     * them and returns unrelated material, even though each report is indexed and answers
     * perfectly when asked about on its own. Splitting the search guarantees every record
     * actually gets looked up.
     */
    private List<Document> fanoutRetrieve(List<String> entities, String fullQuery) {
        int perEntity = Math.max(fanoutMinDocsPerEntity,
                Math.min(fanoutMaxDocsPerEntity, fanoutMaxDocs / entities.size()));

        LOG.info("Fan-out retrieval: {} entities, up to {} chunks each", entities.size(), perEntity);

        LinkedHashMap<Object, Document> merged = new LinkedHashMap<>();

        // Run the per-entity searches concurrently. Done sequentially, a 22-gene question
        // would be 22 embedding calls plus 22 DB queries back to back, which would dominate
        // the response time on its own.
        List<CompletableFuture<List<Document>>> futures = new ArrayList<>();
        for (String entity : entities) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    List<Document> hits = runSearch(entity, Math.max(perEntity * 4, 20));
                    if (hits.isEmpty()) {
                        LOG.info("Fan-out: no hits for entity \"{}\"", entity);
                        return new ArrayList<Document>();
                    }
                    List<Document> ranked = rerankDocuments(hits, entity);
                    List<Document> kept = new ArrayList<>(
                            ranked.subList(0, Math.min(perEntity, ranked.size())));
                    LOG.info("Fan-out: entity \"{}\" -> {} hits, kept {}",
                            entity, hits.size(), kept.size());
                    return kept;
                } catch (Exception e) {
                    LOG.warn("Fan-out search failed for entity \"{}\": {}", entity, e.getMessage());
                    return new ArrayList<Document>();
                }
            }, fanoutExecutor));
        }

        // Drain in submission order so the merged context is deterministic.
        for (CompletableFuture<List<Document>> future : futures) {
            try {
                for (Document doc : future.join()) {
                    merged.putIfAbsent(docKey(doc), doc);
                }
            } catch (Exception e) {
                LOG.warn("Fan-out task failed: {}", e.getMessage());
            }
        }

        // Top up with results for the question as a whole, so shared or general context
        // (the QTL the genes sit in, for instance) is not lost.
        if (merged.size() < fanoutMaxDocs) {
            try {
                List<Document> general = rerankDocuments(runSearch(fullQuery, 40), fullQuery);
                for (Document doc : general) {
                    if (merged.size() >= fanoutMaxDocs) {
                        break;
                    }
                    merged.putIfAbsent(docKey(doc), doc);
                }
            } catch (Exception e) {
                LOG.warn("Fan-out top-up search failed: {}", e.getMessage());
            }
        }

        LOG.info("Fan-out retrieval merged into {} unique chunks", merged.size());
        return new ArrayList<>(merged.values());
    }

    /**
     * Result of the pre-retrieval query analysis step.
     */
    private static class QueryAnalysis {
        String searchQuery;
        List<String> entities = new ArrayList<>();
        /** True when the user is asking what lies inside a region, which is exactly what the
         *  normally-excluded region-listing sections contain. */
        boolean regionQuery;

        /** True when the user wants every record of a type on a chromosome, not a few examples. */
        boolean enumeration;
        /** Gene / Qtl / Strain, when the enumeration names one. */
        String enumObjectType;
        /** Chromosome the enumeration is scoped to, e.g. "14" or "X". */
        String enumChromosome;
        /** Assembly named in the question; blank means use the configured default. */
        String enumAssembly;

        /** The record whose region is being asked about, e.g. "A2m" in "QTLs in the A2m region". */
        String regionAnchor;
        /** What to list inside that region: Gene, Qtl or Strain. */
        String regionTargetType;

        /** True when the user is asking where named records are, rather than about them. */
        boolean positionQuery;
    }

    /**
     * Analyze the user's message before retrieval. Does two jobs in one cheap model call:
     *
     * 1. REWRITE - resolve a follow-up into a standalone query. Retrieval embeds the query
     *    text directly, so "are there any others" or "list the genes from above" carries no
     *    entities and matches nothing above the similarity threshold.
     *
     * 2. ENTITY EXTRACTION - pull out the individual genes/QTLs/strains named. A question
     *    listing 22 gene symbols embeds to a centroid that sits near none of them, so a
     *    single search returns unrelated material even though every one of those reports is
     *    indexed. The entity list lets preProcess fan out one search per entity instead.
     *
     * Falls back to the original question with no entities on any failure - a degraded
     * query beats a failed request.
     */
    private QueryAnalysis analyzeQuery(String question, String conversationId) {
        QueryAnalysis analysis = new QueryAnalysis();
        analysis.searchQuery = question;

        if (!rewriteEnabled) {
            return analysis;
        }

        StringBuilder transcript = new StringBuilder();
        try {
            List<Message> history = chatMemory.get(conversationId, rewriteHistoryMessages);
            if (history != null) {
                for (Message m : history) {
                    String role = (m.getMessageType() == MessageType.USER) ? "User" : "Assistant";
                    String content = m.getContent();
                    if (content == null || content.isBlank()) {
                        continue;
                    }
                    // Cap each turn: assistant answers run long and only the entities matter here.
                    if (content.length() > 1500) {
                        content = content.substring(0, 1500) + " ...";
                    }
                    transcript.append(role).append(": ").append(content).append("\n\n");
                }
            }
        } catch (Exception e) {
            LOG.warn("Could not read chat memory for query analysis: {}", e.getMessage());
        }

        String historyBlock = transcript.length() == 0
                ? "(none - this is the first question of the conversation)"
                : transcript.toString();

        String analysisSystem = """
        You prepare a user's message for a document retrieval system covering Rat Genome
        Database (RGD) records: genes, QTLs, strains, variants, markers, references,
        ontology terms, and annotations.

        Reply with ONLY a JSON object, no markdown fences and no commentary:
        {"query": "<standalone search query>", "entities": ["<name>", "..."],
         "regionQuery": false, "regionAnchor": "", "regionTargetType": "",
         "positionQuery": false, "enumeration": false,
         "enumObjectType": "", "enumChromosome": "", "enumAssembly": ""}

        "query" RULES:
        - Resolve all pronouns and implicit references ("they", "those", "any others",
          "it", "that gene", "the list above") using the conversation history.
        - Preserve entity names, symbols, and identifiers EXACTLY as written
          (for example: LH/Mav, C17h6orf52, Nkx6-1, BN-Chr 13^LH/MavRrrc).
        - Carry forward the subject under discussion when the latest message omits it.
        - If the latest message is a meta question about the conversation itself
          (for example "why did you not find those earlier"), rewrite it into a content
          query for the entities that were being discussed.
        - If the message is already standalone, repeat it unchanged.
        - Keep it under 40 words.

        "entities" RULES:
        - List every specific named record the user is asking about: gene symbols, QTL
          symbols, strain names, marker names, RGD IDs.
        - Include entities carried over from the conversation history when the latest
          message refers to them without naming them.
        - Copy each name EXACTLY as it should be searched. No descriptions, no duplicates.
        - Use [] when the question is general and names no specific records
          (for example "what QTL are on rat chromosome 14").
        - Never invent a name that appears neither in the message nor the history.

        "regionQuery" RULES:
        - true when the user is asking what lies WITHIN a region or interval: which genes,
          QTLs or markers are in a QTL's span, inside a chromosomal range, or overlapping
          another record.
        - false for everything else, including a question about one record's own position,
          its annotations, or its description.
        - Examples of true: "what genes are in the Niddm15 region",
          "which QTLs overlap chr14:1-11Mb", "markers inside Mcs2".
        - Examples of false: "where is Gpat3", "what is Niddm15",
          "what diseases is Mcs2 associated with".
        - When true, also set:
          "regionAnchor" to the record whose region is meant (e.g. "A2m" for "the QTLs in
          the region of A2m"), or "" if the region is given as raw coordinates;
          "regionTargetType" to what is being listed inside it — Gene, Qtl or Strain.

        "positionQuery" RULES:
        - true when the user is asking WHERE named records are — their position, coordinates,
          start/stop, locus or which chromosome they sit on.
        - Set it alongside "entities", which must hold every record they are asking about.
          Resolve "them", "these", "those" from the conversation history first, so a follow-up
          like "can you give me the positions of them" carries ALL the records named earlier,
          not just the ones most recently mentioned.
        - Examples of true: "where is Gpat3", "positions of them",
          "what are the coordinates of Setdb1 and Slc22a15".
        - false when the question is about what a record does, not where it is.

        "enumeration" RULES:
        - true ONLY when the user wants EVERY record of one type on a whole chromosome —
          "what QTL are on rat chromosome 14", "list all genes on chr 7", "how many
          strains are on chromosome 2".
        - false when the question is about one named record, a sub-interval of a
          chromosome, or anything that is not an exhaustive list for a whole chromosome.
        - When true, also set:
          "enumObjectType" to exactly one of Gene, Qtl or Strain;
          "enumChromosome" to the chromosome alone, with no "chr" prefix (e.g. "14", "X");
          "enumAssembly" to the assembly if the user named one (e.g. "GRCr8"), else "".
        - If you cannot fill in both the object type and the chromosome, set
          "enumeration" to false.

        CONVERSATION HISTORY:
        ---------------------
        %s---------------------
        """.formatted(historyBlock);

        try {
            String raw = rewriteClient.prompt()
                    .system(analysisSystem)
                    .user(question)
                    .options(OpenAiChatOptions.builder()
                            .withModel(rewriteModel)
                            .withTemperature(0.0)
                            .build())
                    .call()
                    .content();

            if (raw == null || raw.isBlank()) {
                LOG.warn("Query analysis returned empty, using original question");
                return analysis;
            }

            // Tolerate a model that wraps the object in markdown fences or prose.
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                LOG.warn("Query analysis returned no JSON object, using original question");
                return analysis;
            }

            JsonNode node = JSON.readTree(raw.substring(start, end + 1));

            String rewritten = node.path("query").asText("").trim();
            if (!rewritten.isBlank()) {
                analysis.searchQuery = rewritten;
            }

            JsonNode entities = node.path("entities");
            if (entities.isArray()) {
                Set<String> seen = new HashSet<>();
                for (JsonNode entity : entities) {
                    String name = entity.asText("").trim();
                    if (name.isBlank() || name.length() > 100) {
                        continue;
                    }
                    if (seen.add(name.toLowerCase())) {
                        analysis.entities.add(name);
                    }
                    if (analysis.entities.size() >= fanoutMaxEntities) {
                        LOG.info("Entity list truncated at {} entries", fanoutMaxEntities);
                        break;
                    }
                }
            }

            analysis.regionQuery = node.path("regionQuery").asBoolean(false);
            analysis.enumeration = node.path("enumeration").asBoolean(false);
            analysis.enumObjectType = node.path("enumObjectType").asText("").trim();
            analysis.enumChromosome = node.path("enumChromosome").asText("").trim();
            analysis.enumAssembly = node.path("enumAssembly").asText("").trim();
            analysis.regionAnchor = node.path("regionAnchor").asText("").trim();
            analysis.regionTargetType = node.path("regionTargetType").asText("").trim();
            analysis.positionQuery = node.path("positionQuery").asBoolean(false);

            LOG.info("Query analysis: \"{}\" -> \"{}\" | entities={} | regionQuery={} | enumeration={} {} chr{}",
                    question, analysis.searchQuery, analysis.entities, analysis.regionQuery,
                    analysis.enumeration, analysis.enumObjectType, analysis.enumChromosome);
            return analysis;

        } catch (Exception e) {
            LOG.error("Query analysis failed, falling back to original question: {}", e.getMessage(), e);
            analysis.searchQuery = question;
            analysis.entities.clear();
            return analysis;
        }
    }

    /**
     * Holds the result of pre-processing: system message, filenames, and timing milestones.
     */
    private static class PreProcessResult {
        String systemMessage;
        Set<String> usedFilenames;
        String searchQuery;
        boolean isEmpty;
        long t0, tRewrite, t1, t2, t3;
    }

    /**
     * Shared pre-processing: vector search, re-ranking, context building.
     * Used by both chat() and chatStream().
     */
    private PreProcessResult preProcess(Question question, HttpServletRequest request, String conversationId) {
        PreProcessResult result = new PreProcessResult();
        result.t0 = System.currentTimeMillis();

        // STAGE 0: Resolve follow-ups into a standalone query and pull out the specific
        // records being asked about.
        QueryAnalysis analysis = analyzeQuery(question.getQuestion(), conversationId);
        String searchQuery = analysis.searchQuery;
        result.searchQuery = searchQuery;
        result.tRewrite = System.currentTimeMillis();

        // An exhaustive "every X on chromosome Y" is a database question, not a search one.
        // Retrieval still runs underneath it: the list answers "which", the chunks answer
        // any follow-on detail in the same question.
        // Whole-chromosome list, or everything overlapping one record's span. Both are
        // database questions that retrieval can only ever answer partially.
        String enumerationTable = tryEnumeration(analysis);
        if (enumerationTable == null) {
            enumerationTable = tryRegionOverlap(analysis);
        }
        if (enumerationTable == null) {
            enumerationTable = tryNamedPositions(analysis);
        }

        // Silent while one species is loaded; produces a note only when a named record
        // genuinely exists for several.
        String speciesNote = speciesDisambiguation(analysis.entities);

        // STAGES 1+2: Retrieve and re-rank.
        //
        // Named records are fetched by symbol, not by similarity: the user told us which
        // records they want, so that is an indexed lookup rather than a search. Fan-out
        // stays as the fallback for names the lookup cannot resolve - an alias, a typo, or
        // an object whose metadata has not been backfilled yet.
        List<Document> documents;
        List<Document> exact = exactLookup(analysis.entities);

        if (!exact.isEmpty()) {
            documents = exact;
            if (documents.size() > fanoutMaxDocs) {
                documents = documents.subList(0, fanoutMaxDocs);
            }
            result.t1 = System.currentTimeMillis();
            result.t2 = result.t1;
        } else if (fanoutEnabled && analysis.entities.size() >= 2) {
            documents = fanoutRetrieve(analysis.entities, searchQuery);
            result.t1 = System.currentTimeMillis();
            result.t2 = result.t1;
        } else {
            // A region question is precisely the case the exclusions would break, so they
            // are lifted for it rather than applied blindly.
            List<String> excluded = analysis.regionQuery ? List.of() : excludedSections;
            if (analysis.regionQuery && !excludedSections.isEmpty()) {
                LOG.info("Region question - keeping region-listing sections in the candidate set");
            }

            List<Document> candidates = runSearch(searchQuery, 80, excluded);
            result.t1 = System.currentTimeMillis();
            LOG.info("Stage 1: Retrieved {} candidates from hybrid search (vector + file-name match)", candidates.size());

            documents = rerankDocuments(candidates, searchQuery);
            if (documents.size() > maxContextDocs) {
                documents = documents.subList(0, maxContextDocs);
            }
            result.t2 = System.currentTimeMillis();
        }
        LOG.info("Stage 2: Re-ranked and selected top {} documents", documents.size());
        LOG.info("OpenAI - Total documents for context: {}", documents.size());

        // No documents is NOT a dead end: the model still has the conversation history
        // and may be able to answer from earlier turns or ask a clarifying question.
        result.isEmpty = documents.isEmpty();
        if (result.isEmpty) {
            LOG.info("No documents retrieved for query \"{}\" - falling back to history-only answer", searchQuery);
        }

        // Build context and collect filenames
        StringBuilder contextBuilder = new StringBuilder();
        result.usedFilenames = new HashSet<>();

        // First in the context, so the complete list is read before the sampled chunks that
        // follow it and cannot be mistaken for just another retrieved excerpt.
        // Ahead of everything else: whether the question is even answerable as asked comes
        // before any material that might answer it.
        if (speciesNote != null) {
            contextBuilder.append(speciesNote);
        }

        if (enumerationTable != null) {
            contextBuilder.append(enumerationTable);
            result.isEmpty = false;
        }

        if (documents.isEmpty() && enumerationTable == null) {
            contextBuilder.append("(No documents were retrieved from the knowledge base for this question.)\n\n");
        }
        for (Document doc : documents) {
            String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
            if (!filename.equals("unknown")) {
                result.usedFilenames.add(filename);
            }
            contextBuilder.append(String.format("--- FROM: %s ---\n%s\n\n", filename, doc.getContent()));
        }
        /*
        * add to How to answer: that if user asks about an object and does not provide a species,
        * report back to the user which species they want to know about. Provide a list of species that have that object.
        * If they ask for all, then look at the context documents of the object for all species.
        * If there is only one available in the context documents, then give an answer about that one.
        *
         */
        result.systemMessage = String.format("""
        You are RatChat, a friendly, helpful AI assistant made available by the
        Rat Genome Database (RGD) at the Medical College of Wisconsin (MCW).

        RGD is a comprehensive genomic and genetic database that provides
        curated data on genes, QTLs, strains, variants, markers, references,
        cell lines, gene families, protein domains, projects, and more across
        multiple species.

        You help users by answering questions about:
        - Genes, QTLs, strains, variants, markers, and other RGD data
        - Disease associations and ontology annotations
        - Gene-chemical interactions, pathways, and phenotypes
        - Ortholog information across species
        - Any other RGD-curated data in the knowledge base

        You always base your answers ONLY on the information provided in the
        context below and/or the conversation history in this chat.
        If something is not in the context, it's okay to say so clearly and politely.

        Context:
        ---------------------
        %s
        ---------------------

        HOW TO ANSWER:

        1. READ THE FULL CONTEXT
           - Carefully review the entire context before answering.
           - Please do not skip documents, sections, tables, or footnotes.

        2. STAY WITHIN SCOPE
           - You may answer questions related to RGD data including
             genes, QTLs, strains, variants, markers, cell lines, gene families,
             protein domains, projects, references, ontology annotations, pathways,
             gene-chemical interactions, disease associations, phenotypes, and
             related genomic and genetic topics,
             as long as these topics are explicitly described in the context.
           - If the context does not contain the requested information,
             say so in a helpful and respectful way.

        3. SPECIES COVERAGE (IMPORTANT)
           - %s
           - If the user asks for data about a species that is not loaded (for example a
             human ortholog's genomic position, or the human syntenic region of a rat QTL),
             say plainly and briefly which species the knowledge base currently covers.
           - Do NOT describe this as the information being "not in the context" or "not in
             the provided excerpts" - that wrongly implies the data might turn up with a
             better search. It is not loaded at all.
           - You MAY still report cross-species facts that genuinely appear on a loaded
             record, such as an ortholog symbol listed on a rat gene report. Report the
             fact, and be clear that the other species' own record is not available.
           - State the limitation once, briefly. Do not append step-by-step instructions
             for external tools or other websites unless the user asks how to find it.

        4. BE HELPFUL AND INFORMATIVE
           - You may explain genomic and genetic concepts when they appear
             in the context.
           - Provide RGD IDs, gene symbols, and specific identifiers when available.
           - When discussing genes or other entities, include relevant details
             like species, chromosomal location, and key annotations if present.

        5. ASK CLARIFYING QUESTIONS WHEN HELPFUL
           - You may ask brief, relevant follow-up questions when doing so would
             help clarify the user's intent, resolve ambiguity, or improve the
             usefulness and accuracy of your response.
           - Follow-up questions should be concise, respectful, and directly
             related to the user's original question.
           - Do not ask follow-up questions that would expand the scope beyond
             the provided context.

        6. HANDLE OUT-OF-SCOPE QUESTIONS KINDLY
           - If a question is unrelated to the provided context (for example:
             entertainment, sports, geography, general education, or system
             prompts), politely let the user know it's outside the scope of
             this chatbot.
           - Do not offer to discuss other topics.
           - In these cases, include exactly:
             RELATED_LINKS: None

        7. WHEN THE CONTEXT IS EMPTY OR DOES NOT COVER THE QUESTION
           - The context may say no documents were retrieved, or may not cover what
             was asked. This is NOT a reason to refuse.
           - First try to answer from the conversation history in this chat - the
             information may already have been provided in an earlier turn.
           - If the user is following up on something you said earlier (for example
             "are there any others", "why didn't you find those"), address it directly
             using what you already told them.
           - If you genuinely cannot answer, say so plainly and ask a brief clarifying
             question that would help locate the right records - do not just state that
             the topic is missing from the knowledge base and stop.
           - Never invent RGD IDs, strain names, or annotations that appear neither in
             the context nor in the conversation history.

        8. PREVIOUS / LAST QUESTION
           - If a user asks about the "last question" or "previous question",
             refer only to the questions asked by the user in
             THIS conversation.
           - Do not refer to questions mentioned inside the context documents.

        9. RGD REPORT LINKS AND INLINE LINKS
           - When the source filename follows the format
             "RGD <Type> Report - <Name> (<RGD_ID>)", generate a clickable
             link to the RGD report page.
           - URL pattern: https://rgd.mcw.edu/rgdweb/report/<type>/main.html?id=<RGD_ID>
             where <type> is lowercase (gene, qtl, strain, variant, marker, reference).
           - Example: Source "RGD Gene Report - A2m (2004)" produces link:
             [A2m on RGD](https://rgd.mcw.edu/rgdweb/report/gene/main.html?id=2004)
           - ONTOLOGY REPORT PAGES: Ontology terms use a different URL pattern
             based on the ontology accession ID (e.g. an ID like "DOID:0001816"
             or "GO:0008150"), not the numeric RGD_ID.
           - URL pattern: https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=<ONT_ACC>
             where <ONT_ACC> is the ontology accession ID.
           - Example: An ontology term with accession "DOID:0001816" produces link:
             [angiosarcoma](https://rgd.mcw.edu/rgdweb/ontology/annot.html?acc_id=DOID:0001816)
           - IMPORTANT: The context may contain markdown hyperlinks like
             [Gene Symbol](https://rgd.mcw.edu/...) for genes, QTLs, strains,
             markers, and other entities. When you mention these entities in
             your answer, PRESERVE and INCLUDE the markdown links exactly as
             they appear in the context so users can navigate to the relevant
             RGD report pages.

        10. BE COMPLETE AND CLEAR
           - You may summarize information, but do not leave out important
             details or relevant sources just to be brief.
           - If information is missing, unclear, or not stated in the context,
             explain that plainly.

        11. AVOID ASSUMPTIONS
           - Do not infer outcomes, effectiveness, safety conclusions, or
             regulatory meaning beyond what is explicitly stated.

        12. DOCUMENT REFERENCES
           When mentioning a document name in your response, wrap it in
           double brackets using the EXACT filename from the
           "--- FROM: filename ---" headers as-is (do NOT add or remove
           any extension).
           Example: [[RGD Gene Report - A2m (2004)]]

        13. COMPLETE DATABASE LISTS
           - A block marked "COMPLETE DATABASE LIST" is not a retrieved excerpt. It is the
             full result of a direct database query, already ordered by chromosomal position.
           - Treat it as authoritative and exhaustive for what it covers. Report every row,
             or state the total and summarise — but never imply the list may be partial, and
             never say it reflects only "the provided context".
           - The context documents below it are supporting detail, not a second opinion. If
             a document mentions a record the list does not contain, the list wins for
             "what is on this chromosome".
           - Preserve the given order; it is genomic order, not relevance order.
           - If the question also asks about traits, diseases or other detail, answer that
             from the context documents while still reporting the list in full.

        14. WHICH SPECIES DID THEY MEAN
           - A block marked "SPECIES AVAILABLE FOR THE NAMED RECORDS" lists, per symbol,
             every species that record exists for in the knowledge base.
           - If a record is listed for MORE THAN ONE species and the user did not say which
             they meant, do not guess and do not silently answer for one of them. Ask which
             species they want, naming the ones available for that record.
           - If a record is listed for exactly ONE species, just answer for it. Do not ask a
             question the data does not raise.
           - If the user asks for all species, answer for each one the block lists, keeping
             them clearly separated.
           - If the user already named a species, use it and do not ask again.
           - A symbol absent from the block is not ambiguous; answer normally.

        RELATED LINKS (REQUIRED):

        At the end of every response, include:

        RELATED_LINKS: <comma-separated list>

        Guidelines:
        - List ONLY the files you drew a specific stated fact from.
        - A file being present in the context is NOT a reason to list it. Listing
          everything in the context, or a long list of files you did not quote, is wrong.
        - If your answer says the information is unavailable, not loaded, or could not be
          found, then you used no files - write exactly:
          RELATED_LINKS: None
        - Never list a file whose contents you did not actually use.
        - Use exact filenames from the "--- FROM: filename ---" markers.
        - Separate multiple filenames with commas and no spaces.
        - If no files were used, write exactly:
          RELATED_LINKS: None
        """, contextBuilder, corpusCoverageNote);
        result.t3 = System.currentTimeMillis();

        return result;
    }

    private boolean isGreeting(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase().matches(".*\\b(hi|hello|hey|greetings)\\b.*");
    }

    private String getOrCreateConversationId(Authentication user, HttpServletRequest request) {
        String conversationId = (String) request.getSession().getAttribute("openai_conversation_id");
        if (conversationId == null) {
            conversationId = (user != null) ? user.getName() : request.getSession().getId();
            request.getSession().setAttribute("openai_conversation_id", conversationId);
        }
        return conversationId;
    }

    /**
     * Extract meaningful query terms (removing stop words)
     */
    private Set<String> extractQueryTerms(String query) {
        Set<String> terms = new HashSet<>();

        // Common stop words to exclude
        Set<String> stopWords = Set.of("the", "a", "an", "is", "are", "was", "were",
                                       "in", "on", "at", "to", "for", "of", "with",
                                       "what", "how", "when", "where", "which", "that",
                                       "this", "these", "those", "be", "been", "being",
                                       "have", "has", "had", "do", "does", "did");

        // First, preserve original whitespace-split tokens (keeps special chars like / - )
        // This ensures "BN/NHsdMcwi", "SS/JrHsdMcwi", "Tp53" stay intact as search terms
        String[] rawTokens = query.trim().split("\\s+");
        for (String token : rawTokens) {
            String lower = token.toLowerCase();
            // Strip trailing punctuation (commas, periods, question marks)
            lower = lower.replaceAll("[,\\.\\?!;:]+$", "");
            if (lower.length() > 2 && !stopWords.contains(lower)) {
                terms.add(lower);
            }
        }

        // Also add cleaned/split sub-words for partial matching
        String[] words = query.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ")
                .split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && !stopWords.contains(word)) {
                terms.add(word);
            }
        }

        LOG.debug("Extracted query terms: {}", terms);
        return terms;
    }

    /**
     * Re-rank documents by combining semantic score + keyword matching
     */
    private List<Document> rerankDocuments(List<Document> candidates, String query) {
        Set<String> queryTerms = extractQueryTerms(query);

        if (queryTerms.isEmpty()) {
            LOG.warn("No query terms extracted, returning original order");
            return candidates;
        }

        // Score and sort documents
        List<ScoredDocument> scoredDocs = candidates.stream()
                .map(doc -> {
                    // Get semantic similarity score (already in metadata as distance)
                    Double distance = (Double) doc.getMetadata().getOrDefault("distance", 1.0);
                    double semanticScore = 1.0 - distance; // Convert distance to similarity

                    // Calculate keyword match score
                    String content = doc.getContent().toLowerCase();
                    long matchCount = queryTerms.stream()
                            .filter(term -> content.contains(term))
                            .count();
                    double keywordScore = (double) matchCount / queryTerms.size();

                    // Combined score: 70% semantic + 30% keyword
                    double finalScore = (0.7 * semanticScore) + (0.3 * keywordScore);

                    return new ScoredDocument(doc, finalScore, semanticScore, keywordScore, matchCount);
                })
                .sorted((a, b) -> Double.compare(b.finalScore, a.finalScore)) // Descending order
                .collect(java.util.stream.Collectors.toList());

        // Log top 10 for debugging
        LOG.info("Re-ranking results (top 10):");
        for (int i = 0; i < Math.min(10, scoredDocs.size()); i++) {
            ScoredDocument sd = scoredDocs.get(i);
            LOG.info("  {}. {} - Final: {}, Semantic: {}, Keyword: {}/{} = {}",
                    i + 1,
                    sd.doc.getMetadata().get("filename"),
                    String.format("%.4f", sd.finalScore),
                    String.format("%.4f", sd.semanticScore),
                    sd.matchCount,
                    queryTerms.size(),
                    String.format("%.2f", sd.keywordScore));
        }

        return scoredDocs.stream()
                .map(sd -> sd.doc)
                .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Helper class to hold scored documents during re-ranking
     */
    private static class ScoredDocument {
        final Document doc;
        final double finalScore;
        final double semanticScore;
        final double keywordScore;
        final long matchCount;

        ScoredDocument(Document doc, double finalScore, double semanticScore, double keywordScore, long matchCount) {
            this.doc = doc;
            this.finalScore = finalScore;
            this.semanticScore = semanticScore;
            this.keywordScore = keywordScore;
            this.matchCount = matchCount;
        }
    }

    /**
     * Wrap filenames in response with [[...]] markers for frontend linking.
     * Uses comprehensive normalization to handle all AI filename variations:
     * - Spaces removed, replaced with hyphens, or replaced with underscores
     * - Case differences
     * - Mixed patterns
     */
    private String wrapFilenamesInResponse(String response, Set<String> usedFilenames) {
        if (response == null || usedFilenames == null || usedFilenames.isEmpty()) {
            return response;
        }

        String result = response;

        // Sort filenames by length (longest first) to avoid substring issues
        List<String> sortedFilenames = usedFilenames.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .collect(java.util.stream.Collectors.toList());

        for (String filename : sortedFilenames) {
            String baseName = filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;

            // RGD display names like "RGD Gene Report - A2m (2004)" should NOT get .md appended
            boolean isRgdReport = filename.matches("RGD\\s+\\w+\\s+Report\\s+-\\s+.+\\s+\\(\\d+\\)");
            String fullName;
            if (isRgdReport) {
                fullName = filename;
            } else {
                fullName = filename.endsWith(".md") ? filename : filename + ".md";
            }

            String marker = "[[" + fullName + "]]";

            // Step 0: If RGD report, clean up any AI-appended .md suffix first
            // e.g., "RGD Gene Report - A2m (2004).md" → "RGD Gene Report - A2m (2004)"
            if (isRgdReport) {
                result = result.replace(fullName + ".md", fullName);
            }

            // Step 1: Wrap fullName where not already inside [[...]]
            // Uses regex to avoid double-wrapping when AI already added [[markers]] in body
            String fullNamePattern = "(?<!\\[\\[)" + java.util.regex.Pattern.quote(fullName) + "(?!\\]\\])";
            result = result.replaceAll(fullNamePattern, java.util.regex.Matcher.quoteReplacement(marker));

            // Step 2: Wrap baseName (without .md) - e.g., in body text
            // Negative lookbehind prevents double-wrapping inside [[...]]
            // Negative lookahead prevents matching baseName followed by .md]] or ]]
            if (!baseName.isEmpty() && !baseName.equals(fullName)) {
                String pattern = "(?i)(?<!\\[\\[)" + java.util.regex.Pattern.quote(baseName) + "(?!\\.md\\]\\]|\\]\\])";
                result = result.replaceAll(pattern, java.util.regex.Matcher.quoteReplacement(marker));
            }

            // Skip variations if already wrapped
            if (result.contains(marker)) {
                continue;
            }

            // Generate all possible variations the AI might use
            List<String> variations = generateFilenameVariations(baseName);

            boolean matched = false;
            for (String variation : variations) {
                if (matched) break;

                String variationFull = variation + ".md";

                // Try with .md
                if (result.contains(variationFull) && !result.contains("[[" + variationFull)) {
                    result = result.replace(variationFull, marker);
                    matched = true;
                    break;
                }
                // Try without .md
                if (result.contains(variation) && !result.contains("[[" + variation)) {
                    result = result.replace(variation, marker);
                    matched = true;
                    break;
                }
            }
        }

        LOG.debug("Post-processed response with {} filenames for linking", sortedFilenames.size());
        return result;
    }

    /**
     * Generate all possible variations of a filename that AI might produce.
     * Handles: spaces removed, spaces→hyphens, spaces→underscores, lowercase, and combinations.
     */
    private List<String> generateFilenameVariations(String baseName) {
        List<String> variations = new ArrayList<>();

        // AI often uses en-dash (–) or em-dash (—) instead of hyphen (-)
        String enDashVersion = baseName.replace("-", "\u2013");
        if (!enDashVersion.equals(baseName)) {
            variations.add(enDashVersion);
        }
        String hyphenVersion = baseName.replace("\u2013", "-").replace("\u2014", "-");
        if (!hyphenVersion.equals(baseName)) {
            variations.add(hyphenVersion);
        }

        // Compacted (spaces removed)
        variations.add(baseName.replaceAll("\\s+", ""));

        // Hyphenated (spaces to hyphens)
        variations.add(baseName.replaceAll("\\s+", "-"));

        // Underscored (spaces to underscores)
        variations.add(baseName.replaceAll("\\s+", "_"));

        // Lowercase versions of all above
        variations.add(baseName.toLowerCase().replaceAll("\\s+", ""));
        variations.add(baseName.toLowerCase().replaceAll("\\s+", "-"));
        variations.add(baseName.toLowerCase().replaceAll("\\s+", "_"));
        variations.add(baseName.toLowerCase());

        // Normalize all separators (spaces, underscores, hyphens, en/em-dashes) to space
        String normalizedBase = baseName.replaceAll("[\\s_\\-\\u2013\\u2014]+", " ").trim();
        if (!normalizedBase.equals(baseName)) {
            variations.add(normalizedBase);
            variations.add(normalizedBase.replaceAll("\\s+", ""));
            variations.add(normalizedBase.replaceAll("\\s+", "-"));
            variations.add(normalizedBase.replaceAll("\\s+", "_"));
            variations.add(normalizedBase.toLowerCase());
            variations.add(normalizedBase.toLowerCase().replaceAll("\\s+", "_"));
        }

        return variations;
    }

    /**
     * reCAPTCHA v3 verification endpoint
     * Called by verify.jsp to verify the token with Google
     */
    @PostMapping("/verify-recaptcha")
    public ResponseEntity<Map<String, Object>> verifyRecaptcha(
            @RequestBody Map<String, String> request,
            HttpSession session) {

        LOG.info("Received reCAPTCHA verification request");

        String token = request.get("token");

        if (token == null || token.trim().isEmpty()) {
            LOG.error("No token provided in verification request");
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("message", "No verification token provided");
            return ResponseEntity.badRequest().body(result);
        }

        // Call RecaptchaService to verify with Google
        RecaptchaService.RecaptchaResponse response = recaptchaService.verifyToken(token);

        Map<String, Object> result = new HashMap<>();

        if (response.isSuccess()) {
            // SET SESSION ATTRIBUTE - marks user as verified
            session.setAttribute("recaptcha_verified", true);
            LOG.info("reCAPTCHA verification successful - Session marked as verified");

            result.put("success", true);
            result.put("message", "Verification successful");
            result.put("score", response.getScore());
            return ResponseEntity.ok(result);
        } else {
            LOG.warn("reCAPTCHA verification failed: {}", response.getMessage());
            result.put("success", false);
            result.put("message", response.getMessage());
            result.put("score", response.getScore());
            return ResponseEntity.ok(result);
        }
    }
}
