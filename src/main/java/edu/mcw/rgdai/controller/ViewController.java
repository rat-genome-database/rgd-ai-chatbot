package edu.mcw.rgdai.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
@Controller
public class ViewController {
    private static final Logger logger = LoggerFactory.getLogger(ViewController.class);

    @Value("${recaptcha.site.key}")
    private String recaptchaSiteKey;

    @GetMapping(value = {"", "/", "/home"})
    public String home() {
        logger.info("Accessing home page");
//        return "redirect:/login";
        return  "redirect:/chat";
    }

    @GetMapping("/login")
    public String login() {
        logger.info("Accessing login page");
        return "login";
    }

    @GetMapping("/chat")
    public String chat(jakarta.servlet.http.HttpSession session, Model model) {
        logger.info("Accessing chat page - checking reCAPTCHA verification");

        // Check if user has been verified by reCAPTCHA
        Boolean isVerified = (Boolean) session.getAttribute("recaptcha_verified");

        if (isVerified != null && isVerified) {
            logger.info("User already verified - showing chat page");
            return "chat-openai";
        } else {
            logger.info("User not verified - redirecting to verification page");
            // Pass the reCAPTCHA site key to the verification page
            model.addAttribute("recaptchaSiteKey", recaptchaSiteKey);
            return "verify";
        }
    }

    @GetMapping("/chat-verified")
    public String chatVerified(jakarta.servlet.http.HttpSession session) {
        logger.info("Accessing chat-verified endpoint");

        // This endpoint is only accessed after successful reCAPTCHA verification
        // Check if verification flag is set
        Boolean isVerified = (Boolean) session.getAttribute("recaptcha_verified");

        if (isVerified != null && isVerified) {
            logger.info("User verified - showing chat page");
            return "chat-openai";
        } else {
            logger.warn("User not verified but trying to access chat-verified - redirecting to verification");
            return "redirect:/chat";
        }
    }

    @GetMapping("/chat-openai")
    public String chatOpenAi() {
        logger.info("Accessing chat page");
        return "chat-openai";
    }

    @GetMapping("/chat-ollama")
    public String chatOllama() {
        logger.info("Accessing chat page");
        return "chat";
    }

}