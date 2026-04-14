// ============================================================
// Report Loader - Frontend Logic
// ============================================================

// ============================================================
// Convert Single URL
// ============================================================
function convertUrl() {
    var url = document.getElementById('urlInput').value.trim();
    if (!url) {
        showError('Please enter a URL');
        return;
    }

    var btn = document.getElementById('convertBtn');
    btn.disabled = true;
    btn.innerHTML = '<i class="fas fa-spinner fa-spin"></i> Converting...';
    hideError();
    document.getElementById('convertResult').style.display = 'none';

    fetch(contextPath + '/report-loader/convert', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: url })
    })
    .then(function(res) { return res.json(); })
    .then(function(data) {
        if (data.error) {
            showError(data.error);
            return;
        }

        // Show metadata
        var meta = document.getElementById('resultMeta');
        meta.innerHTML = ''
            + '<div class="meta-row">'
            + '<span class="meta-label">Display Name:</span> '
            + '<strong>' + escapeHtml(data.displayName || data.title) + '</strong>'
            + '</div>'
            + '<div class="meta-row">'
            + '<span class="meta-label">Type:</span> ' + escapeHtml(data.reportType || 'unknown')
            + ' &nbsp;|&nbsp; '
            + '<span class="meta-label">RGD ID:</span> ' + escapeHtml(data.rgdId || 'N/A')
            + ' &nbsp;|&nbsp; '
            + '<span class="meta-label">Size:</span> ' + formatNumber(data.charCount) + ' chars, ' + data.lineCount + ' lines'
            + ' &nbsp;|&nbsp; '
            + '<span class="meta-label">Time:</span> ' + (data.elapsedMs / 1000).toFixed(1) + 's'
            + '</div>'
            + '<div class="meta-row">'
            + '<span class="meta-label">Saved to:</span> ' + escapeHtml(data.fileName)
            + ' (' + formatBytes(data.fileSize) + ')'
            + '</div>';

        // Show markdown preview
        document.getElementById('markdownPreview').textContent = data.markdown;
        document.getElementById('convertResult').style.display = 'block';

        showToast('Converted and saved: ' + (data.fileName || data.title), 'success');
    })
    .catch(function(err) {
        showError('Request failed: ' + err.message);
    })
    .finally(function() {
        btn.disabled = false;
        btn.innerHTML = '<i class="fas fa-magic"></i> Convert';
    });
}

// ============================================================
// Report Type change
// ============================================================
function onReportTypeChange() {
    var type = document.getElementById('reportType').value;
    var isOther = (type === 'other');

    document.getElementById('speciesGroup').style.display = isOther ? 'none' : 'flex';
    document.getElementById('assemblyGroup').style.display = isOther ? 'none' : 'flex';
    document.getElementById('countDisplay').style.display = 'none';
    document.getElementById('bulkRow').style.display = isOther ? 'none' : 'block';
    document.getElementById('urlRow').style.display = isOther ? 'flex' : 'none';
    document.getElementById('convertResult').style.display = 'none';
    hideError();
}

// ============================================================
// Toast notifications
// ============================================================
function showToast(message, type) {
    var container = document.getElementById('toastContainer');
    var toast = document.createElement('div');
    toast.className = 'toast toast-' + (type || 'info');
    toast.innerHTML = '<span>' + escapeHtml(message) + '</span>';
    container.appendChild(toast);
    setTimeout(function() { toast.classList.add('toast-show'); }, 10);
    setTimeout(function() {
        toast.classList.remove('toast-show');
        setTimeout(function() { toast.remove(); }, 300);
    }, 4000);
}

// ============================================================
// Error display
// ============================================================
function showError(msg) {
    document.getElementById('errorMessage').textContent = msg;
    var el = document.getElementById('errorDisplay');
    el.style.display = 'flex';
    el.style.alignItems = 'center';
    el.style.gap = '8px';
}

function hideError() {
    document.getElementById('errorDisplay').style.display = 'none';
}

// ============================================================
// Utility
// ============================================================
function escapeHtml(str) {
    if (!str) return '';
    var div = document.createElement('div');
    div.textContent = str;
    return div.innerHTML;
}

function formatNumber(n) {
    return n ? n.toLocaleString() : '0';
}

function formatBytes(bytes) {
    if (!bytes || bytes === 0) return '0 B';
    var k = 1024;
    var sizes = ['B', 'KB', 'MB', 'GB'];
    var i = Math.floor(Math.log(bytes) / Math.log(k));
    return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + ' ' + sizes[i];
}

// ============================================================
// Initialize
// ============================================================
window.addEventListener('DOMContentLoaded', function() {
    document.getElementById('convertBtn').addEventListener('click', convertUrl);
    document.getElementById('reportType').addEventListener('change', onReportTypeChange);

    document.getElementById('urlInput').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') convertUrl();
    });

    // Set correct initial visibility based on default report type
    onReportTypeChange();
});
