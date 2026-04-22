// ============================================================
// State
// ============================================================
let pendingDeleteFileName = null;
let pendingDeleteChunkCount = 0;
let pendingBulkDelete = [];
let selectedFiles = [];

// Pagination state
let docPage = 1;
let docTotalPages = 1;
let docSearch = '';
const PAGE_SIZE = 50;

let searchDebounceTimer = null;

// ============================================================
// API calls
// ============================================================
const loadStats = () => {
    fetch(contextPath + '/curation/stats')
        .then(res => res.json())
        .then(data => {
            document.getElementById('statDocuments').textContent = data.totalDocuments;
            document.getElementById('statChunks').textContent = data.totalChunks.toLocaleString();
        })
        .catch(err => {
            console.error('Error loading stats:', err);
        });
};

const loadDocuments = () => {
    const params = new URLSearchParams({
        page: docPage,
        size: PAGE_SIZE,
        search: docSearch
    });

    fetch(contextPath + '/curation/files?' + params)
        .then(res => res.json())
        .then(data => {
            docTotalPages = data.totalPages;
            renderDocumentsTable(data.files);
            const docPageChange = (page) => { docPage = page; loadDocuments(); };
            renderPagination('docPaginationTop', docPage, docTotalPages, data.totalCount, docPageChange);
            renderPagination('docPagination', docPage, docTotalPages, data.totalCount, docPageChange);
        })
        .catch(err => {
            console.error('Error loading documents:', err);
            document.getElementById('documentsBody').innerHTML = '<tr class="empty-row"><td colspan="5">Failed to load documents</td></tr>';
        });
};

const refreshAll = () => {
    loadStats();
    loadDocuments();
};

const deleteFile = (fileName) => {
    fetch(contextPath + '/curation/files?fileName=' + encodeURIComponent(fileName), {
        method: 'DELETE'
    })
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                showToast(data.error, 'error');
            } else {
                showToast('Deleted ' + data.deletedChunks + ' chunks for ' + fileName, 'success');
                refreshAll();
            }
        })
        .catch(err => {
            console.error('Error deleting file:', err);
            showToast('Failed to delete file', 'error');
        });
};

const deleteBulk = (fileNames) => {
    let completed = 0;
    let totalDeleted = 0;
    let failed = 0;

    fileNames.forEach(fileName => {
        fetch(contextPath + '/curation/files?fileName=' + encodeURIComponent(fileName), {
            method: 'DELETE'
        })
            .then(res => res.json())
            .then(data => {
                if (data.error) {
                    failed++;
                } else {
                    totalDeleted += data.deletedChunks;
                }
            })
            .catch(() => { failed++; })
            .finally(() => {
                completed++;
                if (completed === fileNames.length) {
                    showToast('Deleted ' + totalDeleted + ' chunks from ' + (fileNames.length - failed) + ' files' +
                        (failed > 0 ? ' (' + failed + ' failed)' : ''), failed > 0 ? 'error' : 'success');
                    refreshAll();
                }
            });
    });
};

const previewFile = (fileName) => {
    document.getElementById('previewFileName').textContent = fileName;
    document.getElementById('previewChunkCount').textContent = '';
    document.getElementById('previewChunks').innerHTML = '<div class="table-spinner"></div> Loading chunks...';
    openModal('previewModal');

    fetch(contextPath + '/curation/files/preview?fileName=' + encodeURIComponent(fileName))
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                document.getElementById('previewChunks').innerHTML = '<p class="error-text">' + data.error + '</p>';
                return;
            }

            document.getElementById('previewChunkCount').textContent = data.chunkCount;

            let html = '';
            data.chunks.forEach((chunk, i) => {
                html += '<div class="chunk-card">';
                html += '<div class="chunk-header">Chunk ' + (i + 1) + '</div>';
                html += '<div class="chunk-body">' + escapeHtml(chunk) + '</div>';
                html += '</div>';
            });
            document.getElementById('previewChunks').innerHTML = html;
        })
        .catch(err => {
            console.error('Error previewing file:', err);
            document.getElementById('previewChunks').innerHTML = '<p class="error-text">Failed to load preview</p>';
        });
};

