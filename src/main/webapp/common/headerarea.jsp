<%@ page import="java.util.ArrayList" %>
<%@ page import="java.util.Iterator" %>
<%@ page import="edu.mcw.rgd.datamodel.WatchedObject" %>
<%@ page import="edu.mcw.rgd.datamodel.WatchedTerm" %>
<%@ page import="edu.mcw.rgd.web.RgdContext" %>

<%
    String rgdBase = "";
    if (request.getServerName().equals("localhost")) {
        rgdBase = "https://dev.rgd.mcw.edu";
    }
%>

<html xmlns="http://www.w3.org/1999/xhtml">
<head>
    <meta name="keywords" content="<%=RgdContext.getLongSiteName(request)%>">
    <!--<META HTTP-EQUIV="CACHE-CONTROL" CONTENT="NO-CACHE, NO-STORE, MUST-REVALIDATE">-->
    <meta name="author" content="RGD">
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8" />
    <meta name="referrer" content="origin">
    <meta http-equiv="Content-Script-Type" content="text/javascript" />
    <meta http-equiv="Content-Style-Type" content="text/css" />
    <!--<meta http-equiv="Pragma" content="no-cache" />-->
    <!--<meta http-equiv="Expires" content="3000" />-->
    <%
        if (!pageDescription.equals("")) {
    %>
    <meta name="description" content="<%=pageDescription%>" />
    <% } %>

    <%=headContent%>



    <title><%=pageTitle%></title>

    <link rel="stylesheet" href="<%= rgdBase %>/rgdweb/css/jquery/jquery-ui-1.8.18.custom.css">
    <link rel="SHORTCUT ICON" href="<%= rgdBase %>/favicon.ico" />
    <link rel="stylesheet" type="text/css" href="<%= rgdBase %>/rgdweb/common/modalDialog/subModal.css" />
    <link rel="stylesheet" type="text/css" href="<%= rgdBase %>/rgdweb/common/modalDialog/style.css" />
    <link href="<%= rgdBase %>/rgdweb/common/rgd_styles-3.css?v=1" rel="stylesheet" type="text/css" />
    <link rel="stylesheet" href="<%= rgdBase %>/rgdweb/OntoSolr/jquery.autocomplete.css" type="text/css" />
    <link rel="stylesheet" href="<%= rgdBase %>/rgdweb/css/webFeedback.css" type="text/css"/>

    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/common/modalDialog/common.js"></script>
    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/common/modalDialog/subModal.js"></script>

    <script src="https://cdn.jsdelivr.net/npm/vue@2.6.12/dist/vue.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/axios/1.1.3/axios.js"></script>
    <script src="https://cdnjs.cloudflare.com/ajax/libs/popper.js/1.14.6/umd/popper.min.js"></script>
    <script src="https://cdn.plot.ly/plotly-latest.min.js"></script>
    <script src="<%= rgdBase %>/rgdweb/js/webFeedback.js" defer></script>

    <%@ include file="/common/googleAnalytics.jsp" %>

    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/js/rgdHomeFunctions-3.js"></script>


    <%
        String idForAngular = request.getParameter("id");
        if (idForAngular == null) {
            idForAngular=request.getParameter("acc_id");
            if (idForAngular == null) {
                idForAngular="";
            }
        }
    %>

    <script>
        function getLoadedObject() {
            return "<%=idForAngular%>";
        }
        function getGeneWatchAttributes() {
            //return ["Nomenclature Changes","New GO Annotation","New Disease Annotation","New Phenotype Annotation","New Pathway Annotation","New PubMed Reference","Altered Strains","New NCBI Transcript/Protein","New Protein Interaction","RefSeq Status Has Changed"];
            return <%= WatchedObject.getAllWatchedLabelsAsJSON()%>
        }
        function getTermWatchAttributes() {
            return <%= WatchedTerm.getAllWatchedLabelsAsJSON()%>
        }

    </script>
    <script>window.onunload = function () { };</script>
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <link rel="stylesheet" href="<%= rgdBase %>/rgdweb/css/elasticsearch/elasticsearch.css">
   <script src="<%= rgdBase %>/rgdweb/js/jquery/jquery-3.7.1.min.js"></script>
    <link rel="stylesheet" href="https://stackpath.bootstrapcdn.com/bootstrap/4.3.1/css/bootstrap.min.css" integrity="sha384-ggOyR0iXCbMQv3Xipma34MD+dH/1fQ784/j6cY/iJTQUOhcWr7x9JvoRxT2MZw1T" crossorigin="anonymous">
    <script src="https://stackpath.bootstrapcdn.com/bootstrap/4.3.1/js/bootstrap.min.js" integrity="sha384-JjSmVgyd0p3pXB1rRibZUAYoIIy6OrQ6VrjIEaFf/nJGzIxFDsf4x0xIM+B07jRM" crossorigin="anonymous"></script>


    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/common/angular/1.8.3/angular.js"></script>
    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/common/angular/1.8.3/angular-sanitize.js"></script>
    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/my/my.js?6"></script>


    <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/font-awesome/4.7.0/css/font-awesome.min.css">
    <link rel="stylesheet" type="text/css" href="<%= rgdBase %>/rgdweb/common/jquery-ui/jquery-ui.css">
    <script src="<%= rgdBase %>/rgdweb/common/jquery-ui/jquery-ui.js"></script>

    <script type="text/javascript" src="<%= rgdBase %>/rgdweb/js/elasticsearch/elasticsearchcommon.js"></script>
    <script src="https://accounts.google.com/gsi/client" async></script>

