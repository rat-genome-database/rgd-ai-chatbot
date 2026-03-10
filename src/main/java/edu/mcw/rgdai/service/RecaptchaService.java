package edu.mcw.rgdai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

@Service
public class RecaptchaService {

    private static final Logger LOG = LoggerFactory.getLogger(RecaptchaService.class);

    @Value("${recaptcha.secret.key}")
    private String secretKey;

    @Value("${recaptcha.verify.url}")
    private String verifyUrl;

    @Value("${recaptcha.threshold}")
    private double threshold;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Verify reCAPTCHA v3 token with Google
     * @param token The reCAPTCHA token from frontend
     * @return RecaptchaResponse containing success status and score
     */
    public RecaptchaResponse verifyToken(String token) {
        LOG.info("🔐 Verifying reCAPTCHA token...");

        try {
            // Prepare request parameters
            String url = String.format("%s?secret=%s&response=%s", verifyUrl, secretKey, token);

            // Call Google's verification API
            ResponseEntity<Map> response = restTemplate.postForEntity(url, null, Map.class);
            Map<String, Object> body = response.getBody();

            if (body == null) {
                LOG.error("reCAPTCHA verification failed: Empty response from Google");
                return new RecaptchaResponse(false, 0.0, "Empty response from Google");
            }

            boolean success = (Boolean) body.getOrDefault("success", false);
            double score = body.get("score") != null ? ((Number) body.get("score")).doubleValue() : 0.0;

            LOG.info("reCAPTCHA verification result - Success: {}, Score: {}, Threshold: {}",
                     success, score, threshold);

            if (!success) {
                LOG.warn("reCAPTCHA verification failed from Google");
                return new RecaptchaResponse(false, score, "Verification failed");
            }

            if (score < threshold) {
                LOG.warn("reCAPTCHA score {} is below threshold {}", score, threshold);
                return new RecaptchaResponse(false, score, "Score below threshold");
            }

            LOG.info("reCAPTCHA verification passed - Score: {}", score);
            return new RecaptchaResponse(true, score, "Verification successful");

        } catch (Exception e) {
            LOG.error("Error verifying reCAPTCHA token: {}", e.getMessage(), e);
            return new RecaptchaResponse(false, 0.0, "Error: " + e.getMessage());
        }
    }

    /**
     * Response object for reCAPTCHA verification
     */
    public static class RecaptchaResponse {
        private final boolean success;
        private final double score;
        private final String message;

        public RecaptchaResponse(boolean success, double score, String message) {
            this.success = success;
            this.score = score;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public double getScore() {
            return score;
        }

        public String getMessage() {
            return message;
        }
    }
}
