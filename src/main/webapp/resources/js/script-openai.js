const addToTranscript = (who, text) => {
    const transcript = document.querySelector('#transcript');
    const name = (who === "User") ? username : who;

    // Create the new entry element
    const tempDiv = document.createElement('div');
    tempDiv.innerHTML = createTranscriptEntry(who, name, text);
    const newEntry = tempDiv.firstElementChild;

    // Append the new entry
    transcript.appendChild(newEntry);

    // Only scroll to beginning of AI responses (within transcript container only)
    if (who === "AI") {
        transcript.scrollTop = newEntry.offsetTop - transcript.offsetTop;
    }
};

const createTranscriptEntry = (who, name, text) => {
    const modelBadge = (who === "AI") ? '<span style="background: #00a67e; color: white; padding: 2px 6px; border-radius: 10px; font-size: 0.8em; margin-left: 5px;">OpenAI</span>' : '';
    // <div><b>${name}:</b> ${modelBadge} ${text}</div>
    return `
    <div class="${who}Entry">
        <div><b>${name}:</b> ${text}</div>
    </div>`;
};

const handleResponse = (response) => {
    // Hide typing indicator
    hideTypingIndicator();

    // Same order as dev server: marked first, then links
    let enhancedAnswer = marked.parse(response.answer);
    enhancedAnswer = convertNCTToLinks(enhancedAnswer);
    enhancedAnswer = convertMdToLinks(enhancedAnswer);
    enhancedAnswer = boldSourcesUsed(enhancedAnswer);
    enhancedAnswer = cleanupClinicalTrialSources(enhancedAnswer);
    addToTranscript("AI", enhancedAnswer);
};
// Function to convert NCT IDs to clickable links
const convertNCTToLinks = (text) => {
    // Pattern to match NCT IDs (NCT followed by 8 digits)
    const nctPattern = /\b(NCT\d{8})\b/g;

    return text.replace(nctPattern, (match, nctId) => {
        const host = window.location.hostname;
        const baseUrl = host.includes('stage') ? 'https://stage.rgd.mcw.edu' : 'https://rgd.mcw.edu';
        const url = `${baseUrl}/platform/data/report/clinicalTrials/${nctId}`;
        return `<a href="${url}" target="_blank">${nctId}</a>`;
    });
};

// Function to convert [[filename]] markers to clickable links
const convertMdToLinks = (text) => {
    // Pattern to match [[...]] markers from backend (with or without .md extension)
    const pattern = /\[\[([^\]]+)\]\]/g;

    return text.replace(pattern, (match, filename) => {
        filename = filename.trim();

        // Check if this is an RGD report: "RGD <Type> Report - <Name> (<RGD_ID>)"
        const rgdPattern = /^RGD\s+(\w+)\s+Report\s+-\s+(.+?)\s+\((\d+)\)$/;
        const rgdMatch = filename.match(rgdPattern);

        if (rgdMatch) {
            const type = rgdMatch[1].toLowerCase(); // gene, qtl, strain, etc.
            const name = rgdMatch[2];
            const rgdId = rgdMatch[3];
            const url = `https://rgd.mcw.edu/rgdweb/report/${type}/main.html?id=${rgdId}`;
            return `<a href="${url}" target="_blank">${filename}</a>`;
        }

        // Non-RGD files: link to uploaded docs (existing behavior)
        let baseFilename = filename;
        if (filename.endsWith('.md')) {
            baseFilename = filename.slice(0, -3);
        }
        const pdfFilename = baseFilename + '.pdf';
        const encodedFilename = encodeURIComponent(pdfFilename);

        let basePath;
        if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
            basePath = 'https://dev.rgd.mcw.edu/assistant';
        } else {
            basePath = contextPath;
        }

        const url = `${basePath}/docs/${encodedFilename}`;
        return `<a href="${url}" target="_blank">${baseFilename}</a>`;
    });
};

// Function to make "SOURCES_USED:" bold and format properly
const boldSourcesUsed = (text) => {
    // Add line break before SOURCES_USED if it's not already on its own line
    // Also add spaces after commas in the sources list
    return text.replace(/([^\n])\s*SOURCES_USED:\s*/g, '$1<strong class="sources-label">SOURCES USED:</strong> ')
               .replace(/SOURCES_USED:\s*/g, '<strong class="sources-label">SOURCES USED:</strong> ')
               .replace(/,(?=\S)/g, ', '); // Add space after comma if there isn't one
};