</head>

<style>
    a {
        color:#0C1D2E;
        text-decoration:underline;
        font-weight:700;
    }
    .speciesCardOverlay {
        position:absolute;
        background-color:#2865a3;
        min-width:63px;
        width:63px;
        height:63px;
        z-index:30;
        opacity:0;
        transition:opacity 0.2s;
    }
    .speciesCardOverlay:hover {
        opacity:.9;
        cursor:pointer;
        color:white;
    }
    .speciesIcon {
        border:1px solid #ccc;
        padding:2px;
        border-radius:3px;
        transition:border-color 0.2s;
    }
    .speciesIcon:hover {
        border-color:#2865a3;
    }
    .g_id_signin > div > div:first-child {
        display:none;
    }
    .GoogleLoginButtonContainer {
        display:inline-block;
        vertical-align:middle;
    }
</style>

<link href="https://fonts.googleapis.com/css?family=Marcellus+SC|Merienda+One|Source+Code+Pro&display=swap" rel="stylesheet">


<body  ng-cloak ng-app="rgdPage"  data-spy="scroll" data-target=".navbar" data-offset="10" style="position: relative;">
<%@ include file="/common/angularTopBodyInclude.jsp" %>
<%@ include file="/common/helpFeedbackChat.jsp" %>

<script>
    function googleSignIn(creds) {
        var resp = fetch("<%= rgdBase %>/rgdweb/my/account.html", {
            method: "POST",
            body: JSON.stringify({
                credential: creds.credential
            }),
            headers: {
                "Content-type": "application/json; charset=UTF-8"
            }
        }).then((response) => response.json())
            .then((json) => {
                document.getElementById("setUser").click();
            });



    }
</script>

<input style="display:none;" id="setUser" type="button" ng-click="rgd.setUser()" value="click"/>

