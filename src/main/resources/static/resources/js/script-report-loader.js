// ============================================================
// Report Loader - Frontend Logic
// ============================================================

var progressTimer = null;

// Active batch params (from DB check on page load or from startBulk)
var activeBatchType = null;
var activeBatchSpecies = null;

// Report types that don't have a species filter
var NO_SPECIES = ['strain', 'reference', 'project'];
// Report types that don't have an assembly filter
var NO_ASSEMBLY = ['strain', 'reference', 'project'];
// Report types that default to Rat (speciesKey=3)
var DEFAULT_RAT = ['gene', 'qtl', 'marker'];
var RAT_SPECIES_KEY = 3;

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
// Report Type change → show/hide species/assembly, load species list
// ============================================================
function onReportTypeChange() {
    var type = document.getElementById('reportType').value;
    var isOther = (type === 'other');
    var hasSpecies = !isOther && NO_SPECIES.indexOf(type) === -1;
    var hasAssembly = !isOther && NO_ASSEMBLY.indexOf(type) === -1;

    document.getElementById('speciesGroup').style.display = hasSpecies ? 'flex' : 'none';
    document.getElementById('assemblyGroup').style.display = hasAssembly ? 'flex' : 'none';
    document.getElementById('countDisplay').style.display = 'none';
    document.getElementById('bulkRow').style.display = isOther ? 'none' : 'flex';
    document.getElementById('urlRow').style.display = isOther ? 'flex' : 'none';
    document.getElementById('convertResult').style.display = 'none';
    hideError();

    // Reset selects
    var speciesSel = document.getElementById('speciesSelect');
    var assemblySel = document.getElementById('assemblySelect');
    speciesSel.innerHTML = '<option value="">Select...</option>';
    assemblySel.innerHTML = '<option value="">Select species first</option>';

    if (isOther) return;

    if (hasSpecies) {
        loadSpecies(type);
    } else {
        // Strain / reference / project — no filter; fetch count directly
        updateCount();
    }
}

function loadSpecies(type) {
    fetch(contextPath + '/report-loader/species?type=' + encodeURIComponent(type))
        .then(function(r) { return r.json(); })
        .then(function(list) {
            var sel = document.getElementById('speciesSelect');
            sel.innerHTML = '<option value="">Select species...</option>';
            list.forEach(function(s) {
                var opt = document.createElement('option');
                opt.value = s.key;
                opt.textContent = s.name;
                sel.appendChild(opt);
            });
            // Auto-select Rat for gene/qtl/marker, then trigger assembly load
            if (DEFAULT_RAT.indexOf(type) !== -1) {
                sel.value = String(RAT_SPECIES_KEY);
                if (sel.value === String(RAT_SPECIES_KEY)) {
                    onSpeciesChange();
                }
            }
        })
        .catch(function(e) { showError('Failed to load species: ' + e.message); });
}

function onSpeciesChange() {
    var type = document.getElementById('reportType').value;
    var speciesKey = document.getElementById('speciesSelect').value;
    var assemblySel = document.getElementById('assemblySelect');
    assemblySel.innerHTML = '<option value="">Loading...</option>';
    document.getElementById('countDisplay').style.display = 'none';

    if (!speciesKey) {
        assemblySel.innerHTML = '<option value="">Select species first</option>';
        return;
    }

    var hasAssembly = NO_ASSEMBLY.indexOf(type) === -1;
    if (!hasAssembly) {
        updateCount();
        return;
    }

    fetch(contextPath + '/report-loader/assemblies?species=' + encodeURIComponent(speciesKey))
        .then(function(r) { return r.json(); })
        .then(function(list) {
            assemblySel.innerHTML = '';
            // Primary first (default selection), then "All", then the rest
            list.forEach(function(m) {
                var opt = document.createElement('option');
                opt.value = m.mapKey;
                opt.textContent = m.name;
                assemblySel.appendChild(opt);
            });
            // "All" option
            var allOpt = document.createElement('option');
            allOpt.value = '0';
            allOpt.textContent = 'All';
            assemblySel.appendChild(allOpt);

            updateCount();
        })
        .catch(function(e) { showError('Failed to load assemblies: ' + e.message); });
}

function onAssemblyChange() {
    updateCount();
}

function updateCount() {
    var type = document.getElementById('reportType').value;
    var speciesKey = document.getElementById('speciesSelect').value || '0';
    var mapKey = document.getElementById('assemblySelect').value || '0';

    var display = document.getElementById('countDisplay');
    display.style.display = 'inline-flex';
    document.getElementById('countValue').textContent = '…';
    document.getElementById('countLabel').textContent = type + 's';

    var url = contextPath + '/report-loader/count?type=' + encodeURIComponent(type)
        + '&species=' + encodeURIComponent(speciesKey)
        + '&mapKey=' + encodeURIComponent(mapKey);

    fetch(url)
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (data.error) {
                display.style.display = 'none';
                showError('Count failed: ' + data.error);
                return;
            }
            document.getElementById('countValue').textContent = formatNumber(data.count);
        })
        .catch(function(e) {
            display.style.display = 'none';
            showError('Count failed: ' + e.message);
        });
}