// Function to remove "CLINICAL TRIAL" prefix from sources (handles all AI format variations)
const cleanupClinicalTrialSources = (text) => {
    // Handles: "CLINICAL TRIAL: ", "CLINICAL-TRIAL_", "CLINICAL-TRIAL:", "CLINICAL_TRIAL_", etc.
    return text.replace(/CLINICAL[\s_-]*TRIAL[\s_:-]*/gi, '');
};

// Show typing indicator
const showTypingIndicator = () => {
    const transcript = document.querySelector('#transcript');

    // Create typing indicator HTML
    const indicatorHTML = `
        <div id="typingIndicator" class="typing-indicator active">
            <div class="typing-dots">
                <span>thinking</span>
                <div class="dot"></div>
                <div class="dot"></div>
                <div class="dot"></div>
            </div>
        </div>
    `;

    // Append it to transcript
    transcript.innerHTML += indicatorHTML;

    // Scroll to show the typing indicator
    transcript.scrollTop = transcript.scrollHeight;
};

// Hide typing indicator
const hideTypingIndicator = () => {
    const indicator = document.getElementById('typingIndicator');
    if (indicator) {
        indicator.remove();
    }
};

// Streaming toggle - set to false to use the old non-streaming behavior
const USE_STREAMING = true;

// API Interactions - OpenAI Endpoints
const postQuestion = (question) => {
    // Show typing indicator
    showTypingIndicator();

    fetch(contextPath + "/chat-openai", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({ question: question })
    })
        .then(res => res.json())
        .then(handleResponse)
        .catch(error => {
            console.error('Error:', error);
            hideTypingIndicator();
            addToTranscript("AI", "Sorry, there was an error processing your request with OpenAI.");
        });
};

// Streaming version - shows text as it arrives from OpenAI
const postQuestionStream = (question) => {
    showTypingIndicator();

    const transcript = document.querySelector('#transcript');

    fetch(contextPath + "/chat-openai/stream", {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "Accept": "text/event-stream"
        },
        body: JSON.stringify({ question: question })
    })
    .then(response => {
        if (!response.ok) {
            throw new Error(`HTTP error! Status: ${response.status}`);
        }

        hideTypingIndicator();

        // Create AI entry for streaming text
        const aiEntry = document.createElement('div');
        aiEntry.className = 'AIEntry';
        const contentDiv = document.createElement('div');
        contentDiv.innerHTML = '<b>AI:</b> ';
        const streamSpan = document.createElement('span');
        contentDiv.appendChild(streamSpan);
        aiEntry.appendChild(contentDiv);
        transcript.appendChild(aiEntry);

        // Scroll to show the AI entry
        transcript.scrollTop = aiEntry.offsetTop - transcript.offsetTop;

        // Setup streaming markdown parser
        const smdRenderer = smd.default_renderer(streamSpan);
        const smdParser = smd.parser(smdRenderer);

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let rawText = '';

        function processStream() {
            return reader.read().then(({ done, value }) => {
                if (done) return;

                buffer += decoder.decode(value, { stream: true });

                // Split on double newline (SSE event boundary)
                const parts = buffer.split('\n\n');
                buffer = parts.pop(); // Keep incomplete part in buffer

                for (const part of parts) {
                    if (!part.trim()) continue;

                    let eventName = 'message';
                    let dataLines = [];
                    const lines = part.split('\n');

                    for (const line of lines) {
                        if (line.startsWith('event:')) {
                            eventName = line.substring(6).trim();
                        } else if (line.startsWith('data:')) {
                            dataLines.push(line.substring(5));
                        }
                    }
                    // Join data lines with newlines (SSE spec: multi-line data)
                    const eventData = dataLines.join('\n');

                    if (eventName === 'metadata') {
                        // metadata received, no action needed during streaming
                    } else if (eventName === 'token') {
                        rawText += eventData;
                        // Strip [[, ]], and .md so smd doesn't misinterpret as link syntax
                        const cleanToken = eventData.replace(/\[\[/g, '').replace(/\.md\]\]/g, '').replace(/\]\]/g, '');
                        smd.parser_write(smdParser, cleanToken);
                    } else if (eventName === 'done') {
                        try { smd.parser_end(smdParser); } catch (e) { /* safe to ignore */ }
                        try {
                            const payload = JSON.parse(eventData);
                            // Convert [[...]] markers before markdown parsing so they aren't mangled
                            let preProcessed = convertMdToLinks(payload.fullResponse);
                            let enhanced = marked.parse(preProcessed);
                            enhanced = convertNCTToLinks(enhanced);
                            enhanced = boldSourcesUsed(enhanced);
                            enhanced = cleanupClinicalTrialSources(enhanced);
                            streamSpan.innerHTML = enhanced;
                        } catch (e) {
                            console.error('Error parsing done payload:', e);
                        }
                    } else if (eventName === 'error') {
                        streamSpan.textContent = 'Sorry, there was an error processing your request.';
                        console.error('Stream error:', eventData);
                    }
                }

                return processStream();
            });
        }

        return processStream();
    })
    .catch(error => {
        console.error('Streaming error:', error);
        hideTypingIndicator();
        addToTranscript("AI", "Sorry, there was an error processing your request with OpenAI.");
    });
};

