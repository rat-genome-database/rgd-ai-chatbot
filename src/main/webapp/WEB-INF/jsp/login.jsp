<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<!DOCTYPE html>
<html>
<head>
    <title>RGD AI Assistant - Login</title>
    <link rel="stylesheet" href="<%= request.getContextPath() %>/resources/css/style.css" />
</head>
<body>
<div class="login-container">
    <h1>Login</h1>
    <%
        // Check for "error" parameter and display message
        if (request.getParameter("error") != null) {
    %>
    <div class="error">Invalid username or password.</div>
    <%
        }
        // Check for "logout" parameter and display message
        if (request.getParameter("logout") != null) {
    %>
    <div class="info">You have been logged out.</div>
    <%
        }
    %>
    <form method="POST" action="<%= request.getContextPath() %>/login">
        <div class="form-group">
            <label>Username:</label>
            <input type="text" name="username" required/>
        </div>
        <div class="form-group">
            <label>Password:</label>
            <input type="password" name="password" required/>
        </div>
        <div class="form-group">
            <input type="submit" value="Login" class="submit-btn"/>
        </div>
    </form>
</div>
</body>
</html>
