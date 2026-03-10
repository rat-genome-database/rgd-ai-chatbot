<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="org.springframework.security.core.context.SecurityContextHolder,
                  org.springframework.security.core.Authentication" %>
<%
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    String username = "User";
    String contextPath = request.getContextPath();

    String pageTitle = "RGD AI Assistant (Ollama)";
    String pageDescription = "RGD AI Assistant - Ollama powered chatbot";
    String headContent = ""
        + "<link rel=\"stylesheet\" href=\"" + contextPath + "/resources/css/style.css\"/>\n"
        + "<script>\n"
        + "  var username = \"" + username + "\";\n"
        + "  var contextPath = \"" + contextPath + "\";\n"
        + "</script>\n"
        + "<script src=\"" + contextPath + "/resources/js/script.js\"></script>\n";
%>
<%@ include file="/common/headerarea.jsp" %>

<div class="chat-container">
    <!-- Upload Modal -->
    <div id="uploadModal" class="modal">
        <div class="modal-content">
            <span class="closeModalSpan">&times;</span>
            <h2>Upload a file</h2>
            <form id="uploadForm" method="post" action="<%= contextPath %>/upload" enctype="multipart/form-data" target="hiddenUploadFrame">
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
            <h2>RGD AI Assistant (Ollama)</h2>
        </div>

        <div id="transcript"></div>
        <%if(request.getServerName().equals("localhost")){%>
        <div id="controls">
            <button id="uploadFile" class="upload-btn">Upload File</button>
            <button id="processUrl" class="upload-btn">Process URL</button>
        </div>
        <%}%>
        <div class="input-area">
            <textarea id="userInput" placeholder="Type your question here..." rows="3"></textarea>
            <button id="typedTextSubmit" class="submit-btn">Send</button>
        </div>
    </div>
</div>

<%@ include file="/common/footerarea.jsp" %>