const uploadFiles = async (files) => {
    document.getElementById('uploadProgress').style.display = 'block';
    document.getElementById('uploadConfirmBtn').disabled = true;
    document.getElementById('uploadCancelBtn').disabled = true;

    let statusHtml = '<div class="upload-progress-bar">' +
        '<div class="progress-text" id="progressText">0 / ' + files.length + '</div>' +
        '<div class="progress-current" id="progressCurrent">Preparing...</div>' +
        '<div class="progress-track"><div class="progress-fill" id="progressFill" style="width: 0%"></div></div>' +
        '</div>';
    for (let i = 0; i < files.length; i++) {
        statusHtml += '<div class="upload-file-status" id="uploadStatus_' + i + '">';
        statusHtml += '<i class="fas fa-clock status-waiting"></i> ';
        statusHtml += '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>';
        statusHtml += '<span class="upload-file-state status-waiting">waiting</span>';
        statusHtml += '</div>';
    }
    document.getElementById('uploadStatusList').innerHTML = statusHtml;

    let newCount = 0;
    let updatedCount = 0;
    let failedCount = 0;

    for (let i = 0; i < files.length; i++) {
        const el = document.getElementById('uploadStatus_' + i);

        document.getElementById('progressCurrent').textContent = 'Processing: ' + files[i].name;

        el.innerHTML = '<i class="fas fa-spinner fa-spin"></i> ' +
            '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
            '<span class="upload-file-state">uploading...</span>';

        el.scrollIntoView({ behavior: 'smooth', block: 'nearest' });

        try {
            const formData = new FormData();
            formData.append('file', files[i]);

            const res = await fetch(contextPath + '/curation/upload', {
                method: 'POST',
                body: formData
            });
            const data = await res.json();

            let icon, stateClass, stateText;
            if (data.status === 'done') {
                icon = '<i class="fas fa-check-circle status-done"></i> ';
                stateClass = 'status-done';
                stateText = 'done (' + data.chunkCount + ' chunks)';
                newCount++;
            } else if (data.status === 'updated') {
                icon = '<i class="fas fa-sync-alt status-updated"></i> ';
                stateClass = 'status-updated';
                stateText = 'updated (' + data.chunkCount + ' chunks)';
                updatedCount++;
            } else {
                icon = '<i class="fas fa-times-circle status-failed"></i> ';
                stateClass = 'status-failed';
                stateText = 'failed: ' + (data.error || 'unknown error');
                failedCount++;
            }

            el.innerHTML = icon +
                '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
                '<span class="upload-file-state ' + stateClass + '">' + stateText + '</span>';

        } catch (err) {
            console.error('Error uploading file:', files[i].name, err);
            el.innerHTML = '<i class="fas fa-times-circle status-failed"></i> ' +
                '<span class="upload-file-name">' + escapeHtml(files[i].name) + '</span>' +
                '<span class="upload-file-state status-failed">failed: ' + err.message + '</span>';
            failedCount++;
        }

        const completed = i + 1;
        const pct = Math.round((completed / files.length) * 100);
        document.getElementById('progressText').textContent = completed + ' / ' + files.length;
        document.getElementById('progressFill').style.width = pct + '%';
    }

    document.getElementById('progressCurrent').textContent = 'Complete!';

    const summaryEl = document.getElementById('uploadSummary');
    summaryEl.style.display = 'block';
    summaryEl.innerHTML = '<strong>Summary:</strong> ' +
        files.length + ' files processed — ' +
        '<span class="status-done">' + newCount + ' new</span>, ' +
        '<span class="status-updated">' + updatedCount + ' updated</span>, ' +
        '<span class="status-failed">' + failedCount + ' failed</span>';

    document.getElementById('uploadCancelBtn').disabled = false;
    document.getElementById('uploadCancelBtn').textContent = 'Close';

    refreshAll();
    showToast('Upload complete: ' + newCount + ' new, ' + updatedCount + ' updated, ' + failedCount + ' failed', 'success');
};

