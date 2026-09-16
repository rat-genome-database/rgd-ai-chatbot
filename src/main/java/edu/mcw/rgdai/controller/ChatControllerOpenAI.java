package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import edu.mcw.rgdai.model.DocumentEmbeddingOpenAI;
import edu.mcw.rgdai.repository.DocumentEmbeddingOpenAIRepository;
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
            @Value("${chatbot.corpus.coverage-note}") String corpusCoverageNote,
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
     * Single hybrid (vector + full-text) retrieval pass.
     */
    private List<Document> runSearch(String query, int topK) {
        SearchRequest req = SearchRequest.query(query)
                .withTopK(topK)
                .withSimilarityThreshold(0.35);
        if (openaiVectorStore instanceof PostgresVectorStoreOpenAI) {
            return ((PostgresVectorStoreOpenAI) openaiVectorStore).hybridSearch(req);
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
        {"query": "<standalone search query>", "entities": ["<name>", "..."]}

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

            LOG.info("Query analysis: \"{}\" -> \"{}\" | entities={}",
                    question, analysis.searchQuery, analysis.entities);
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

        // STAGES 1+2: Retrieve and re-rank. A question naming several records gets one
        // search per record - a single embedding of 20+ symbols lands near none of them.
        List<Document> documents;
        if (fanoutEnabled && analysis.entities.size() >= 2) {
            documents = fanoutRetrieve(analysis.entities, searchQuery);
            result.t1 = System.currentTimeMillis();
            result.t2 = result.t1;
        } else {
            List<Document> candidates = runSearch(searchQuery, 80);
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
        if (documents.isEmpty()) {
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
        * If they ask for all, then look at the context documents of the gene for all species.
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
