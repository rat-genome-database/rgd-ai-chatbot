<%
    String platformBase = request.getServerName().equals("localhost") ? "https://dev.scge.mcw.edu" : "";
%>
<link href="<%= platformBase %>/platform/common/css/footer.css" rel="stylesheet" type="text/css"/>

<footer class="site-footer text-white">
  <div class="container footer-content">
    <div class="row">

      <!-- About Section (Empty for now, can be used later) -->
      <div class="col-md-4 footer-section">
      </div>

      <!-- Quick Links -->
      <div class="col-md-4 footer-section">
        <h5>Quick Links</h5>
        <ul class="footer-links">
          <li><a href="<%= platformBase %>/platform/home">Home</a></li>
          <li><a href="https://scge.mcw.edu/phase-2-tcdc/">About TCDC</a></li>
          <li><a href="https://scge.mcw.edu/contact/">Contact</a></li>
          <li><a class="nav-link" href="https://creativecommons.org/licenses/by/4.0/" target="_blank">License</a></li>
        </ul>
      </div>

      <!-- Social Media -->
      <div class="col-md-4 footer-section">
        <h5>Follow Us</h5>
        <ul class="footer-social-icons">
          <li><a href="https://twitter.com/somaticediting" target="_blank" title="Twitter"><i class="fa-brands fa-x-twitter"></i></a></li>
          <li><a href="https://www.linkedin.com/company/somatic-cell-genome-editing-consortium/about/" target="_blank" title="LinkedIn"><i class="fab fa-linkedin"></i></a></li>
          <li><a href="https://www.youtube.com/channel/UCnMSf_YZdv1gIuqPmB6vrYw" target="_blank" title="YouTube"><i class="fab fa-youtube"></i></a></li>
          <li><a href="https://bsky.app/profile/scge.bsky.social" target="_blank" title="Bluesky"><i class="fab fa-bluesky"></i></a></li>
        </ul>
      </div>

    </div>
  </div>

  <!-- Copyright -->
  <div class="footer-copyright">
    <div class="container">
      <div class="text-center">
        <p>Copyright &copy; 2024 SCGE Platform | All Rights Reserved | Hosted by the SCGE Consortium</p>
      </div>
    </div>
  </div>
</footer>