<table class="wrapperTable" cellpadding="0" cellspacing="0" border="0">
    <tr>
        <td>

            <div id="headWrapper">

                <div class="top-bar">
                    <table width="100%" border="0" class="headerTable" cellpadding="0" cellspacing="0">
                        <tr>
                            <td align="left" style="color:white;" rowspan="3" width="10">

                                <div><a class="homeLink" href="<%= rgdBase %>/wg/home"><img style="border:3px solid rgba(255,255,255,0.2); border-radius:4px;" src="<%= rgdBase %>/rgdweb/common/images/rgd_logo.jpg" alt="Rat Genome Database"></a></div>

                            </td>


                            <td align="right" style="color:white;" valign="center" colspan="3">
                                <table>
                                    <tr>
                                        <td>
                                            <a href="<%= rgdBase %>/wg/registration-entry/">Submit Data</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/wg/help3">Help</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/wg/home/rgd_rat_community_videos/">Video Tutorials</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/wg/news2/">News</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/wg/home/rat-genome-database-publications">Publications</a>&nbsp;|&nbsp;

                                            <a href="https://download.rgd.mcw.edu">Download</a>&nbsp;|&nbsp;
                                            <a href="https://rest.rgd.mcw.edu/rgdws/swagger-ui/index.html">REST API</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/wg/citing-rgd">Citing RGD</a>&nbsp;|&nbsp;
                                            <a href="<%= rgdBase %>/rgdweb/contact/contactus.html">Contact</a>&nbsp;&nbsp;&nbsp;

                                        </td>
                                        <td>
                                        <div class="GoogleLoginButtonContainer">

                                            <div id="signIn">
                                            <div style="display:none;" id="g_id_onload"
                                                 data-client_id="833037398765-po85dgcbuttu1b1lco2tivl6eaid3471.apps.googleusercontent.com"
                                                 data-auto_prompt="false"
                                                 data-auto_select="true"
                                                 data-callback="googleSignIn"
                                            >
                                            </div>

                                            <div class="g_id_signin"
                                                 data-type="standard"
                                                 data-shape="rectangular"
                                                 data-theme="outline"
                                                 data-text="signin_with"
                                                 data-size="small"
                                                 data-logo_alignment="left">
                                            </div>
                                            </div>
                                            <div id="manageSubs" style="display:none;">
                                                <input  type="button" class="btn btn-info btn-sm"  value="Manage Subscriptions" ng-click="rgd.loadMyRgd($event)" style="background-color:#2B84C8;padding:1px 10px;font-size:12px;line-height:1.5;border-radius:3px"/>
                                            </div>
                                        </div>
                                        </td>
                                    </tr>
                                </table>
                            </td>
                        </tr>
                        <tr>
                            <td colspan="2">
                                <div class="rgd-navbar">
                                    <button class="rgd-menu-toggle" onclick="document.querySelector('.rgd-nav-items').classList.toggle('rgd-nav-open')"><i class="fa fa-bars"></i> Menu</button>
                                    <div class="rgd-nav-items">
                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/wg'">Home
                                            <i class="fa fa-caret-down"></i>
                                        </button>

                                        <div class="rgd-dropdown-content">
                                            <a href="<%= rgdBase %>/rgdweb/search/searchByPosition.html">Search RGD</a><!---RGDD-1856 New Search By Position added -->
                                            <a href="<%= rgdBase %>/wg/grants/">Grant Resources</a>
                                            <a href="<%= rgdBase %>/wg/citing-rgd/">Citing RGD</a>
                                            <a href="<%= rgdBase %>/wg/about-us/">About Us</a>
                                            <a href="<%= rgdBase %>/rgdweb/contact/contactus.html">Contact Us</a>
                                        </div>
                                    </div>
                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/wg/data-menu/'">Data
                                            <i class="fa fa-caret-down"></i>
                                        </button>

                                        <div class="rgd-dropdown-content">
                                            <a href="<%= rgdBase %>/rgdweb/search/genes.html?100">Genes</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/variants.html">Variants</a>
                                            <a href="<%= rgdBase %>/rgdweb/projects/project.html">Community Projects</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/qtls.html?100">QTLs</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/strains.html?100">Strains</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/markers.html?100">Markers</a>
                                            <a href="<%= rgdBase %>/rgdweb/report/genomeInformation/genomeInformation.html">Genome Information</a>
                                            <a href="<%= rgdBase %>/rgdweb/ontology/search.html">Ontologies</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/cellLines.html">Cell Lines</a>
                                            <a href="<%= rgdBase %>/rgdweb/search/references.html?100">References</a>
                                            <a href="https://download.rgd.mcw.edu">Download</a>
                                            <a href="<%= rgdBase %>/wg/registration-entry/">Submit Data</a>
                                        </div>
                                    </div>
                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/wg/tool-menu/'">Analysis & Visualization
                                            <i class="fa fa-caret-down"></i>
                                        </button>

                                        <div class="rgd-dropdown-content">
                                            <a href="<%=RgdContext.getSolrUrl("solr")%>" >OntoMate (Literature Search)</a>
                                            <a href="<%= rgdBase %>/rgdweb/jbrowse2/listing.jsp">JBrowse (Genome Browser)</a>
                                            <a href="<%= rgdBase %>/vcmap">Synteny Browser (VCMap)</a>
                                            <a href="<%= rgdBase %>/rgdweb/front/config.html">Variant Visualizer</a>

                                            <a href="<%= rgdBase %>/rgdweb/enrichment/start.html">Multi-Ontology Enrichment (MOET)</a>
                                            <a href="<%= rgdBase %>/rgdweb/ortholog/start.html">Gene-Ortholog Location Finder (GOLF)</a>
                                            <a href="<%= rgdBase %>/rgdweb/cytoscape/query.html">InterViewer (Protein-Protein Interactions)</a>
                                            <a href="<%= rgdBase %>/rgdweb/phenominer/ontChoices.html?species=3">PhenoMiner (Quantitative Phenotypes)</a>
                                            <a href="<%= rgdBase %>/rgdweb/ga/start.jsp">Gene Annotator</a>
                                            <a href="<%= rgdBase %>/rgdweb/generator/list.html">OLGA (Gene List Generator)</a>
                                            <a href="https://www.alliancegenome.org/bluegenes/alliancemine">AllianceMine</a>
                                            <a href="<%= rgdBase %>/rgdweb/gTool/Gviewer.jsp">GViewer (Genome Viewer)</a>
                                        </div>
                                    </div>
                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/rgdweb/portal/index.jsp'">Diseases
                                            <i class="fa fa-caret-down"></i>
                                        </button>
                                        <div class="rgd-dropdown-content">
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=1">Aging & Age-Related Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=16">Behavioral & Substance Use Disorder</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=2">Cancer & Neoplastic Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=3">Cardiovascular Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=14">Coronavirus Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=12">Developmental Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=4">Diabetes</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=5">Hematologic Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=6">Immune & Inflammatory Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=15">Infectious Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=13">Liver Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=7">Neurological Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=8">Obesity & Metabolic Syndrome</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=9">Renal Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=10">Respiratory Disease</a>
                                            <a href="<%= rgdBase %>/rgdweb/portal/home.jsp?p=11">Sensory Organ Disease</a>
                                        </div>

                                    </div>
                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/wg/physiology/'">Phenotypes & Models
                                            <i class="fa fa-caret-down"></i>
                                        </button>

                                        <div class="rgd-dropdown-content">
                                            <a href="<%= rgdBase %>/rgdweb/models/findModels.html">Find Models</a>
                                            <a href="<%= rgdBase %>/rgdweb/models/allModels.html">Genetic Models</a>
                                            <a href="<%= rgdBase %>/wg/autism-rat-model-resource/">Autism Models</a>
                                            <a href="<%= rgdBase %>/rgdweb/phenominer/ontChoices.html?species=3">Rat PhenoMiner (Quantitative Phenotypes)</a>
                                            <a href="<%= rgdBase %>/rgdweb/phenominer/ontChoices.html?species=4">Chinchilla PhenoMiner</a>
                                            <a href="<%= rgdBase %>/rgdweb/phenominer/phenominerExpectedRanges/views/home.html">Expected Ranges (Quantitative Phenotype)</a>
                                            <a href="<%= rgdBase %>/rgdweb/pa/termCompare.html?term1=RS%3A0000457&term2=CMO%3A0000000&countType=rec&species=3">PhenoMiner Term Comparison</a>
                                            <a href="<%= rgdBase %>/rgdweb/hrdp_panel.html">Hybrid Rat Diversity Panel</a>
                                            <a href="<%= rgdBase %>/wg/phenotype-data13/">Phenotypes</a>
                                            <a href="<%= rgdBase %>/wg/physiology/additionalmodels/">Phenotypes in Other Animal Models</a>
                                            <a href="<%= rgdBase %>/wg/strain-maintenance/">Animal Husbandry</a>
                                            <a href="<%= rgdBase %>/wg/physiology/strain-medical-records/">Strain Medical Records</a>
                                            <a href="<%= rgdBase %>/wg/phylogenetics/">Phylogenetics</a>
                                            <a href="<%= rgdBase %>/wg/strain-availability/">Strain Availability</a>
                                            <a href="https://download.rgd.mcw.edu/pub/data_release/Hi-res_Rat_Calendars/">Calendar</a>
                                            <a href="<%= rgdBase %>/wg/physiology/rats101/">Rats 101</a>
                                            <a href="<%= rgdBase %>/wg/photos-and-images/community-submissions/">Submissions</a>
                                            <a href="<%= rgdBase %>/wg/photos-and-images/physgen-photo-archive2/">Photo Archive</a>
                                        </div>
                                    </div>

                                    <a href="<%= rgdBase %>/wg/home/pathway2/">Pathways</a>

                                    <div class="rgd-dropdown">
                                        <button class="rgd-dropbtn" style="cursor:pointer" onclick="javascript:location.href='<%= rgdBase %>/wg/com-menu/'">Community
                                            <i class="fa fa-caret-down"></i>
                                        </button>

                                        <div class="rgd-dropdown-content">
                                            <a href="https://rgd.mcw.edu/wg/rat_forum_invite/">Rat Community Forum</a>
                                            <a href="<%= rgdBase %>/wg/com-menu/directory-of-rat-laboratories2/">Directory of Rat Laboratories</a>
                                            <a href="<%= rgdBase %>/wg/home/rgd_rat_community_videos/">Video Tutorials</a>
                                            <a href="<%= rgdBase %>/wg/news2/">News</a>
                                            <a href="<%= rgdBase %>/wg/home/rat-genome-database-publications/">RGD Publications</a>
                                            <a href="<%= rgdBase %>/wg/com-menu/poster_archive/">RGD Presentations Archive</a>
                                            <a href="<%= rgdBase %>/wg/nomenclature-guidelines/">Nomenclature Guidelines</a>
                                            <a href="<%= rgdBase %>/wg/resource-links/">Resource Links</a>
                                            <a href="<%= rgdBase %>/wg/resource-links/laboratory-resources/">Laboratory Resources</a>
                                            <a href="<%= rgdBase %>/wg/resource-links/employment-resources/">Employment Resources</a>
                                        </div>
                                    </div>
                                    </div><!-- end rgd-nav-items -->
                                </div>
                            </td>
                        </tr>
                        <tr>
                            <td >
                                <%@include file="../WEB-INF/jsp/search/elasticsearch/searchBox.jsp"%>
                            </td>
                            <td class="rgd-social-icons" align="right" style="white-space:nowrap; padding-right:10px;">
                                            <a href="https://www.facebook.com/pg/RatGenomeDatabase/posts/" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/social/facebook-20.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://twitter.com/ratgenome" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/social/twitter-20.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://www.linkedin.com/company/rat-genome-database" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/social/linkedin-20.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://www.youtube.com/channel/UCMpex8AfXd_JSTH3DIxMGFw?view_as=subscriber" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/social/youtube-20.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://github.com/rat-genome-database" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/GitHub_Logo_White-20.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://bsky.app/profile/ratgenome.bsky.social" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/blueSky.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                                            <a href="https://genomic.social/@ratgenome" style="margin:0 2px;"><img src="<%= rgdBase %>/rgdweb/common/images/mastadon.png" style="opacity:0.85; transition:opacity 0.2s;" onmouseover="this.style.opacity='1'" onmouseout="this.style.opacity='0.85'"/></a>
                            </td>
                        </tr>


                    </table>
                </div>

                <script>
                /* Mobile nav: convert dropdown hover to click-based toggle */
                document.addEventListener('DOMContentLoaded', function() {
                    document.querySelectorAll('.rgd-dropbtn[onclick]').forEach(function(btn) {
                        var match = btn.getAttribute('onclick').match(/location\.href='([^']+)'/);
                        var href = match ? match[1] : null;
                        btn.removeAttribute('onclick');
                        btn.addEventListener('click', function() {
                            if (window.innerWidth > 768) {
                                if (href) location.href = href;
                            } else {
                                var dd = this.closest('.rgd-dropdown');
                                document.querySelectorAll('.rgd-dropdown.rgd-dropdown-active').forEach(function(d) {
                                    if (d !== dd) d.classList.remove('rgd-dropdown-active');
                                });
                                dd.classList.toggle('rgd-dropdown-active');
                            }
                        });
                    });
                    /* Close mobile menu when clicking outside */
                    document.addEventListener('click', function(e) {
                        if (window.innerWidth <= 768 && !e.target.closest('.rgd-navbar')) {
                            document.querySelectorAll('.rgd-dropdown.rgd-dropdown-active').forEach(function(d) {
                                d.classList.remove('rgd-dropdown-active');
                            });
                        }
                    });
                });
                </script>

                <input type="hidden" id="speciesType" value="">




            </div>





