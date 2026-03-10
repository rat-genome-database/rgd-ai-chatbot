<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
    <title>Verifying...</title>
    <%
        String contextPath = request.getContextPath();
        String siteKey = (String) request.getAttribute("recaptchaSiteKey");
    %>
    <style>
        body {
            font-family: Arial, sans-serif;
            display: flex;
            justify-content: center;
            align-items: center;
            height: 100vh;
            margin: 0;
            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
        }
        .verify-container {
            text-align: center;
            background: white;
            padding: 40px;
            border-radius: 10px;
            box-shadow: 0 10px 30px rgba(0,0,0,0.3);
        }
        .spinner {
            border: 4px solid #f3f3f3;
            border-top: 4px solid #667eea;
            border-radius: 50%;
            width: 50px;
            height: 50px;
            animation: spin 1s linear infinite;
            margin: 20px auto;
        }
        @keyframes spin {
            0% { transform: rotate(0deg); }
            100% { transform: rotate(360deg); }
        }
        .verify-text {
            font-size: 24px;
            color: #333;
            margin-bottom: 10px;
        }
        .verify-subtext {
            font-size: 14px;
            color: #666;
        }
        .error-container {
            display: none;
            color: #d32f2f;
            margin-top: 20px;
        }
        .error-text {
            font-size: 18px;
            font-weight: bold;
        }
    </style>
    <!-- Load reCAPTCHA v3 script -->
    <script src="https://www.google.com/recaptcha/api.js?render=<%= siteKey %>"></script>
</head>
<body>
<div class="verify-container">
    <div id="verifyingSection">
        <div class="verify-text">Verifying your request...</div>
        <div class="spinner"></div>
        <div class="verify-subtext">This will only take a moment</div>
    </div>
    <div id="errorSection" class="error-container">
        <div class="error-text">Verification Failed</div>
        <div id="errorMessage" class="verify-subtext"></div>
    </div>
</div>

<script>
    var contextPath = "<%= contextPath %>";
    var recaptchaSiteKey = "<%= siteKey %>";

    // Execute reCAPTCHA v3 when page loads
    window.addEventListener('load', function() {
        grecaptcha.ready(function() {
            grecaptcha.execute(recaptchaSiteKey, {action: 'chat_access'})
                .then(function(token) {
                    console.log('reCAPTCHA token obtained');
                    // Send token to backend for verification
                    verifyToken(token);
                })
                .catch(function(error) {
                    console.error('Error getting reCAPTCHA token:', error);
                    showError('Failed to obtain verification token. Please refresh the page.');
                });
        });
    });

    function verifyToken(token) {
        fetch(contextPath + '/chat-openai/verify-recaptcha', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ token: token })
        })
        .then(response => response.json())
        .then(data => {
            console.log('Verification response:', data);
            if (data.success) {
                console.log('Verification successful! Redirecting to chat...');
                // Store verification in session and redirect
                window.location.href = contextPath + '/chat-verified';
            } else {
                console.error('Verification failed:', data.message);
                showError('Our system has detected potential automated activity. Please try again later. If the issue persists, contact us at RGD-Data@mcw.edu');
            }
        })
        .catch(error => {
            console.error('Error during verification:', error);
            showError('Connection error. Please check your internet and try again.');
        });
    }

    function showError(message) {
        document.getElementById('verifyingSection').style.display = 'none';
        document.getElementById('errorSection').style.display = 'block';
        document.getElementById('errorMessage').textContent = message;
    }
</script>
</body>
</html>