// ============================================================
// Table rendering
// ============================================================
const renderDocumentsTable = (docs) => {
    const tbody = document.getElementById('documentsBody');

    if (docs.length === 0) {
        tbody.innerHTML = '<tr class="empty-row"><td colspan="5">' + (docSearch ? 'No documents matching "' + escapeHtml(docSearch) + '"' : 'No documents found') + '</td></tr>';
        updateBulkDeleteBtn();
        return;
    }

    let html = '';
    docs.forEach(doc => {
        html += '<tr>';
        html += '<td class="col-check"><input type="checkbox" class="row-check doc-check" data-filename="' + escapeAttr(doc.fileName) + '" data-chunks="' + doc.chunkCount + '"></td>';
        html += '<td class="col-name" title="' + escapeAttr(doc.fileName) + '">' + escapeHtml(doc.fileName) + '</td>';
        html += '<td class="col-chunks"><span class="chunk-badge">' + doc.chunkCount + '</span></td>';
        html += '<td class="col-date">' + formatDate(doc.uploadedAt) + '</td>';
        html += '<td class="col-actions">';
        html += '<button class="icon-btn preview-btn" title="Preview chunks" onclick="previewFile(\'' + escapeAttr(doc.fileName) + '\')"><i class="fas fa-eye"></i></button>';
        html += '<button class="icon-btn delete-btn" title="Delete" onclick="confirmDelete(\'' + escapeAttr(doc.fileName) + '\', ' + doc.chunkCount + ')"><i class="fas fa-trash-alt"></i></button>';
        html += '</td>';
        html += '</tr>';
    });
    tbody.innerHTML = html;

    tbody.querySelectorAll('.doc-check').forEach(cb => {
        cb.addEventListener('change', () => updateBulkDeleteBtn());
    });
    updateBulkDeleteBtn();
};

// ============================================================
// Pagination rendering
// ============================================================
const renderPagination = (containerId, currentPage, totalPages, totalCount, onPageChange) => {
    const container = document.getElementById(containerId);
    if (!container) return;

    if (totalPages <= 1) {
        container.innerHTML = totalCount > 0 ? '<span class="page-info">' + totalCount + ' total</span>' : '';
        return;
    }

    let html = '<span class="page-info">Page ' + currentPage + ' of ' + totalPages + ' (' + totalCount + ' total)</span>';
    html += '<div class="page-buttons">';

    html += '<button class="page-btn' + (currentPage === 1 ? ' disabled' : '') + '" data-page="' + (currentPage - 1) + '"' +
        (currentPage === 1 ? ' disabled' : '') + '><i class="fas fa-chevron-left"></i></button>';

    const pages = getPageNumbers(currentPage, totalPages);
    pages.forEach(p => {
        if (p === '...') {
            html += '<span class="page-ellipsis">...</span>';
        } else {
            html += '<button class="page-btn' + (p === currentPage ? ' active' : '') + '" data-page="' + p + '">' + p + '</button>';
        }
    });

    html += '<button class="page-btn' + (currentPage === totalPages ? ' disabled' : '') + '" data-page="' + (currentPage + 1) + '"' +
        (currentPage === totalPages ? ' disabled' : '') + '><i class="fas fa-chevron-right"></i></button>';

    html += '</div>';
    container.innerHTML = html;

    container.querySelectorAll('.page-btn:not(.disabled)').forEach(btn => {
        btn.addEventListener('click', () => {
            const page = parseInt(btn.dataset.page);
            if (page >= 1 && page <= totalPages) {
                onPageChange(page);
            }
        });
    });
};

const getPageNumbers = (current, total) => {
    if (total <= 7) {
        return Array.from({length: total}, (_, i) => i + 1);
    }

    const pages = [];
    pages.push(1);

    if (current > 3) {
        pages.push('...');
    }

    const start = Math.max(2, current - 1);
    const end = Math.min(total - 1, current + 1);
    for (let i = start; i <= end; i++) {
        pages.push(i);
    }

    if (current < total - 2) {
        pages.push('...');
    }

    pages.push(total);
    return pages;
};

// ============================================================
// Bulk delete
// ============================================================
const getCheckedFiles = () => {
    const checked = document.querySelectorAll('.doc-check:checked');
    const files = [];
    checked.forEach(cb => {
        files.push({
            fileName: cb.dataset.filename,
            chunkCount: parseInt(cb.dataset.chunks)
        });
    });
    return files;
};

const updateBulkDeleteBtn = () => {
    const btn = document.getElementById('bulkDeleteDocBtn');
    if (!btn) return;

    const checked = getCheckedFiles();
    if (checked.length > 0) {
        btn.style.display = 'inline-flex';
        btn.innerHTML = '<i class="fas fa-trash-alt"></i> Delete Selected (' + checked.length + ')';
    } else {
        btn.style.display = 'none';
    }
};

