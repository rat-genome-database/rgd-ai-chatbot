package edu.mcw.rgdai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Service
public class OllamaService {
    private static final Logger LOG = LoggerFactory.getLogger(OllamaService.class);
    private final String baseUrl;
    private final String model;
    private final RestTemplate restTemplate;

    public OllamaService(
            @Value("${spring.ai.ollama.base-url}") String baseUrl,
            @Value("${spring.ai.ollama.chat.model}") String model) {
        this.baseUrl = baseUrl;
        this.model = model;

        // Configure RestTemplate with longer timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10000);  // 10 seconds
        factory.setReadTimeout(120000);    // 2 minutes for complex responses
        this.restTemplate = new RestTemplate(factory);

        LOG.info("OllamaService initialized with model: {} at {}", model, baseUrl);
    }

    public String generateResponse(String prompt) {
        LOG.info("Generating response for prompt length: {} characters", prompt.length());
        LOG.debug("Prompt preview: {}...", prompt.substring(0, Math.min(200, prompt.length())));

        String url = baseUrl + "/api/generate";
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false); // Disable streaming for simpler response handling

        // Optimized options for better, more consistent responses
        Map<String, Object> options = new HashMap<>();
        options.put("temperature", 0.1);      // Lower temperature for more consistent responses
        options.put("top_p", 0.9);           // Focus on most likely tokens
        options.put("top_k", 40);            // Limit token choices
        options.put("num_predict", 2048);    // Allow longer responses
        options.put("stop", new String[]{"Human:", "USER:", "Question:", "QUESTION:"});  // Stop sequences
        requestBody.put("options", options);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);

        try {
            LOG.debug("Sending request to Ollama at: {}", url);
            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);

            if (response == null) {
                LOG.error("Received null response from Ollama");
                return "Error: No response received from the AI model.";
            }

            String generatedText = (String) response.get("response");
            if (generatedText == null || generatedText.trim().isEmpty()) {
                LOG.error("Received empty response from Ollama");
                return "Error: Empty response from the AI model.";
            }

            // Check if the response was cut off
            Boolean done = (Boolean) response.get("done");
            if (done != null && !done) {
                LOG.warn("Response may have been truncated");
                generatedText += "\n\n[Note: Response may have been truncated due to length limits]";
            }

            LOG.info("Generated response length: {} characters", generatedText.length());
            LOG.debug("Response preview: {}...", generatedText.substring(0, Math.min(200, generatedText.length())));

            return generatedText.trim();

        } catch (Exception e) {
            LOG.error("Error generating response from Ollama", e);

            // Provide more specific error messages
            if (e.getMessage().contains("Connection refused")) {
                return "Error: Cannot connect to the AI model. Please check if Ollama is running.";
            } else if (e.getMessage().contains("timeout")) {
                return "Error: The AI model took too long to respond. Please try a shorter question.";
            } else {
                return "Error: " + e.getMessage();
            }
        }
    }
}

//old code
//package edu.mcw.rgdai.service;
//
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Value;
//import org.springframework.http.HttpEntity;
//import org.springframework.http.HttpHeaders;
//import org.springframework.http.MediaType;
//import org.springframework.stereotype.Service;
//import org.springframework.web.client.RestTemplate;
//
//import java.util.HashMap;
//import java.util.Map;
//
//@Service
//public class OllamaService {
//    private static final Logger LOG = LoggerFactory.getLogger(OllamaService.class);
//    private final String baseUrl;
//    private final String model;
//    private final RestTemplate restTemplate;
//
//    public OllamaService(
//            @Value("${spring.ai.ollama.base-url}") String baseUrl,
//            @Value("${spring.ai.ollama.chat.model}") String model) {
//        this.baseUrl = baseUrl;
//        this.model = model;
//        this.restTemplate = new RestTemplate();
//    }
//
//    public String generateResponse(String prompt) {
//        LOG.info("Generating response for prompt: {}", prompt);
//
//        String url = baseUrl + "/api/generate";
//        Map<String, Object> requestBody = new HashMap<>();
//        requestBody.put("model", model);
//        requestBody.put("prompt", prompt);
//        requestBody.put("stream", false); // Important: disable streaming for simpler response handling
////        requestBody.put("options", Map.of("temperature", 0.0));
//        requestBody.put("options", Map.of(
//                "temperature", 0.1,     // Good for most tasks
//                "top_p", 0.95,          // Slightly higher for normal models
//                "repeat_penalty", 1.1   // Optional: prevent repetition
//        ));
//
//        HttpHeaders headers = new HttpHeaders();
//        headers.setContentType(MediaType.APPLICATION_JSON);
//
//        HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
//
//        try {
//            Map<String, Object> response = restTemplate.postForObject(url, request, Map.class);
//            String generatedText = (String) response.get("response");
//            LOG.info("Generated response: {}", generatedText);
//            return generatedText;
//        } catch (Exception e) {
//            LOG.error("Error generating response", e);
//            return "Error: " + e.getMessage();
//        }
//    }
//}