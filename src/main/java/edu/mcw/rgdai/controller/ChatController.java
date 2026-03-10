package edu.mcw.rgdai.controller;

import edu.mcw.rgdai.model.Answer;
import edu.mcw.rgdai.model.Question;
import jakarta.servlet.http.HttpServletRequest;
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
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.ai.document.Document;
import org.springframework.http.ResponseEntity;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);

    private ChatClient chatClient;
    private final VectorStore vectorStore;
    private final String configuredModel;
    private InMemoryChatMemory chatMemory;
    private final ChatModel ollamaChatModel;
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";

    public ChatController(
            ApplicationContext context,
            @Qualifier("ollamaVectorStore") VectorStore vectorStore,
            @Value("${spring.ai.ollama.chat.model}") String configuredModel) {

        LOG.info("🤖 Initializing Ollama ChatController with enhanced features");
        this.vectorStore = vectorStore;
        this.configuredModel = configuredModel;
        this.chatMemory = new InMemoryChatMemory();

        LOG.info("🎯 Configured Ollama Model: {}", configuredModel);

        // Find Ollama ChatModel
        Map<String, ChatModel> chatModels = context.getBeansOfType(ChatModel.class);
        ChatModel foundModel = null;
        for (Map.Entry<String, ChatModel> entry : chatModels.entrySet()) {
            if (entry.getValue().getClass().getSimpleName().toLowerCase().contains("ollama")) {
                foundModel = entry.getValue();
                LOG.info("✅ Found Ollama ChatModel: {}", entry.getKey());
                LOG.info("📋 ChatModel Class: {}", entry.getValue().getClass().getName());
                break;
            }
        }
        if (foundModel == null) {
            throw new RuntimeException("❌ Ollama ChatModel not found!");
        }
        this.ollamaChatModel = foundModel;
        this.chatClient = buildClient(ollamaChatModel, this.chatMemory);
        LOG.info("✅ Ollama ChatClient initialized successfully with model: {}", configuredModel);
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

        LOG.info("🟠 Ollama - Received question: {}", question.getQuestion());
        LOG.info("🎯 Processing with model: {}", configuredModel);

        String conversationId = getOrCreateConversationId(user, request);
        LOG.info("🟠 Using conversation ID: {}", conversationId);

        if (isGreeting(question.getQuestion())) {
            return new Answer("Hello! I'm the Ollama version. I can help you with questions about the documents in my knowledge base. What would you like to know?");
        }

        try {
            List<Document> documents = vectorStore.similaritySearch(
                    SearchRequest.query(question.getQuestion())
                            .withTopK(8)
                            .withSimilarityThreshold(0.38));
            LOG.info("🟠 Ollama - Retrieved {} documents from vector store", documents.size());

            if (documents.isEmpty()) {
                return new Answer("I don't have information about that topic in my knowledge base.");
            }

            StringBuilder contextBuilder = new StringBuilder();
            for (Document doc : documents) {
                String filename = doc.getMetadata().getOrDefault("filename", "unknown").toString();
                contextBuilder.append(String.format("--- FROM: %s ---\n%s\n\n", filename, doc.getContent()));
            }

            String systemMessage = String.format("""
                                                Answer using the context below OR conversation history. Do NOT use external knowledge about general topics, mountains, etc.
                                                When asked about "last question" or "previous question", refer to the most recent user message in the conversation.
                                                IMPORTANT: When asked about "last question" or "previous question", only refer to questions YOU were asked in THIS conversation, not questions mentioned in the document context.

                                                Context:
                                                ---------------------
                                                %s
                                                ---------------------
                                                When you use information from the context above, add "SOURCES_USED:[]" followed by the specific filenames that contained the information you referenced in your response. Only list sources you actually drew information from.
                                                ""\", contextBuilder);
                    """, contextBuilder.toString());

            String response = chatClient.prompt()
                    .system(systemMessage)
                    .user(question.getQuestion())
                    .advisors(spec -> spec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId))
                    .call()
                    .content();

            LOG.info("🟠 Ollama - Generated response using model: {}", configuredModel);
            return new Answer(response);

        } catch (Exception e) {
            LOG.error("❌ Ollama - Error generating response with model {}: {}", configuredModel, e.getMessage(), e);
            return new Answer("Ollama Error: " + e.getMessage());
        }
    }

    @PostMapping("/reset-memory")
    public ResponseEntity<Map<String, String>> resetChatMemory(
            Authentication user,
            HttpServletRequest request) {

        LOG.info("🔄 Ollama - Reset chat memory requested");
        try {
            String oldId = getOrCreateConversationId(user, request);
            chatMemory.clear(oldId);
            LOG.info("✅ Ollama - Cleared memory for conversation ID: {}", oldId);

            request.getSession().removeAttribute("ollama_conversation_id");

            String newId = "reset_" + System.currentTimeMillis();
            request.getSession().setAttribute("ollama_conversation_id", newId);
            LOG.info("✅ Ollama - Started new conversation with ID: {}", newId);

            InMemoryChatMemory newMemory = new InMemoryChatMemory();
            this.chatMemory = newMemory;
            this.chatClient = buildClient(this.ollamaChatModel, newMemory);

            Map<String, String> resp = new HashMap<>();
            resp.put("status", "success");
            resp.put("message", "Chat memory cleared successfully");
            resp.put("oldConversationId", oldId);
            resp.put("newConversationId", newId);
            return ResponseEntity.ok(resp);

        } catch (Exception e) {
            LOG.error("❌ Ollama - Error resetting chat memory: {}", e.getMessage(), e);
            Map<String, String> resp = new HashMap<>();
            resp.put("status", "error");
            resp.put("message", "Failed to reset chat memory");
            return ResponseEntity.status(500).body(resp);
        }
    }

    private boolean isGreeting(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        return text.toLowerCase().matches(".*\\b(hi|hello|hey|greetings)\\b.*");
    }

    private String getOrCreateConversationId(Authentication user, HttpServletRequest request) {
        String conversationId = (String) request.getSession().getAttribute("ollama_conversation_id");
        if (conversationId == null) {
            conversationId = (user != null) ? user.getName() : request.getSession().getId();
            request.getSession().setAttribute("ollama_conversation_id", conversationId);
        }
        return conversationId;
    }
}