// ============================================================
// Bulk Start / Pause / Resume / Progress
// ============================================================
function startBulk() {
    var type = document.getElementById('reportType').value;
    var speciesKey = parseInt(document.getElementById('speciesSelect').value || '0', 10);
    var mapKey = parseInt(document.getElementById('assemblySelect').value || '0', 10);
    var reset = document.getElementById('resetCheckbox').checked;

    var needsSpecies = NO_SPECIES.indexOf(type) === -1;
    if (needsSpecies && !speciesKey) {
        showError('Please select a species');
        return;
    }
    hideError();

    var btn = document.getElementById('bulkStartBtn');
    btn.disabled = true;

    activeBatchType = type;
    activeBatchSpecies = String(speciesKey);

    fetch(contextPath + '/report-loader/bulk/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            reportType: type,
            speciesKey: speciesKey,
            mapKey: mapKey,
            reset: reset
        })
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.error) {
            showError(data.error);
            btn.disabled = false;
            activeBatchType = null;
            activeBatchSpecies = null;
            return;
        }
        document.getElementById('bulkProgressSection').style.display = 'block';
        document.getElementById('bulkCancelBtn').style.display = '';
        setPauseMode();
        showToast('Bulk load started', 'success');
        startProgressPolling();
    })
    .catch(function(e) {
        btn.disabled = false;
        activeBatchType = null;
        activeBatchSpecies = null;
        showError('Start failed: ' + e.message);
    });
}

function pauseBulk() {
    fetch(contextPath + '/report-loader/bulk/cancel', { method: 'POST' })
        .then(function(r) { return r.json(); })
        .then(function() { showToast('Pausing...', 'info'); })
        .catch(function(e) { showError('Pause failed: ' + e.message); });
}

function resumeBulk() {
    var type = activeBatchType || document.getElementById('reportType').value;
    var speciesKey = parseInt(activeBatchSpecies || document.getElementById('speciesSelect').value || '0', 10);
    var mapKey = parseInt(document.getElementById('assemblySelect').value || '0', 10);

    activeBatchType = type;
    activeBatchSpecies = String(speciesKey);

    fetch(contextPath + '/report-loader/bulk/start', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            reportType: type,
            speciesKey: speciesKey,
            mapKey: mapKey,
            reset: false
        })
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.error) {
            showError(data.error);
            return;
        }
        setPauseMode();
        showToast('Bulk load resumed', 'success');
        startProgressPolling();
    })
    .catch(function(e) { showError('Resume failed: ' + e.message); });
}

function setPauseMode() {
    var btn = document.getElementById('bulkCancelBtn');
    btn.innerHTML = '<i class="fas fa-pause"></i> Pause';
    btn.onclick = pauseBulk;
    btn.className = 'action-btn danger-btn';
    document.getElementById('bulkStartBtn').disabled = true;
    document.getElementById('resetCheckbox').onchange = null;
}

function setResumeMode() {
    var btn = document.getElementById('bulkCancelBtn');
    btn.innerHTML = '<i class="fas fa-redo"></i> Resume';
    btn.onclick = resumeBulk;
    btn.className = 'action-btn bulk-btn';
    // Start Bulk only enabled when Start Fresh is checked
    var reset = document.getElementById('resetCheckbox');
    document.getElementById('bulkStartBtn').disabled = !reset.checked;
    reset.onchange = function() {
        document.getElementById('bulkStartBtn').disabled = !reset.checked;
    };
}

function startProgressPolling() {
    if (progressTimer) clearInterval(progressTimer);
    pollProgress();
    progressTimer = setInterval(pollProgress, 2000);
}

function stopProgressPolling() {
    if (progressTimer) {
        clearInterval(progressTimer);
        progressTimer = null;
    }
}

