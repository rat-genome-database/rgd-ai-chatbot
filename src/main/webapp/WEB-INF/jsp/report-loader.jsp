<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
    String contextPath = request.getContextPath();

    String pageTitle = "RGD AI Assistant - Report Loader";
    String pageDescription = "Convert RGD report pages to markdown for the knowledge base";
    String headContent = ""
        + "<link href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.6.0/css/all.min.css\" rel=\"stylesheet\" type=\"text/css\"/>\n"
        + "<link rel=\"stylesheet\" href=\"" + contextPath + "/resources/css/report-loader.css?v=" + System.currentTimeMillis() + "\"/>\n"
        + "<script>\n"
        + "  var contextPath = \"" + contextPath + "\";\n"
        + "</script>\n"
        + "<script src=\"" + contextPath + "/resources/js/script-report-loader.js?v=" + System.currentTimeMillis() + "\" defer></script>\n";
%>
<%@ include file="/common/headerarea.jsp" %>

<div class="loader-container">
    <!-- Header -->
    <div class="loader-header">
        <div class="header-left">
            <h2><i class="fas fa-file-import"></i> RGD Report Loader</h2>
            <p class="header-subtitle">Convert RGD report pages to markdown for the knowledge base</p>
        </div>
        <div class="header-right">
            <a href="<%= contextPath %>/curation" class="back-btn"><i class="fas fa-database"></i> Knowledge Base</a>
        </div>
    </div>

    <!-- Convert Section -->
    <div class="section-card">
        <h3 class="section-title"><i class="fas fa-exchange-alt"></i> Convert Report</h3>

        <div class="controls-row">
            <div class="select-group">
                <label>Report Type</label>
                <select id="reportType">
                    <option value="gene">Gene</option>
                    <option value="qtl">QTL</option>
                    <option value="strain">Strain</option>
                    <option value="marker">Marker</option>
                    <option value="reference">Reference</option>
                    <option value="project">Project</option>
                    <option value="other">Other</option>
                </select>
            </div>
            <div class="select-group" id="speciesGroup">
                <label>Species</label>
                <select id="speciesSelect">
                    <option value="">Select...</option>
                </select>
            </div>
            <div class="select-group" id="assemblyGroup">
                <label>Assembly</label>
                <select id="assemblySelect">
                    <option value="">Select species first</option>
                </select>
            </div>
            <div class="count-display" id="countDisplay" style="display:none;">
                <span id="countValue">0</span> <span id="countLabel">records</span>
            </div>
        </div>

        <div class="url-row" id="urlRow" style="display:none;">
            <input type="text" id="urlInput"
                   placeholder="Enter any URL to convert to markdown..."
                   class="url-input" />
            <button class="action-btn convert-btn" id="convertBtn">
                <i class="fas fa-magic"></i> Convert
            </button>
        </div>
        <div class="bulk-row" id="bulkRow">
            <label class="reset-checkbox">
                <input type="checkbox" id="resetCheckbox" />
                <span>Start Fresh (delete existing records for this selection)</span>
            </label>
            <button class="action-btn bulk-btn" id="bulkStartBtn">
                <i class="fas fa-play"></i> Start Bulk
            </button>
        </div>

        <!-- Conversion Result -->
        <div id="convertResult" style="display:none;">
            <div class="result-meta" id="resultMeta"></div>
            <pre class="markdown-preview" id="markdownPreview"></pre>
        </div>

        <!-- Error Display -->
        <div class="error-display" id="errorDisplay" style="display:none;">
            <i class="fas fa-exclamation-circle"></i> <span id="errorMessage"></span>
        </div>
    </div>

    <!-- Bulk Progress Section -->
    <div class="section-card" id="bulkProgressSection" style="display:none;">
        <h3 class="section-title"><i class="fas fa-tasks"></i> Bulk Load Progress</h3>
        <div class="bulk-controls">
            <button class="action-btn danger-btn" id="bulkCancelBtn"><i class="fas fa-pause"></i> Pause</button>
        </div>
        <div class="progress-stats">
            <span class="stat-pill stat-total">Total: <strong id="progTotal">0</strong></span>
            <span class="stat-pill stat-completed">Completed: <strong id="progCompleted">0</strong></span>
            <span class="stat-pill stat-failed">Failed: <strong id="progFailed">0</strong></span>
            <span class="stat-pill stat-pending">Pending: <strong id="progPending">0</strong></span>
        </div>
        <div class="progress-current" id="progCurrent"></div>
        <div class="progress-track">
            <div class="progress-fill" id="progressFill" style="width:0%"></div>
        </div>
        <div class="progress-pct" id="progressPct">0%</div>
    </div>

    <!-- Generated Files Section (placeholder for Phase 2) -->
    <div class="section-card" id="generatedFilesSection" style="display:none;">
        <h3 class="section-title"><i class="fas fa-folder-open"></i> Generated Files</h3>
        <div class="table-wrapper">
            <table class="data-table" id="filesTable">
                <thead>
                    <tr>
                        <th class="col-name">Filename</th>
                        <th class="col-status">Status</th>
                        <th class="col-size">Size</th>
                        <th class="col-date">Date</th>
                        <th class="col-actions">Actions</th>
                    </tr>
                </thead>
                <tbody id="filesBody">
                    <tr class="empty-row"><td colspan="5">No files generated yet. Convert a URL above to get started.</td></tr>
                </tbody>
            </table>
        </div>
    </div>
</div>

<!-- Toast Notification -->
<div class="toast-container" id="toastContainer"></div>

<br>
<%@ include file="/common/footerarea.jsp" %>
