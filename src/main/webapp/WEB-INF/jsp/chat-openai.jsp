<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="org.springframework.security.core.context.SecurityContextHolder,
                  org.springframework.security.core.Authentication" %>
<%
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String username = "User";
    String contextPath = request.getContextPath();

    String pageTitle = "RGD AI Assistant";
    String pageDescription = "RGD AI Assistant - Ask questions about RGD data";
    String headContent = ""
        + "<link href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.6.0/css/all.min.css\" rel=\"stylesheet\" type=\"text/css\"/>\n"
        + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Roboto:wght@400;500;600;700&display=swap\"/>\n"
        + "<link rel=\"stylesheet\" href=\"" + contextPath + "/resources/css/style.css\"/>\n"
        + "<link rel=\"stylesheet\" href=\"" + contextPath + "/resources/css/chat-modern.css?v=" + System.currentTimeMillis() + "\"/>\n"
        + "<script src=\"https://cdn.jsdelivr.net/npm/marked/marked.min.js\"></script>\n"
        + "<script type=\"module\">\n"
        + "  import * as smd from 'https://cdn.jsdelivr.net/npm/streaming-markdown/smd.min.js';\n"
        + "  window.smd = smd;\n"
        + "</script>\n"
        + "<script>\n"
        + "  var username = \"" + username + "\";\n"
        + "  var contextPath = \"" + contextPath + "\";\n"
        + "</script>\n"
        + "<script src=\"" + contextPath + "/resources/js/script-openai.js?v=" + System.currentTimeMillis() + "\"></script>\n";
%>
<%@ include file="/common/headerarea.jsp" %>

<div class="chat-container chat-modern" data-logo="<%= contextPath %>/resources/images/ratChat-logo.jpg">
    <!-- Upload Modal -->
    <div id="uploadModal" class="modal">
        <div class="modal-content">
            <span class="closeModalSpan">&times;</span>
            <h2>Upload a file to OpenAI</h2>
            <form id="uploadForm" method="post" action="<%= contextPath %>/upload-openai" enctype="multipart/form-data" target="hiddenUploadFrame">
                <input type="file" name="file" id="file" required/>
                <input type="submit" value="Upload" class="submit-btn"/>
            </form>
            <div class="loader" id="loader">
                <div class="loading-spinner"></div>
            </div>
        </div>
    </div>

    <!-- URL Modal -->
    <div id="urlModal" class="modal">
        <div class="modal-content">
            <span class="closeUrlModalSpan">&times;</span>
            <h2>Process Website URL</h2>
            <form id="urlForm">
                <input type="url" name="url" id="urlInput" placeholder="https://example.com" required/>
                <input type="submit" value="Process" class="submit-btn"/>
            </form>
            <div class="loader" id="urlLoader">
                <div class="loading-spinner"></div>
            </div>
        </div>
    </div>

    <iframe name="hiddenUploadFrame" id="hiddenUploadFrame" style="display:none;"></iframe>

    <!-- Chat Area -->
    <div id="chatArea">
        <div id="header">
            <div class="header-title">
                <img class="header-logo-img" src="<%= contextPath %>/resources/images/ratChat-logo.jpg" alt="Rat Chat">
                <div>
                    <h2>RGD AI Assistant</h2>
                    <div class="header-subtitle">Answers drawn from RGD's gene, QTL, strain and disease reports</div>
                </div>
            </div>
            <div class="header-actions">
                <span id="modelSelectWrapper" class="model-select-wrapper" style="display: none;">
                    <label for="modelSelect">Model</label>
                    <select id="modelSelect" class="model-select"></select>
                </span>
                <button id="startOverBtn" class="start-over-btn" title="Start a new conversation">Clear memory</button>
                <%if((request.getServerName().equals("localhost")) || (request.getServerName().equals("dev.rgd.mcw.edu")) || (request.getServerName().equals("pipelines.rgd.mcw.edu"))){%>
                <a href="<%= contextPath %>/curation" target="_blank" class="curation-link">Curation</a>
                <%}%>
            </div>
        </div>

        <div id="transcript"></div>

        <div class="input-area">
            <div class="composer">
                <textarea id="userInput" placeholder="Ask about a rat gene, QTL, strain or disease..." rows="1" aria-label="Your question"></textarea>
                <button id="typedTextSubmit" class="submit-btn" aria-label="Send"><i class="fa-solid fa-arrow-up"></i></button>
            </div>
            <div class="composer-hint">
                <div id="disclaimer">
                    <div id="disclaimerHeader">
                        <span class="disclaimer-arrow">&#9660;</span>
                        <strong>Disclaimer</strong>
                    </div>
                    <div id="disclaimerContent">
                        This AI chatbot provides information from RGD's knowledge base. Responses are generated by AI and should be verified for accuracy.
                    </div>
                </div>
            </div>
        </div>
    </div>
</div>

<%@ include file="/common/footerarea.jsp" %>