function pollProgress() {
    var type = activeBatchType || document.getElementById('reportType').value;
    var speciesKey = activeBatchSpecies || document.getElementById('speciesSelect').value || '0';

    fetch(contextPath + '/report-loader/bulk/progress?type=' + encodeURIComponent(type)
        + '&species=' + encodeURIComponent(speciesKey))
        .then(function(r) { return r.json(); })
        .then(function(d) {
            if (d.error) return;
            document.getElementById('progTotal').textContent = formatNumber(d.total);
            document.getElementById('progCompleted').textContent = formatNumber(d.completed);
            document.getElementById('progFailed').textContent = formatNumber(d.failed);
            document.getElementById('progPending').textContent = formatNumber(d.pending);

            var done = (d.completed || 0) + (d.failed || 0);
            var pct = d.total > 0 ? Math.round(100 * done / d.total) : 0;
            document.getElementById('progressFill').style.width = pct + '%';
            document.getElementById('progressPct').textContent = pct + '%';
            document.getElementById('progCurrent').textContent = d.running && d.currentSymbol
                ? 'Processing: ' + d.currentSymbol
                : '';

            if (!d.running) {
                stopProgressPolling();
                if (d.pending > 0) {
                    // Paused or interrupted — show Resume
                    setResumeMode();
                    showToast('Bulk load paused (' + formatNumber(d.completed) + ' completed, '
                        + formatNumber(d.pending) + ' pending)', 'info');
                } else {
                    // Finished — show Start Bulk
                    document.getElementById('bulkStartBtn').disabled = false;
                    document.getElementById('bulkCancelBtn').style.display = 'none';
                    document.getElementById('resetCheckbox').onchange = null;
                    activeBatchType = null;
                    activeBatchSpecies = null;
                    showToast('Bulk load finished (' + formatNumber(d.completed) + ' completed, '
                        + formatNumber(d.failed) + ' failed)', 'success');
                }
            }
        })
        .catch(function() { /* keep polling */ });
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
// Page-load active batch check
// ============================================================
function checkActiveBatch() {
    fetch(contextPath + '/report-loader/bulk/active')
        .then(function(r) { return r.json(); })
        .then(function(data) {
            if (!data.active) {
                // No active batch — use defaults (Gene + Rat)
                onReportTypeChange();
                return;
            }

            activeBatchType = data.reportType;
            activeBatchSpecies = String(data.speciesKey);

            // Set report type dropdown
            document.getElementById('reportType').value = data.reportType;

            // Show/hide species and assembly groups based on type
            var hasSpecies = NO_SPECIES.indexOf(data.reportType) === -1;
            var hasAssembly = NO_ASSEMBLY.indexOf(data.reportType) === -1;
            document.getElementById('speciesGroup').style.display = hasSpecies ? 'flex' : 'none';
            document.getElementById('assemblyGroup').style.display = hasAssembly ? 'flex' : 'none';
            document.getElementById('bulkRow').style.display = 'flex';
            document.getElementById('urlRow').style.display = 'none';

            // Load species list, then set the correct species
            if (hasSpecies) {
                fetch(contextPath + '/report-loader/species?type=' + encodeURIComponent(data.reportType))
                    .then(function(r) { return r.json(); })
                    .then(function(list) {
                        var sel = document.getElementById('speciesSelect');
                        sel.innerHTML = '<option value="">Select species...</option>';
                        list.forEach(function(s) {
                            var opt = document.createElement('option');
                            opt.value = s.key;
                            opt.textContent = s.name;
                            sel.appendChild(opt);
                        });
                        sel.value = String(data.speciesKey);

                        // Load assemblies and set mapKey
                        if (hasAssembly) {
                            fetch(contextPath + '/report-loader/assemblies?species=' + encodeURIComponent(data.speciesKey))
                                .then(function(r) { return r.json(); })
                                .then(function(aList) {
                                    var aSel = document.getElementById('assemblySelect');
                                    aSel.innerHTML = '';
                                    aList.forEach(function(m) {
                                        var opt = document.createElement('option');
                                        opt.value = m.mapKey;
                                        opt.textContent = m.name;
                                        aSel.appendChild(opt);
                                    });
                                    var allOpt = document.createElement('option');
                                    allOpt.value = '0';
                                    allOpt.textContent = 'All';
                                    aSel.appendChild(allOpt);
                                    if (data.mapKey) aSel.value = String(data.mapKey);
                                })
                                .catch(function() {});
                        }
                    })
                    .catch(function() {});
            }

            // Show progress section
            document.getElementById('bulkProgressSection').style.display = 'block';
            document.getElementById('bulkCancelBtn').style.display = '';
            if (data.running) {
                setPauseMode();
                startProgressPolling();
            } else {
                setResumeMode();
                // Do one poll to show current stats
                pollProgress();
            }
        })
        .catch(function() {
            // Fetch failed — use defaults
            onReportTypeChange();
        });
}

// ============================================================
// Initialize
// ============================================================
window.addEventListener('DOMContentLoaded', function() {
    document.getElementById('convertBtn').addEventListener('click', convertUrl);
    document.getElementById('reportType').addEventListener('change', onReportTypeChange);
    document.getElementById('speciesSelect').addEventListener('change', onSpeciesChange);
    document.getElementById('assemblySelect').addEventListener('change', onAssemblyChange);
    document.getElementById('bulkStartBtn').addEventListener('click', startBulk);
    document.getElementById('bulkCancelBtn').onclick = pauseBulk;

    document.getElementById('urlInput').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') convertUrl();
    });

    // Check for active batch first; falls back to onReportTypeChange() if none found
    checkActiveBatch();
});