<%--            </DIV>--%>
<%--            <!--end headwrapper -->--%>
<%--            </div>--%>

<%--            <script>--%>
<%--                if (location.href.indexOf("") == -1 &&--%>
<%--                    location.href.indexOf("https://www.rgd.mcw.edu") == -1 &&--%>
<%--                    location.href.indexOf("osler") == -1 &&--%>
<%--                    location.href.indexOf("horan") == -1 &&--%>
<%--                    location.href.indexOf("owen") == -1 &&--%>
<%--                    location.href.indexOf("hancock") == -1 &&--%>
<%--                    location.href.indexOf("preview.rgd.mcw.edu") == -1) {--%>
<%--                    document.getElementById("curation-top").style.visibility='visible';--%>
<%--                }--%>
<%--            </script>--%>

        </td>
    </tr>

</table>

    <%
    ArrayList error = (ArrayList) request.getAttribute("error");
    if (error != null) {
        Iterator errorIt = error.iterator();
        while (errorIt.hasNext()) {
            String err = (String) errorIt.next();
            out.println("<br><span style=\"color:red;font-size:20px;\">&nbsp;&nbsp;&nbsp;&nbsp;" + err + "</span>");
        out.println("<br>");
        }
    }
    ArrayList status = (ArrayList) request.getAttribute("status");
    if (status !=null) {
        Iterator statusIt = status.iterator();
        while (statusIt.hasNext()) {
            String stat = (String) statusIt.next();
            out.println("<br><span style=\"color:blue;font-size:20px;\">&nbsp;&nbsp;&nbsp;&nbsp;" + stat + "</span>");
        out.println("<br>");
        }
    }
%>



<div id="mainBody">
    <div id="contentArea" class="content-area">
        <table cellpadding="5" border=0 align="center" width="100%">
            <tr>
                <td colspan="3" align="left" valign="top">