const toggleSelectAll = () => {
    const isChecked = document.getElementById('selectAllDocs').checked;
    document.querySelectorAll('.doc-check').forEach(cb => {
        cb.checked = isChecked;
    });
    updateBulkDeleteBtn();
};

const confirmBulkDelete = () => {
    const checked = getCheckedFiles();
    if (checked.length === 0) return;

    const totalChunks = checked.reduce((sum, f) => sum + f.chunkCount, 0);
    pendingBulkDelete = checked.map(f => f.fileName);

    document.getElementById('deleteFileName').textContent = checked.length + ' files';
    document.getElementById('deleteChunkCount').textContent = totalChunks;
    pendingDeleteFileName = null;
    openModal('deleteModal');
};

// ============================================================
// Delete confirmation
// ============================================================
const confirmDelete = (fileName, chunkCount) => {
    pendingDeleteFileName = fileName;
    pendingDeleteChunkCount = chunkCount;
    pendingBulkDelete = [];
    document.getElementById('deleteFileName').textContent = fileName;
    document.getElementById('deleteChunkCount').textContent = chunkCount;
    openModal('deleteModal');
};

const executeDelete = () => {
    closeModal('deleteModal');
    if (pendingBulkDelete.length > 0) {
        deleteBulk(pendingBulkDelete);
        pendingBulkDelete = [];
    } else if (pendingDeleteFileName) {
        deleteFile(pendingDeleteFileName);
        pendingDeleteFileName = null;
    }
};

// ============================================================
// Modal helpers
// ============================================================
const openModal = (id) => {
    document.getElementById(id).classList.add('active');
};

const closeModal = (id) => {
    document.getElementById(id).classList.remove('active');
};

// ============================================================
// Upload modal logic
// ============================================================
const openUploadModal = () => {
    selectedFiles = [];
    document.getElementById('fileInput').value = '';
    document.getElementById('selectedFiles').style.display = 'none';
    document.getElementById('uploadProgress').style.display = 'none';
    document.getElementById('uploadSummary').style.display = 'none';
    document.getElementById('uploadConfirmBtn').disabled = true;
    document.getElementById('uploadCancelBtn').disabled = false;
    document.getElementById('uploadCancelBtn').textContent = 'Cancel';
    document.getElementById('dropZone').style.display = 'flex';
    openModal('uploadModal');
};

const handleFilesSelected = (files) => {
    selectedFiles = Array.from(files);
    if (selectedFiles.length === 0) return;

    document.getElementById('selectedFiles').style.display = 'block';
    document.getElementById('selectedCount').textContent = '(' + selectedFiles.length + ')';
    document.getElementById('uploadConfirmBtn').disabled = false;

    let html = '';
    selectedFiles.forEach((file, i) => {
        html += '<li>';
        html += '<i class="fas fa-file"></i> ';
        html += '<span>' + escapeHtml(file.name) + '</span>';
        html += '<span class="file-size">' + formatFileSize(file.size) + '</span>';
        html += '<button class="remove-file-btn" onclick="removeSelectedFile(' + i + ')">&times;</button>';
        html += '</li>';
    });
    document.getElementById('fileList').innerHTML = html;
};

const removeSelectedFile = (index) => {
    selectedFiles.splice(index, 1);
    if (selectedFiles.length === 0) {
        document.getElementById('selectedFiles').style.display = 'none';
        document.getElementById('uploadConfirmBtn').disabled = true;
    } else {
        handleFilesSelected(selectedFiles);
    }
};

// ============================================================
// Toast notifications
// ============================================================
const showToast = (message, type) => {
    const container = document.getElementById('toastContainer');
    const toast = document.createElement('div');
    toast.className = 'toast toast-' + type;

    const icon = type === 'success' ? 'fa-check-circle' : type === 'error' ? 'fa-exclamation-circle' : 'fa-info-circle';
    toast.innerHTML = '<i class="fas ' + icon + '"></i> <span>' + message + '</span>' +
        '<button class="toast-close" onclick="this.parentElement.remove()">&times;</button>';

    container.appendChild(toast);

    setTimeout(() => {
        toast.classList.add('toast-fade');
        setTimeout(() => toast.remove(), 300);
    }, 4000);
};