const processUrl = (url) => {
    fetch(contextPath + "/process-url", {
        method: "POST",
        headers: {
            "Content-Type": "application/json"
        },
        body: JSON.stringify({ url: url })
    })
        .then(res => {
            if (!res.ok) {
                return res.json().then(errorData => {
                    throw new Error(errorData.error || `HTTP error! Status: ${res.status}`);
                });
            }
            return res.json();
        })
        .then(data => {
            document.getElementById("urlLoader").style.visibility = "hidden";
            document.getElementById("urlModal").style.display = "none";
            addToTranscript("RGD", `Processed URL: ${data.url} (${data.chunkCount} chunks extracted from "${data.title}")`);
        })
        .catch(error => {
            console.error('Error processing URL:', error);
            document.getElementById("urlLoader").style.visibility = "hidden";
            addToTranscript("RGD", `Error processing URL: ${error.message}`);
        });
};

// Event Handlers
const submitTypedText = (event) => {
    const typedTextInput = document.querySelector('#userInput');
    const typedText = typedTextInput.value.trim();

    if (typedText.length === 0) {
        return false;
    }

    addToTranscript("User", typedText);
    if (USE_STREAMING) {
        postQuestionStream(typedText);
    } else {
        postQuestion(typedText);
    }
    typedTextInput.value = '';
    return false;
};

const startOverChat = () => {
    if (confirm("Are you sure you want to start over? This will clear the AI's memory of our conversation.")) {
        // Disable button during request
        const startOverBtn = document.getElementById("startOverBtn");
        startOverBtn.disabled = true;
        startOverBtn.textContent = "Starting over...";

        fetch(contextPath + "/chat-openai/reset-memory", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            }
        })
            .then(res => res.json())
            .then(data => {
                if (data.status === "success") {
                    addToTranscript("RGD", "Chat memory cleared - starting fresh conversation");
                } else {
                    addToTranscript("RGD", "Error: " + data.message);
                }
            })
            .catch(error => {
                console.error('Error:', error);
                addToTranscript("RGD", "Error clearing chat memory");
            })
            .finally(() => {
                // Re-enable button
                startOverBtn.disabled = false;
                startOverBtn.textContent = "Clear memory";
            });
    }
};

