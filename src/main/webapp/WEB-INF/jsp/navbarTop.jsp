<%@ page import="java.util.Map" %>

<%
    Map userAttributes = (Map) request.getSession().getAttribute("userAttributes");
    String platformBase1 = request.getServerName().equals("localhost") ? "https://dev.scge.mcw.edu" : "";
%>

<nav class="navbar navbar-expand-lg navbar-dark justify-content-end navbar-top" style="background-color: rgb(27, 128, 182)">
    <div class="container-fluid justify-content-end">
        <!-- Brand Logo -->

        <!--Toggle Button for Mobile -->
        <button class="navbar-toggler" type="button" data-toggle="collapse" data-target="#navbarNavTop">
            <span class="navbar-toggler-icon"></span>
        </button>

        <!-- Navbar Links -->
        <div class="collapse navbar-collapse justify-content-end" id="navbarNavTop">
            <ul class="navbar-nav mr-auto" >
                <%--        <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href=""><i class="fab fa-facebook"></i></a></li>--%>
                <%--        <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href=""><i class="fab fa-instagram"></i></a></li>--%>
                <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href="https://twitter.com/somaticediting" target="_blank" title="Twitter"><i class="fa-brands fa-x-twitter"></i></a></li>
                <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href="https://www.linkedin.com/company/somatic-cell-genome-editing-consortium/about/" target="_blank" title="LinkedIn"><i class="fab fa-linkedin"></i></a></li>
                <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href="https://www.youtube.com/channel/UCnMSf_YZdv1gIuqPmB6vrYw" target="_blank" title="YouTube"><i class="fab fa-youtube"></i></a></li>
                <li class="nav-item collapse-social-icons-dropdown"><a class="nav-link" href="https://bsky.app/profile/scge.bsky.social" target="_blank" title="Bluesky"><i class="fab fa-bluesky"></i></a></li>

            </ul>
            <ul class="navbar-nav ml-auto justify-content-end">
                <li class="nav-item text-nowrap"><a class="nav-link" href="<%= platformBase1 %>/platform/home">SCGE Platform Home</a></li>
                <li class="nav-item text-nowrap"><a class="nav-link" href="https://scge.mcw.edu">SCGE Consortium Home</a></li>
                <li class="nav-item text-nowrap"><a class="nav-link" href="https://scge.mcw.edu">SCGE Consortium Home</a></li>
                <li class="nav-item text-nowrap"><a class="nav-link" href="https://scge.mcw.edu/phase-2-tcdc/">About SCGE TCDC</a></li>
                <li class="nav-item text-nowrap"><a class="nav-link" href="https://scge.mcw.edu/contact/">Contact Us</a></li>
                <li class="nav-item text-nowrap"><a class="nav-link" href="https://creativecommons.org/licenses/by/4.0/" target="_blank">License</a></li>

                <li class="nav-item">&nbsp;</li>
                <li class="nav-item text-nowrap">
                    <% if (request.getServerName().equals("localhost") || request.getServerName().equals("dev.scge.mcw.edu") || request.getServerName().equals("stage.scge.mcw.edu") )
                    {if(userAttributes!=null && userAttributes.get("name")!=null){%>

                    <ul class="navbar-nav ml-auto">
                        <li class="nav-item text-nowrap" style="padding-right: 2%"><a href="/platform/dashboard"> <button class="btn btn-sm btn-warning">My Dashboard</button></a></li>
                        <li class="nav-item"><img class="rounded-circle " width="40" height="40" src='<%=userAttributes.get("picture")%>' alt=""></li>
                        <li class="nav-item text-nowrap text-light"  style="padding-right: 2%"><%=userAttributes.get("name")%></li>
                        <li class="nav-item" ><a href="/platform/logout" title="Sign out"><button class="btn btn-light btn-sm"><i class="fa fa-sign-out" aria-hidden="true"></i>&nbsp;Logout</button></a></li>
                    </ul>
                       <% }else{%>
                    <ul class="navbar-nav ml-auto">
                        <li class="nav-item"><a href="/platform/login.jsp" title="Consortium Member Sign In"><button class="btn btn-light btn-sm" >Login</button></a></li>
                    </ul>
                       <%}%>

                    <%}%>
                </li>
            </ul>
        </div>
    </div>
</nav>