// ============================================================
// Utility functions
// ============================================================
const escapeHtml = (str) => {
    if (!str) return '';
    const div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
};

const escapeAttr = (str) => {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
};

const formatDate = (dateStr) => {
    if (!dateStr) return '-';
    const date = new Date(dateStr);
    return date.toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
    });
};

const formatFileSize = (bytes) => {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
};

// ============================================================
// Server Files / Bulk Embed
// ============================================================
let embedProgressTimer = null;
let embedActivePath = null;

const openServerFilesModal = () => {
    openModal('serverFilesModal');
    loadDirectories();
    checkActiveEmbed();
};

const loadDirectories = () => {
    document.getElementById('dirListing').innerHTML =
        '<div class="table-spinner"></div> Loading directories...';

    fetch(contextPath + '/curation/bulk-embed/directories')
        .then(res => res.json())
        .then(data => {
            if (data.error) {
                document.getElementById('dirListing').innerHTML =
                    '<p class="error-text">' + escapeHtml(data.error) + '</p>';
                return;
            }
            renderDirectoryTable(data);
        })
        .catch(err => {
            document.getElementById('dirListing').innerHTML =
                '<p class="error-text">Failed to load directories</p>';
        });
};

const renderDirectoryTable = (dirs) => {
    if (!dirs || dirs.length === 0) {
        document.getElementById('dirListing').innerHTML =
            '<p style="color:#9ca3af; text-align:center; padding:24px;">No server files found. Use the Report Loader to generate markdown files first.</p>';
        return;
    }

    let html = '<table class="data-table dir-table"><thead><tr>';
    html += '<th style="width:25%; text-align:left !important;">Directory</th>';
    html += '<th style="width:13%; text-align:center !important;">Files</th>';
    html += '<th style="width:13%; text-align:center !important;">Embedded</th>';
    html += '<th style="width:13%; text-align:center !important;">Remaining</th>';
    html += '<th style="width:36%; text-align:center !important;">Action</th>';
    html += '</tr></thead><tbody>';

    dirs.forEach(d => {
        const remaining = d.remainingFiles || 0;
        html += '<tr>';
        html += '<td style="text-align:left;"><i class="fas fa-folder" style="color:#d97706; margin-right:8px;"></i>' + escapeHtml(d.path) + '</td>';
        html += '<td style="text-align:center;">' + (d.totalFiles || 0).toLocaleString() + '</td>';
        html += '<td style="text-align:center;"><span class="chunk-badge">' + (d.embeddedFiles || 0).toLocaleString() + '</span></td>';
        html += '<td style="text-align:center;">' + remaining.toLocaleString() + '</td>';
        html += '<td style="text-align:center; white-space:nowrap;">';
        if (remaining > 0) {
            html += '<button class="embed-action-btn" '
                  + 'onclick="startBulkEmbed(\'' + escapeAttr(d.path) + '\', false)">'
                  + '<i class="fas fa-play"></i> Embed</button> ';
            html += '<button class="reembed-action-btn" '
                  + 'onclick="startBulkEmbed(\'' + escapeAttr(d.path) + '\', true)">'
                  + '<i class="fas fa-redo"></i> Re-embed</button>';
        } else if (d.totalFiles > 0) {
            html += '<button class="reembed-action-btn" '
                  + 'onclick="startBulkEmbed(\'' + escapeAttr(d.path) + '\', true)">'
                  + '<i class="fas fa-redo"></i> Re-embed</button>';
        }
        html += '</td></tr>';
    });

    html += '</tbody></table>';
    document.getElementById('dirListing').innerHTML = html;
};

const startBulkEmbed = (path, forceReembed) => {
    const action = forceReembed ? 'Re-embed' : 'Embed';
    if (forceReembed && !confirm('Re-embed will delete existing embeddings and re-process all files in "' + path + '". Continue?')) {
        return;
    }

    fetch(contextPath + '/curation/bulk-embed/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: path, forceReembed: forceReembed })
    })
    .then(res => {
        if (res.status === 409) return res.json().then(d => { throw new Error(d.error); });
        return res.json();
    })
    .then(data => {
        if (data.error) {
            showToast(data.error, 'error');
            return;
        }
        embedActivePath = path;
        document.getElementById('embedProgressSection').style.display = 'block';
        setEmbedPauseMode();
        showToast(action + ' started for ' + path, 'success');
        startEmbedProgressPolling();
    })
    .catch(err => {
        showToast('Failed to start: ' + err.message, 'error');
    });
};

