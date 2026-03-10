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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.ai.openai.OpenAiChatOptions;

@RestController
@RequestMapping("/chat-openai")
public class ChatControllerOpenAI {

    private static final Logger LOG = LoggerFactory.getLogger(ChatControllerOpenAI.class);
    private static final Logger TIMING_LOG = LoggerFactory.getLogger("TIMING");

    private ChatClient chatClient;
    private final VectorStore openaiVectorStore;
    private final String configuredModel;
    private InMemoryChatMemory chatMemory;
    private final ChatModel openAiChatModel;
    private final DocumentEmbeddingOpenAIRepository repository;
    private final RecaptchaService recaptchaService;
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public ChatControllerOpenAI(
            ApplicationContext context,
            @Qualifier("openaiVectorStore") VectorStore openaiVectorStore,
            @Value("${spring.ai.openai.model}") String configuredModel,
            DocumentEmbeddingOpenAIRepository repository,
            RecaptchaService recaptchaService) {

        LOG.info("Initializing OpenAI ChatController with system messages for doc context");
        this.openaiVectorStore = openaiVectorStore;
        this.configuredModel = configuredModel;
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
        LOG.info("OpenAI ChatClient initialized successfully with model: {}", configuredModel);
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
            PreProcessResult pp = preProcess(question, request);

            if (pp.isEmpty) {
                return new Answer("I don't have information about that topic in my knowledge base.");
            }

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

            // Extract NCTIDs from AI response and store in session for future questions
            List<String> responseNCTIDs = extractNCTIDs(response);
            if (!responseNCTIDs.isEmpty()) {
                Set<String> conversationNCTIDs = (Set<String>) request.getSession().getAttribute("conversationNCTIDs");
                if (conversationNCTIDs == null) {
                    conversationNCTIDs = new HashSet<>();
                }
                int beforeSize = conversationNCTIDs.size();
                conversationNCTIDs.addAll(responseNCTIDs);
                int afterSize = conversationNCTIDs.size();
                int newNCTIDs = afterSize - beforeSize;
                request.getSession().setAttribute("conversationNCTIDs", conversationNCTIDs);
                LOG.info("Found {} NCTID mentions in response, {} unique NCTIDs total in conversation ({} new)",
                         responseNCTIDs.size(), conversationNCTIDs.size(), newNCTIDs);
                LOG.debug("Session NCTIDs: {}", conversationNCTIDs);
            }

            long t7 = System.currentTimeMillis();

            // Log timing summary
            long total = t7 - pp.t0;
            long nctidExtract = pp.t1 - pp.t0;
            long vectorSearch = pp.t2 - pp.t1;
            long rerank = pp.t3 - pp.t2;
            long nctidLookup = pp.t4 - pp.t3;
            long contextBuild = pp.t5 - pp.t4;
            long openaiApi = t6 - pp.t5;
            long postProcess = t7 - t6;

            TIMING_LOG.info("TIMING: [Q: \"{}\"]", question.getQuestion());
            TIMING_LOG.info("  NCTID Extraction:    {}ms ({}s)", nctidExtract, String.format("%.2f", nctidExtract / 1000.0));
            TIMING_LOG.info("  Vector Search:       {}ms ({}s)", vectorSearch, String.format("%.2f", vectorSearch / 1000.0));
            TIMING_LOG.info("  Re-ranking:          {}ms ({}s)", rerank, String.format("%.2f", rerank / 1000.0));
            TIMING_LOG.info("  NCTID DB Lookup:     {}ms ({}s)", nctidLookup, String.format("%.2f", nctidLookup / 1000.0));
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
            pp = preProcess(question, request);
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

        if (pp.isEmpty) {
            streamExecutor.execute(() -> {
                try {
                    String msg = "I don't have information about that topic in my knowledge base.";
                    emitter.send(SseEmitter.event().name("done")
                            .data("{\"fullResponse\":\"" + escapeJson(msg) + "\"}"));
                    emitter.complete();
                } catch (IOException e) {
                    emitter.completeWithError(e);
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

                                // NCTID extraction and session storage
                                List<String> responseNCTIDs = extractNCTIDs(processed);
                                if (!responseNCTIDs.isEmpty()) {
                                    Set<String> conversationNCTIDs = (Set<String>)
                                            session.getAttribute("conversationNCTIDs");
                                    if (conversationNCTIDs == null) {
                                        conversationNCTIDs = new HashSet<>();
                                    }
                                    conversationNCTIDs.addAll(responseNCTIDs);
                                    session.setAttribute("conversationNCTIDs", conversationNCTIDs);
                                    LOG.info("STREAM - Stored {} NCTIDs in session", conversationNCTIDs.size());
                                }

                                // Send final done event with processed response
                                emitter.send(SseEmitter.event().name("done")
                                        .data("{\"fullResponse\":\"" + escapeJson(processed) + "\"}"));
                                emitter.complete();

                                // Timing
                                long t7 = System.currentTimeMillis();
                                long total = t7 - ppFinal.t0;
                                long openaiApi = t6 - ppFinal.t5;
                                long postProcess = t7 - t6;
                                long nctidExtract = ppFinal.t1 - ppFinal.t0;
                                long vectorSearch = ppFinal.t2 - ppFinal.t1;
                                long rerank = ppFinal.t3 - ppFinal.t2;
                                long nctidLookup = ppFinal.t4 - ppFinal.t3;
                                long contextBuild = ppFinal.t5 - ppFinal.t4;

                                TIMING_LOG.info("STREAM TIMING: [Q: \"{}\"]", question.getQuestion());
                                TIMING_LOG.info("  NCTID Extraction:    {}ms ({}s)", nctidExtract, String.format("%.2f", nctidExtract / 1000.0));
                                TIMING_LOG.info("  Vector Search:       {}ms ({}s)", vectorSearch, String.format("%.2f", vectorSearch / 1000.0));
                                TIMING_LOG.info("  Re-ranking:          {}ms ({}s)", rerank, String.format("%.2f", rerank / 1000.0));
                                TIMING_LOG.info("  NCTID DB Lookup:     {}ms ({}s)", nctidLookup, String.format("%.2f", nctidLookup / 1000.0));
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
            request.getSession().removeAttribute("conversationNCTIDs");
            LOG.info("OpenAI - Cleared session NCTIDs");

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
     * Holds the result of pre-processing: system message, filenames, and timing milestones.
     */
    private static class PreProcessResult {
        String systemMessage;
        Set<String> usedFilenames;
        boolean isEmpty;
        long t0, t1, t2, t3, t4, t5;
    }

    /**
     * Shared pre-processing: NCTID extraction, vector search, re-ranking, context building.
     * Used by both chat() and chatStream().
     */
    private PreProcessResult preProcess(Question question, HttpServletRequest request) {
        PreProcessResult result = new PreProcessResult();
        result.t0 = System.currentTimeMillis();

        // Extract NCTIDs from current question
        List<String> currentNCTIDs = extractNCTIDs(question.getQuestion());

        // Get NCTIDs from previous conversation (stored in session)
        Set<String> allNCTIDs = new HashSet<>(currentNCTIDs);
        Set<String> sessionNCTIDs = (Set<String>) request.getSession().getAttribute("conversationNCTIDs");
        if (sessionNCTIDs != null && !sessionNCTIDs.isEmpty()) {
            allNCTIDs.addAll(sessionNCTIDs);
            LOG.info("Retrieved {} NCTIDs from session: {}", sessionNCTIDs.size(), sessionNCTIDs);
        }

        if (!allNCTIDs.isEmpty()) {
            LOG.info("Found {} unique NCTIDs in conversation (current + session): {}", allNCTIDs.size(), allNCTIDs);
        }
        result.t1 = System.currentTimeMillis();

        // STAGE 1: Broad retrieval - Get top 80 candidates from semantic search WITH SCORES
        List<Document> candidates;
        if (openaiVectorStore instanceof PostgresVectorStoreOpenAI) {
            PostgresVectorStoreOpenAI vectorStoreWithScores = (PostgresVectorStoreOpenAI) openaiVectorStore;
            candidates = vectorStoreWithScores.similaritySearchWithScores(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(80)
                            .withSimilarityThreshold(0.35));
        } else {
            candidates = openaiVectorStore.similaritySearch(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(80)
                            .withSimilarityThreshold(0.35));
        }
        result.t2 = System.currentTimeMillis();
        LOG.info("Stage 1: Retrieved {} candidates from semantic similarity search", candidates.size());

        // STAGE 2: Re-rank using semantic + keyword scoring
        List<Document> documents = rerankDocuments(candidates, question.getQuestion());

        // Take top 40 after re-ranking
        if (documents.size() > 40) {
            documents = documents.subList(0, 40);
        }
        result.t3 = System.currentTimeMillis();
        LOG.info("Stage 2: Re-ranked and selected top {} documents", documents.size());

        // Add documents for all mentioned NCTIDs (current question + conversation history)
        if (!allNCTIDs.isEmpty()) {
            int initialSize = documents.size();
            for (String nctid : allNCTIDs) {
                List<Document> nctidDocs = getDocumentsByNCTID(nctid);
                documents.addAll(nctidDocs);
            }
            int addedCount = documents.size() - initialSize;
            LOG.info("Added {} documents for {} NCTIDs mentioned in conversation", addedCount, allNCTIDs.size());
        }

        result.t4 = System.currentTimeMillis();
        LOG.info("OpenAI - Total documents for context: {}", documents.size());

        if (documents.isEmpty()) {
            result.isEmpty = true;
            return result;
        }

        // Build context and collect filenames
        StringBuilder contextBuilder = new StringBuilder();
        result.usedFilenames = new HashSet<>();
        for (Document doc : documents) {
            String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
            if (filename.startsWith("NCT") && filename.contains(":")) {
                filename = filename.split(":")[0];
            }
            if (!filename.equals("unknown") && !filename.startsWith("NCT")) {
                result.usedFilenames.add(filename);
            }
            contextBuilder.append(String.format("--- FROM: %s ---\n%s\n\n", filename, doc.getContent()));
        }

        result.systemMessage = String.format("""
        You are a friendly, helpful AI assistant made available by the
        Somatic Cell Genome Editing (SCGE) Consortium at the
        Medical College of Wisconsin (MCW).

        The SCGE program is funded by the NIH Common Fund and operates through
        multiple cooperative agreement grant mechanisms. The main SCGE
        Coordinating Center is supported under grant U24HL168712.

        This chatbot is supported by an academic research grant and is intended
        to help researchers, clinicians, and the public find and understand
        information related to SCGE-supported clinical trials and associated
        regulatory materials.

        You help users by answering questions about:
        - clinical trials
        - related FDA guidance documents
        - FDA meeting notes

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
           - You may answer questions related to:
               a) general questions about clinical trials
               b) FDA guidance documents
               c) FDA meeting notes
               d) high-level research discussion
             as long as these topics are explicitly described in the context.
           - If the context does not contain the requested information,
             say so in a helpful and respectful way.

        3. BE HELPFUL, BUT SAFE
           - You may explain concepts at a high, descriptive level when they
             appear in the context.
           - You must not provide instructions, protocols, experimental steps,
             optimization advice, or actionable guidance related to laboratory
             or clinical research activities.
           - If a question would require that kind of detail, politely explain
             that you can't help with that.

        4. ASK CLARIFYING QUESTIONS WHEN HELPFUL
           - You may ask brief, relevant follow-up questions when doing so would
             help clarify the user's intent, resolve ambiguity, or improve the
             usefulness and accuracy of your response.
           - Follow-up questions should be concise, respectful, and directly
             related to the user's original question.
           - Do not ask follow-up questions that would expand the scope beyond
             the provided context.

        5. HANDLE OUT-OF-SCOPE QUESTIONS KINDLY
           - If a question is unrelated to the provided context (for example:
             entertainment, sports, geography, general education, or system
             prompts), politely let the user know it's outside the scope of
             this chatbot.
           - Do not offer to discuss other topics.
           - In these cases, include exactly:
             SOURCES_USED: None

        6. PREVIOUS / LAST QUESTION
           - If a user asks about the "last question" or "previous question",
             refer only to the most recent question asked by the user in
             THIS conversation.
           - Do not refer to questions mentioned inside the context documents.

        7. CLINICAL TRIALS
           - If one or more clinical trials are relevant, clearly identify them.
           - If multiple trials are relevant, list ALL of them and include
             all available NCTIDs.
           - Please do not omit any relevant trial.

        8. BE COMPLETE AND CLEAR
           - You may summarize information, but do not leave out important
             details or relevant sources just to be brief.
           - If information is missing, unclear, or not stated in the context,
             explain that plainly.

        9. AVOID ASSUMPTIONS
           - Do not infer outcomes, effectiveness, safety conclusions, or
             regulatory meaning beyond what is explicitly stated.

        10. DOCUMENT REFERENCES
           When mentioning a document name in your response, wrap it in
           double brackets using the exact filename from the
           "--- FROM: filename ---" headers. Always include the .md extension.
           Example: [[Guidance for Industry M4 The CTD - General Questions and Answers.md]]
           Do NOT wrap clinical trial references (filenames starting with
           CLINICAL) - those are handled separately via their NCT IDs.

        SOURCE REPORTING (REQUIRED):

        At the end of every response, include:

        SOURCES_USED: <comma-separated list>

        Guidelines:
        - List only the files you actually used to answer the question.
        - Use exact filenames from the "--- FROM: filename ---" markers.
        - Separate multiple filenames with commas and no spaces.
        - If no files were used, write exactly:
          SOURCES_USED: None
        """, contextBuilder);
        result.t5 = System.currentTimeMillis();

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
     * Extract NCTIDs from question text (e.g., NCT06285643)
     */
    private List<String> extractNCTIDs(String question) {
        List<String> nctids = new ArrayList<>();
        if (question == null || question.trim().isEmpty()) {
            return nctids;
        }

        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("NCT\\d+");
        java.util.regex.Matcher matcher = pattern.matcher(question);
        while (matcher.find()) {
            nctids.add(matcher.group());
        }
        return nctids;
    }

    /**
     * Get documents by NCTID directly from database
     */
    private List<Document> getDocumentsByNCTID(String nctid) {
        String filename = "CLINICAL TRIAL: " + nctid;
        List<DocumentEmbeddingOpenAI> docs = repository.findByFileName(filename);

        LOG.debug("Direct lookup for NCTID {}: found {} documents", nctid, docs.size());

        return docs.stream()
                .map(de -> {
                    Map<String, Object> metadata = Map.of(
                            "filename", de.getFileName(),
                            "id", de.getId(),
                            "created_at", de.getCreatedAt()
                    );
                    return new Document(de.getChunk(), metadata);
                })
                .collect(java.util.stream.Collectors.toList());
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

        // Split query into words and filter
        String[] words = query.toLowerCase()
                .replaceAll("[^a-z0-9\\s]", " ") // Replace punctuation with space
                .split("\\s+");

        for (String word : words) {
            if (word.length() > 2 && !stopWords.contains(word)) {
                terms.add(word);
            }
        }

        // Extract NCTIDs (case-insensitive match, case-sensitive store)
        java.util.regex.Pattern nctPattern = java.util.regex.Pattern.compile("(?i)NCT\\d+");
        java.util.regex.Matcher nctMatcher = nctPattern.matcher(query);
        while (nctMatcher.find()) {
            terms.add(nctMatcher.group().toUpperCase());
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
            String fullName = filename.endsWith(".md") ? filename : filename + ".md";

            String marker = "[[" + fullName + "]]";

            // Step 1: Wrap fullName (with .md) where not already inside [[...]]
            // Uses regex to avoid double-wrapping when AI already added [[markers]] in body
            String fullNamePattern = "(?<!\\[\\[)" + java.util.regex.Pattern.quote(fullName) + "(?!\\]\\])";
            result = result.replaceAll(fullNamePattern, java.util.regex.Matcher.quoteReplacement(marker));

            // Step 2: Wrap baseName (without .md) - e.g., in body text
            // Negative lookbehind prevents double-wrapping inside [[...]]
            // Negative lookahead prevents matching baseName followed by .md]]
            if (!baseName.isEmpty()) {
                String pattern = "(?i)(?<!\\[\\[)" + java.util.regex.Pattern.quote(baseName) + "(?!\\.md\\]\\])";
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