// Initialize UI Events
const initUIEvents = () => {
    // Submit button click
    const submitButton = document.querySelector('#typedTextSubmit');
    submitButton.addEventListener('click', submitTypedText);

    // Enter key in textarea
    const textarea = document.querySelector('#userInput');
    textarea.addEventListener('keydown', e => {
        if (e.key === "Enter" && !e.shiftKey) {
            e.preventDefault();
            submitTypedText(e);
        }
    });

    // File upload modal
    const modal = document.getElementById("uploadModal");
    const openModalBtn = document.getElementById("uploadFile");
    const closeModalSpan = document.getElementsByClassName("closeModalSpan")[0];
    if (openModalBtn) {
        openModalBtn.addEventListener('click', () => {
            modal.style.display = "block";
        });
    }
    if (closeModalSpan) {
        closeModalSpan.addEventListener('click', () => {
            modal.style.display = "none";
        });
    }

    // URL modal
    const urlModal = document.getElementById("urlModal");
    const openUrlModalBtn = document.getElementById("processUrl");
    const closeUrlModalSpan = document.getElementsByClassName("closeUrlModalSpan")[0];
    if (openUrlModalBtn) {
        openUrlModalBtn.addEventListener('click', () => {
            urlModal.style.display = "block";
        });
    }
    if (closeUrlModalSpan) {
        closeUrlModalSpan.addEventListener('click', () => {
            urlModal.style.display = "none";
        });
    }

    // URL form submission
    const urlForm = document.getElementById("urlForm");
    urlForm.addEventListener('submit', (e) => {
        e.preventDefault();
        const urlInput = document.getElementById("urlInput");
        const url = urlInput.value.trim();

        if (url) {
            document.getElementById("urlLoader").style.visibility = "visible";
            processUrl(url);
            urlInput.value = '';
        }
    });

    // Close modals when clicking outside
    window.addEventListener('click', (event) => {
        if (event.target === modal) {
            modal.style.display = "none";
        }
        if (event.target === urlModal) {
            urlModal.style.display = "none";
        }
    });

    // File upload handling
    const uploadForm = document.getElementById("uploadForm");
    uploadForm.addEventListener('submit', () => {
        const filename = uploadForm.elements[0].value;
        if (filename && filename.length > 0) {
            document.getElementById("loader").style.visibility = "visible";
        }
    });

    const hiddenUploadFrame = document.getElementById("hiddenUploadFrame");
    hiddenUploadFrame.addEventListener('load', () => {
        try {
            const response = hiddenUploadFrame.contentDocument.body.innerText;
            if (response) {
                const json = JSON.parse(response);
                const fileName = json.fileName;
                document.getElementById("loader").style.visibility = "hidden";
                modal.style.display = "none";
                addToTranscript("RGD", `Uploaded file: ${fileName} (${json.fileSize} bytes) to OpenAI vector store`);
                uploadForm.reset();
            }
        } catch (e) {
            console.error('Error processing upload response:', e);
        }
    });
    // Load Clinical Trials button
    const loadTrialsBtn = document.getElementById("loadTrials");
    if (loadTrialsBtn) {
        loadTrialsBtn.addEventListener('click', () => {
                loadTrialsBtn.disabled = true;
                loadTrialsBtn.textContent = "Loading Trials...";

                addToTranscript("RGD", "Starting clinical trials loading process...");

                fetch(contextPath + "/load-clinical-trials", {
                    method: "POST",
                    headers: {
                        "Content-Type": "application/json"
                    }
                })
                    .then(res => res.json())
                    .then(data => {
                        if (data.error) {
                            addToTranscript("RGD", `Error: ${data.error}`);
                        } else {
                            const message = `Clinical trials loading complete!\n` +
                                `Total: ${data.total}\n` +
                                `Processed: ${data.processed}\n` +
                                `Overwritten: ${data.overwritten}\n` +
                                `Failed: ${data.failed}`;

                            addToTranscript("RGD", message);

                            if (data.failedList && data.failedList.length > 0) {
                                addToTranscript("RGD", `Failed trials: ${data.failedList.join(', ')}`);
                            }
                        }
                    })
                    .catch(error => {
                        console.error('Error loading clinical trials:', error);
                        addToTranscript("RGD", "Error loading clinical trials: " + error.message);
                    })
                    .finally(() => {
                        loadTrialsBtn.disabled = false;
                        loadTrialsBtn.textContent = "Load Clinical Trials";
                    });
        });
    }

    // Welcome message
    addToTranscript("RGD", "Welcome to RGD AI Assistant!");

    const startOverBtn = document.getElementById('startOverBtn');
    startOverBtn.addEventListener('click',startOverChat);
};

// Disclaimer toggle
const initDisclaimer = () => {
    const disclaimer = document.getElementById('disclaimer');
    const disclaimerHeader = document.getElementById('disclaimerHeader');

    disclaimerHeader.addEventListener('click', () => {
        disclaimer.classList.toggle('collapsed');
    });
};

// Initialize everything when DOM is loaded
window.addEventListener('load', () => {
    initUIEvents();
    initDisclaimer();
});