// ============================================================
// Report Loader - Frontend Logic
// ============================================================

var progressTimer = null;
var _resumeUpdateStartBtn = null; // listener ref for cleanup

// Active batch params (from DB check on page load or from startBulk)
var activeBatchType = null;
var activeBatchSpecies = null;
var activeBatchMapKey = null;

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
            assemblySel.dispatchEvent(new Event('change'));
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
    activeBatchMapKey = String(mapKey);

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
            activeBatchMapKey = null;
            return;
        }
        document.getElementById('bulkProgressSection').style.display = 'block';
        setPauseMode();
        showToast('Bulk load started', 'success');
        startProgressPolling();
    })
    .catch(function(e) {
        btn.disabled = false;
        activeBatchType = null;
        activeBatchSpecies = null;
        activeBatchMapKey = null;
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
    activeBatchMapKey = String(mapKey);

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

function retryFailed() {
    var type = activeBatchType || document.getElementById('reportType').value;
    var speciesKey = parseInt(activeBatchSpecies || document.getElementById('speciesSelect').value || '0', 10);
    var mapKey = parseInt(activeBatchMapKey || document.getElementById('assemblySelect').value || '0', 10);

    activeBatchType = type;
    activeBatchSpecies = String(speciesKey);
    activeBatchMapKey = String(mapKey);

    document.getElementById('bulkRetryBtn').classList.add('btn-hidden');

    fetch(contextPath + '/report-loader/bulk/retry', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            reportType: type,
            speciesKey: speciesKey,
            mapKey: mapKey
        })
    })
    .then(function(r) { return r.json(); })
    .then(function(data) {
        if (data.error) {
            showError(data.error);
            document.getElementById('bulkRetryBtn').classList.remove('btn-hidden');
            return;
        }
        setPauseMode();
        showToast('Retrying ' + formatNumber(data.reset) + ' failed records', 'success');
        startProgressPolling();
    })
    .catch(function(e) {
        document.getElementById('bulkRetryBtn').classList.remove('btn-hidden');
        showError('Retry failed: ' + e.message);
    });
}

function setPauseMode() {
    var btn = document.getElementById('bulkCancelBtn');
    btn.classList.remove('btn-hidden', 'bulk-btn');
    btn.classList.add('danger-btn');
    btn.innerHTML = '<i class="fas fa-pause"></i> Pause';
    btn.onclick = pauseBulk;
    document.getElementById('bulkStartBtn').disabled = true;
    document.getElementById('bulkRetryBtn').classList.add('btn-hidden');
    document.getElementById('resetCheckbox').onchange = null;
    if (_resumeUpdateStartBtn) {
        document.getElementById('reportType').removeEventListener('change', _resumeUpdateStartBtn);
        document.getElementById('assemblySelect').removeEventListener('change', _resumeUpdateStartBtn);
        _resumeUpdateStartBtn = null;
    }
}

function setResumeMode() {
    var btn = document.getElementById('bulkCancelBtn');
    btn.classList.remove('btn-hidden', 'danger-btn');
    btn.classList.add('bulk-btn');
    btn.innerHTML = '<i class="fas fa-redo"></i> Resume';
    btn.onclick = resumeBulk;

    var reset = document.getElementById('resetCheckbox');
    var startBtn = document.getElementById('bulkStartBtn');

    // Clean up previous listeners
    if (_resumeUpdateStartBtn) {
        document.getElementById('reportType').removeEventListener('change', _resumeUpdateStartBtn);
        document.getElementById('assemblySelect').removeEventListener('change', _resumeUpdateStartBtn);
    }

    _resumeUpdateStartBtn = function() {
        var selType = document.getElementById('reportType').value;
        var aSel = document.getElementById('assemblySelect');
        var selMapKey = aSel.value || '0';
        // Don't treat an unloaded assembly dropdown as "different"
        var assemblyReady = aSel.options.length > 0 && aSel.options[0].value !== '';
        var typeDifferent = selType !== activeBatchType;
        var mapDifferent = assemblyReady && selMapKey !== activeBatchMapKey;
        var isDifferent = typeDifferent || mapDifferent;
        // Different selection = new batch, enable Start freely
        // Same selection = require Start Fresh to restart from scratch
        startBtn.disabled = !isDifferent && !reset.checked;
    };

    _resumeUpdateStartBtn();
    reset.onchange = _resumeUpdateStartBtn;
    document.getElementById('reportType').addEventListener('change', _resumeUpdateStartBtn);
    document.getElementById('assemblySelect').addEventListener('change', _resumeUpdateStartBtn);
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
    var mapKey = activeBatchMapKey || document.getElementById('assemblySelect').value || '0';

    fetch(contextPath + '/report-loader/bulk/progress?type=' + encodeURIComponent(type)
        + '&species=' + encodeURIComponent(speciesKey)
        + '&mapKey=' + encodeURIComponent(mapKey))
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
            document.getElementById('progCurrent').innerHTML = d.running && d.currentSymbol
                ? 'Processing: ' + d.currentSymbol
                : '';

            if (d.running) {
                // Running — show Pause only
                setPauseMode();
            } else {
                stopProgressPolling();
                if (d.pending > 0) {
                    // Paused — show Resume (and Retry if failures exist)
                    setResumeMode();
                    if (d.failed > 0) {
                        document.getElementById('bulkRetryBtn').classList.remove('btn-hidden');
                    } else {
                        document.getElementById('bulkRetryBtn').classList.add('btn-hidden');
                    }
                    showToast('Bulk load paused (' + formatNumber(d.completed) + ' completed, '
                        + formatNumber(d.pending) + ' pending)', 'info');
                } else if (d.failed > 0) {
                    // Finished with failures — show only Retry
                    document.getElementById('bulkCancelBtn').classList.add('btn-hidden');
                    document.getElementById('bulkRetryBtn').classList.remove('btn-hidden');
                    document.getElementById('bulkStartBtn').disabled = false;
                    document.getElementById('resetCheckbox').onchange = null;
                    showToast('Bulk load finished with ' + formatNumber(d.failed) + ' failures ('
                        + formatNumber(d.completed) + ' completed)', 'warning');
                } else {
                    // Finished — all done
                    document.getElementById('bulkCancelBtn').classList.add('btn-hidden');
                    document.getElementById('bulkRetryBtn').classList.add('btn-hidden');
                    document.getElementById('bulkStartBtn').disabled = false;
                    document.getElementById('resetCheckbox').onchange = null;
                    activeBatchType = null;
                    activeBatchSpecies = null;
                    activeBatchMapKey = null;
                    showToast('Bulk load finished (' + formatNumber(d.completed) + ' completed)', 'success');
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
            activeBatchMapKey = String(data.mapKey || 0);

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
                                    aSel.dispatchEvent(new Event('change'));
                                })
                                .catch(function() {});
                        }
                    })
                    .catch(function() {});
            }

            // Show progress section
            document.getElementById('bulkProgressSection').style.display = 'block';
            if (data.running) {
                setPauseMode();
                startProgressPolling();
            } else {
                // One poll to determine correct state (Resume, Retry, or done)
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
    document.getElementById('bulkRetryBtn').addEventListener('click', retryFailed);

    document.getElementById('urlInput').addEventListener('keydown', function(e) {
        if (e.key === 'Enter') convertUrl();
    });

    // Check for active batch first; falls back to onReportTypeChange() if none found
    checkActiveBatch();
});
