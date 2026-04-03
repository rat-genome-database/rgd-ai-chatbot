<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%
    String contextPath = request.getContextPath();

    String pageTitle = "RGD AI Assistant - Knowledge Curation";
    String pageDescription = "Manage documents in the RGD AI Assistant knowledge base";
    String headContent = ""
        + "<link href=\"https://cdnjs.cloudflare.com/ajax/libs/font-awesome/6.6.0/css/all.min.css\" rel=\"stylesheet\" type=\"text/css\"/>\n"
        + "<link rel=\"stylesheet\" href=\"" + contextPath + "/resources/css/curation.css?v=" + System.currentTimeMillis() + "\"/>\n"
        + "<script>\n"
        + "  var contextPath = \"" + contextPath + "\";\n"
        + "</script>\n"
        + "<script src=\"" + contextPath + "/resources/js/script-curation.js?v=" + System.currentTimeMillis() + "\"></script>\n";
%>
<%@ include file="/common/headerarea.jsp" %>

<div class="curation-container">
    <!-- Header -->
    <div class="curation-header">
        <div class="header-left">
            <h2>RatChat Knowledge Curation</h2>
            <p class="header-subtitle">Manage documents in the RatChat knowledge base</p>
        </div>
        <div class="header-right">
            <a href="<%= contextPath %>/chat" class="back-to-chat-btn"><i class="fas fa-comments"></i> Back to Chat</a>
        </div>
    </div>

    <!-- Stats Cards -->
    <div class="stats-row">
        <div class="stat-card">
            <div class="stat-icon"><i class="fas fa-file-alt"></i></div>
            <div class="stat-info">
                <span class="stat-number" id="statDocuments">--</span>
                <span class="stat-label">Documents</span>
            </div>
        </div>
        <div class="stat-card">
            <div class="stat-icon stat-icon-chunks"><i class="fas fa-puzzle-piece"></i></div>
            <div class="stat-info">
                <span class="stat-number" id="statChunks">--</span>
                <span class="stat-label">Total Chunks</span>
            </div>
        </div>
    </div>

    <!-- Documents Section -->
    <div class="documents-section">
        <div class="toolbar">
            <div class="search-box">
                <i class="fas fa-search search-icon"></i>
                <input type="text" id="docSearch" placeholder="Search documents by filename..." />
            </div>
            <button class="action-btn bulk-delete-btn" id="bulkDeleteDocBtn" style="display:none;">
                <i class="fas fa-trash-alt"></i> Delete Selected
            </button>
            <button class="action-btn upload-btn" id="uploadBtn">
                <i class="fas fa-cloud-upload-alt"></i> Upload Files
            </button>
        </div>
        <div class="pagination-bar pagination-top" id="docPaginationTop"></div>
        <div class="table-wrapper">
            <table class="data-table" id="documentsTable">
                <thead>
                    <tr>
                        <th class="col-check"><input type="checkbox" id="selectAllDocs" title="Select all"></th>
                        <th class="col-name">Filename</th>
                        <th class="col-chunks">Chunks</th>
                        <th class="col-date">Uploaded</th>
                        <th class="col-actions">Actions</th>
                    </tr>
                </thead>
                <tbody id="documentsBody">
                    <tr class="loading-row">
                        <td colspan="5"><div class="table-spinner"></div> Loading documents...</td>
                    </tr>
                </tbody>
            </table>
        </div>
        <div class="pagination-bar" id="docPagination"></div>
    </div>
</div>

<!-- Upload Modal -->
<div class="modal-overlay" id="uploadModal">
    <div class="modal-box">
        <div class="modal-header">
            <h3><i class="fas fa-cloud-upload-alt"></i> Upload Documents</h3>
            <button class="modal-close" id="uploadModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <div class="drop-zone" id="dropZone">
                <i class="fas fa-file-upload drop-icon"></i>
                <p>Drag & drop files here</p>
                <span class="drop-or">or</span>
                <label class="browse-btn">
                    Browse Files
                    <input type="file" id="fileInput" multiple accept=".md,.txt,.pdf,.docx" hidden />
                </label>
            </div>
            <div class="selected-files" id="selectedFiles" style="display:none;">
                <h4>Selected Files <span id="selectedCount"></span></h4>
                <ul id="fileList"></ul>
            </div>
            <div class="upload-progress" id="uploadProgress" style="display:none;">
                <h4>Upload Progress</h4>
                <div id="uploadStatusList"></div>
                <div class="upload-summary" id="uploadSummary" style="display:none;"></div>
            </div>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="uploadCancelBtn">Cancel</button>
            <button class="confirm-btn" id="uploadConfirmBtn" disabled>
                <i class="fas fa-upload"></i> Upload
            </button>
        </div>
    </div>
</div>

<!-- Preview Modal -->
<div class="modal-overlay" id="previewModal">
    <div class="modal-box modal-box-large">
        <div class="modal-header">
            <h3><i class="fas fa-eye"></i> Preview: <span id="previewFileName"></span></h3>
            <button class="modal-close" id="previewModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <div class="preview-info">
                <span id="previewChunkCount"></span> chunks
            </div>
            <div class="preview-chunks" id="previewChunks">
                <div class="table-spinner"></div> Loading chunks...
            </div>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="previewCloseBtn">Close</button>
        </div>
    </div>
</div>

<!-- Delete Confirmation Modal -->
<div class="modal-overlay" id="deleteModal">
    <div class="modal-box modal-box-small">
        <div class="modal-header modal-header-danger">
            <h3><i class="fas fa-trash-alt"></i> Confirm Delete</h3>
            <button class="modal-close" id="deleteModalClose">&times;</button>
        </div>
        <div class="modal-body">
            <p>Are you sure you want to delete <strong id="deleteFileName"></strong>?</p>
            <p class="delete-warning">This will permanently remove <span id="deleteChunkCount"></span> chunks from the knowledge base.</p>
        </div>
        <div class="modal-footer">
            <button class="cancel-btn" id="deleteCancelBtn">Cancel</button>
            <button class="danger-btn" id="deleteConfirmBtn">
                <i class="fas fa-trash-alt"></i> Delete
            </button>
        </div>
    </div>
</div>

<!-- Toast Notification -->
<div class="toast-container" id="toastContainer"></div>

<br>
<%@ include file="/common/footerarea.jsp" %>