//old code
////package edu.mcw.rgdai.controller;
////
////import edu.mcw.rgdai.model.Answer;
////import edu.mcw.rgdai.model.Question;
////import org.slf4j.Logger;
////import org.slf4j.LoggerFactory;
////import org.springframework.ai.chat.client.ChatClient;
////import org.springframework.ai.chat.client.advisor.PromptChatMemoryAdvisor;
////import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
////import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
////import org.springframework.ai.chat.memory.InMemoryChatMemory;
////import org.springframework.ai.vectorstore.SearchRequest;
////import org.springframework.ai.vectorstore.VectorStore;
////import org.springframework.security.core.Authentication;
////import org.springframework.web.bind.annotation.*;
////@RestController
////@RequestMapping("/chat")
////public class ChatController {
////    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
////    private final ChatClient chatClient;
////    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "conversationId";
////
////    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
////        LOG.info("Initializing ChatController");
////        this.chatClient = chatClientBuilder
////                .defaultAdvisors(
////                        new SimpleLoggerAdvisor(),
////                        new QuestionAnswerAdvisor(vectorStore, SearchRequest.defaults().withTopK(5)
////                                .withSimilarityThreshold(0.5)), // Lower threshold),  // Get top 5 matches
////                        new PromptChatMemoryAdvisor(new InMemoryChatMemory())
////                )
////                .build();
////        LOG.info("ChatClient initialized successfully");
////    }
////
////    @PostMapping
////    public Answer chat(@RequestBody Question question, Authentication user) {
////        LOG.info("Received question: {}", question.getQuestion());
////        Answer answer = chatClient.prompt()
////                .user(question.getQuestion())
////                .advisors(
////                        advisorSpec -> advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, user.getPrincipal())
////                )
////                .call()
////                .entity(Answer.class);
////        LOG.info("Generated answer: {}", answer.getAnswer());
////        return answer;
////    }
////}
//package edu.mcw.rgdai.controller;
//
//import edu.mcw.rgdai.model.Answer;
//import edu.mcw.rgdai.model.Question;
//import edu.mcw.rgdai.service.OllamaService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.ai.document.Document;
//import org.springframework.ai.vectorstore.SearchRequest;
//import org.springframework.ai.vectorstore.VectorStore;
//import org.springframework.security.core.Authentication;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
//@RestController
//@RequestMapping("/chat")
//public class ChatController {
//    private static final Logger LOG = LoggerFactory.getLogger(ChatController.class);
//    private final VectorStore vectorStore;
//    private final OllamaService ollamaService;
//
//    public ChatController(VectorStore vectorStore, OllamaService ollamaService) {
//        this.vectorStore = vectorStore;
//        this.ollamaService = ollamaService;
//        LOG.info("ChatController initialized successfully");
//    }
//
//    @PostMapping
//    public Answer chat(@RequestBody Question question, Authentication user) {
//        LOG.info("Received question: {}", question.getQuestion());
//
//        // 1. Retrieve relevant documents
//        List<Document> documents = vectorStore.similaritySearch(
//                SearchRequest.query(question.getQuestion()).withTopK(10)
//        );
//
//        // 2. Format the context
//        String context = documents.stream()
//                .map(Document::getContent)
//                .collect(Collectors.joining("\n\n"));
//
//        LOG.info("Retrieved context: {} characters", context.length());
//
//        // 3. Create prompt with context
//
//        //using this prompt
//        String prompt=String.format("""
//      You are an AI assistant that ONLY answers questions based on the provided context information.
//
//    IMPORTANT INSTRUCTIONS:
//    - If the answer is not contained in the context except for greeting messages such as hi, how are you doing etc, respond with ONLY: "I don't have information about that in my knowledge base."
//    - If the user says "hi" or greets you, respond EXACTLY: "Hello! I can help you find information from the uploaded documents. What would you like to know?"
//    - Do not use any knowledge outside of the provided context
//    - Do not make up or infer information not explicitly stated in the context
//
//    Context:
//    %s
//
//    Question: %s
//
//    Answer (based ONLY on the context above):
//    """, context, question.getQuestion());
//        String enhancedPrompt = String.format("""
//SYSTEM: You must follow these rules exactly. No exceptions.
//
//RULE 1: If user says "hi", "hello", or any greeting, respond ONLY with: "Hello! I can help you find information from the uploaded documents. What would you like to know?"
//
//RULE 2: If the question cannot be answered from the context below, respond ONLY with: "I don't have information about that in my knowledge base."
//
//RULE 3: Never explain what the context contains. Never mention document topics unless directly asked.
//
//RULE 4: Only answer if the exact information is in the context below.
//
//Context: %s
//
//User Question: %s
//
//Response:""", context, question.getQuestion());
//
//        // 4. Get response from Ollama
//        String response = ollamaService.generateResponse(enhancedPrompt);
//        System.out.println("Context length: {} characters"+context.length());
//        return new Answer(response);
//    }
//}