const pauseBulkEmbed = () => {
    fetch(contextPath + '/curation/bulk-embed/cancel', { method: 'POST' })
        .then(res => res.json())
        .then(() => showToast('Pausing...', 'info'))
        .catch(err => showToast('Pause failed: ' + err.message, 'error'));
};

const resumeBulkEmbed = () => {
    if (embedActivePath) {
        startBulkEmbed(embedActivePath, false);
    }
};

const retryBulkEmbed = () => {
    document.getElementById('embedRetryBtn').classList.add('btn-hidden');
    fetch(contextPath + '/curation/bulk-embed/retry', { method: 'POST' })
        .then(res => {
            if (res.status === 409) return res.json().then(d => { throw new Error(d.error); });
            return res.json();
        })
        .then(data => {
            if (data.error) {
                showToast(data.error, 'error');
                document.getElementById('embedRetryBtn').classList.remove('btn-hidden');
                return;
            }
            setEmbedPauseMode();
            showToast('Retrying ' + data.retryCount + ' failed files', 'success');
            startEmbedProgressPolling();
        })
        .catch(err => {
            showToast('Retry failed: ' + err.message, 'error');
            document.getElementById('embedRetryBtn').classList.remove('btn-hidden');
        });
};

const setEmbedPauseMode = () => {
    const btn = document.getElementById('embedCancelBtn');
    btn.classList.remove('btn-hidden');
    btn.className = 'action-btn danger-btn';
    btn.innerHTML = '<i class="fas fa-pause"></i> Pause';
    btn.onclick = pauseBulkEmbed;
    document.getElementById('embedRetryBtn').classList.add('btn-hidden');
};

const setEmbedResumeMode = () => {
    const btn = document.getElementById('embedCancelBtn');
    btn.classList.remove('btn-hidden');
    btn.className = 'action-btn embed-action-btn';
    btn.innerHTML = '<i class="fas fa-play"></i> Resume';
    btn.onclick = resumeBulkEmbed;
};

const startEmbedProgressPolling = () => {
    if (embedProgressTimer) clearInterval(embedProgressTimer);
    pollEmbedProgress();
    embedProgressTimer = setInterval(pollEmbedProgress, 2000);
};

const stopEmbedProgressPolling = () => {
    if (embedProgressTimer) {
        clearInterval(embedProgressTimer);
        embedProgressTimer = null;
    }
};

const pollEmbedProgress = () => {
    fetch(contextPath + '/curation/bulk-embed/progress')
        .then(res => res.json())
        .then(d => {
            if (d.error) return;

            document.getElementById('embedTotal').textContent = (d.total || 0).toLocaleString();
            document.getElementById('embedCompleted').textContent = (d.completed || 0).toLocaleString();
            document.getElementById('embedSkipped').textContent = (d.skipped || 0).toLocaleString();
            document.getElementById('embedFailed').textContent = (d.failed || 0).toLocaleString();
            document.getElementById('embedPending').textContent = (d.pending || 0).toLocaleString();

            const processed = (d.completed || 0) + (d.skipped || 0) + (d.failed || 0);
            const pct = d.total > 0 ? Math.round(100 * processed / d.total) : 0;
            document.getElementById('embedProgressFill').style.width = pct + '%';
            document.getElementById('embedProgressPct').textContent = pct + '%';

            const currentEl = document.getElementById('embedCurrent');
            currentEl.textContent = d.running && d.currentFile ? 'Processing: ' + d.currentFile : '';

            if (d.running) {
                setEmbedPauseMode();
            } else {
                stopEmbedProgressPolling();
                loadDirectories();  // Refresh directory counts
                refreshAll();       // Refresh embedded documents table

                if (d.pending > 0) {
                    setEmbedResumeMode();
                    if (d.failed > 0) {
                        document.getElementById('embedRetryBtn').classList.remove('btn-hidden');
                    }
                    showToast('Embedding paused (' + d.completed + ' embedded, ' + d.pending + ' pending)', 'info');
                } else if (d.failed > 0) {
                    document.getElementById('embedCancelBtn').classList.add('btn-hidden');
                    document.getElementById('embedRetryBtn').classList.remove('btn-hidden');
                    showToast('Embedding finished with ' + d.failed + ' failures (' + d.completed + ' embedded)', 'error');
                } else {
                    document.getElementById('embedCancelBtn').classList.add('btn-hidden');
                    document.getElementById('embedRetryBtn').classList.add('btn-hidden');
                    showToast('Embedding complete! ' + d.completed + ' files embedded, ' + d.skipped + ' skipped', 'success');
                }
            }
        })
        .catch(() => { /* keep polling */ });
};

const checkActiveEmbed = () => {
    fetch(contextPath + '/curation/bulk-embed/active')
        .then(res => res.json())
        .then(data => {
            if (data.active) {
                embedActivePath = data.path || null;
                document.getElementById('embedProgressSection').style.display = 'block';
                if (data.running) {
                    setEmbedPauseMode();
                    startEmbedProgressPolling();
                } else {
                    pollEmbedProgress(); // One poll to determine state
                }
            }
        })
        .catch(() => {});
};

// ============================================================
// Initialize
// ============================================================
window.addEventListener('load', () => {
    refreshAll();

    // Search input with debounce
    document.getElementById('docSearch').addEventListener('input', (e) => {
        clearTimeout(searchDebounceTimer);
        searchDebounceTimer = setTimeout(() => {
            docSearch = e.target.value;
            docPage = 1;
            loadDocuments();
        }, 300);
    });

    // Upload button
    document.getElementById('uploadBtn').addEventListener('click', openUploadModal);

    // Server Files button
    document.getElementById('serverFilesBtn').addEventListener('click', openServerFilesModal);

    // Server Files modal events
    document.getElementById('serverFilesModalClose').addEventListener('click', () => {
        closeModal('serverFilesModal');
        stopEmbedProgressPolling();
    });
    document.getElementById('serverFilesCloseBtn').addEventListener('click', () => {
        closeModal('serverFilesModal');
        stopEmbedProgressPolling();
    });

    // Upload modal events
    document.getElementById('uploadModalClose').addEventListener('click', () => closeModal('uploadModal'));
    document.getElementById('uploadCancelBtn').addEventListener('click', () => closeModal('uploadModal'));
    document.getElementById('uploadConfirmBtn').addEventListener('click', () => {
        if (selectedFiles.length > 0) {
            document.getElementById('dropZone').style.display = 'none';
            document.getElementById('selectedFiles').style.display = 'none';
            uploadFiles(selectedFiles);
        }
    });

    // File input change
    document.getElementById('fileInput').addEventListener('change', (e) => {
        handleFilesSelected(e.target.files);
    });

    // Drag and drop
    const dropZone = document.getElementById('dropZone');
    dropZone.addEventListener('dragover', (e) => {
        e.preventDefault();
        dropZone.classList.add('drag-over');
    });
    dropZone.addEventListener('dragleave', () => {
        dropZone.classList.remove('drag-over');
    });
    dropZone.addEventListener('drop', (e) => {
        e.preventDefault();
        dropZone.classList.remove('drag-over');
        handleFilesSelected(e.dataTransfer.files);
    });

    // Preview modal events
    document.getElementById('previewModalClose').addEventListener('click', () => closeModal('previewModal'));
    document.getElementById('previewCloseBtn').addEventListener('click', () => closeModal('previewModal'));

    // Delete modal events
    document.getElementById('deleteModalClose').addEventListener('click', () => closeModal('deleteModal'));
    document.getElementById('deleteCancelBtn').addEventListener('click', () => closeModal('deleteModal'));
    document.getElementById('deleteConfirmBtn').addEventListener('click', executeDelete);

    // Close modals on overlay click
    document.querySelectorAll('.modal-overlay').forEach(overlay => {
        overlay.addEventListener('click', (e) => {
            if (e.target === overlay) {
                overlay.classList.remove('active');
            }
        });
    });

    // Select All checkbox
    document.getElementById('selectAllDocs').addEventListener('change', () => toggleSelectAll());

    // Bulk delete button
    document.getElementById('bulkDeleteDocBtn').addEventListener('click', () => confirmBulkDelete());
});
