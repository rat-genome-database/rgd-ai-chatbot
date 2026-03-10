// // Utility Functions
// const addToTranscript = (who, text) => {
//     const transcript = document.querySelector('#transcript');
//     const name = (who === "User") ? username : who;
//     transcript.innerHTML += createTranscriptEntry(who, name, text);
//     transcript.scrollTop = transcript.scrollHeight;
// };
//
// const createTranscriptEntry = (who, name, text) => {
//     return `
//     <div class="${who}Entry">
//         <div><b>${name}:</b> ${text}</div>
//     </div>`;
// };
//
// const handleResponse = (response) => {
//     addToTranscript("AI", response.answer);
// };
//
// // API Interactions
// const postQuestion = (question) => {
//     fetch("/rgd-ai-chat-rag/chat", {
//         method: "POST",
//         headers: {
//             "Content-Type": "application/json"
//         },
//         body: JSON.stringify({ question: question })
//     })
//         .then(res => res.json())
//         .then(handleResponse)
//         .catch(error => {
//             console.error('Error:', error);
//             addToTranscript("AI", "Sorry, there was an error processing your request.");
//         });
// };
//
// // Event Handlers
// const submitTypedText = (event) => {
//     const typedTextInput = document.querySelector('#userInput');
//     const typedText = typedTextInput.value.trim();
//
//     if (typedText.length === 0) {
//         return false;
//     }
//
//     addToTranscript("User", typedText);
//     postQuestion(typedText);
//     typedTextInput.value = '';
//     return false;
// };
//
// // Initialize UI Events
// const initUIEvents = () => {
//     // Submit button click
//     const submitButton = document.querySelector('#typedTextSubmit');
//     submitButton.addEventListener('click', submitTypedText);
//
//     // Enter key in textarea
//     const textarea = document.querySelector('#userInput');
//     textarea.addEventListener('keydown', e => {
//         if (e.key === "Enter" && !e.shiftKey) {
//             e.preventDefault();
//             submitTypedText(e);
//         }
//     });
//
//     // File upload modal
//     const modal = document.getElementById("uploadModal");
//     const openModalBtn = document.getElementById("uploadFile");
//     const closeModalSpan = document.getElementsByClassName("closeModalSpan")[0];
//
//     openModalBtn.addEventListener('click', () => {
//         modal.style.display = "block";
//     });
//
//     closeModalSpan.addEventListener('click', () => {
//         modal.style.display = "none";
//     });
//
//     // Close modal when clicking outside
//     window.addEventListener('click', (event) => {
//         if (event.target === modal) {
//             modal.style.display = "none";
//         }
//     });
//
//     // File upload handling
//     const uploadForm = document.getElementById("uploadForm");
//     uploadForm.addEventListener('submit', () => {
//         const filename = uploadForm.elements[0].value;
//         if (filename && filename.length > 0) {
//             document.getElementById("loader").style.visibility = "visible";
//         }
//     });
//
//     const hiddenUploadFrame = document.getElementById("hiddenUploadFrame");
//     hiddenUploadFrame.addEventListener('load', () => {
//         try {
//             const response = hiddenUploadFrame.contentDocument.body.innerText;
//             if (response) {
//                 const json = JSON.parse(response);
//                 const fileName = json.fileName;
//                 document.getElementById("loader").style.visibility = "hidden";
//                 modal.style.display = "none";
//                 addToTranscript("File", `Uploaded file: ${fileName} (${json.fileSize} bytes)`);
//                 uploadForm.reset();
//             }
//         } catch (e) {
//             console.error('Error processing upload response:', e);
//         }
//     });
// };
//
// // Initialize everything when DOM is loaded
// window.addEventListener('load', initUIEvents);
// Utility Functions
const addToTranscript = (who, text) => {
    const transcript = document.querySelector('#transcript');
    const name = (who === "User") ? username : who;
    transcript.innerHTML += createTranscriptEntry(who, name, text);
    transcript.scrollTop = transcript.scrollHeight;
};

const createTranscriptEntry = (who, name, text) => {
    return `
    <div class="${who}Entry">
        <div><b>${name}:</b> ${text}</div>
    </div>`;
};

const handleResponse = (response) => {
    addToTranscript("AI", response.answer);
};

// API Interactions
const postQuestion = (question) => {
    fetch(contextPath + "/chat", {
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
            addToTranscript("AI", "Sorry, there was an error processing your request.");
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
            addToTranscript("System", `Processed URL: ${data.url} (${data.chunkCount} chunks extracted from "${data.title}")`);
        })
        .catch(error => {
            console.error('Error processing URL:', error);
            document.getElementById("urlLoader").style.visibility = "hidden";
            addToTranscript("System", `Error processing URL: ${error.message}`);
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
    postQuestion(typedText);
    typedTextInput.value = '';
    return false;
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

    openModalBtn.addEventListener('click', () => {
        modal.style.display = "block";
    });

    closeModalSpan.addEventListener('click', () => {
        modal.style.display = "none";
    });

    // URL modal
    const urlModal = document.getElementById("urlModal");
    const openUrlModalBtn = document.getElementById("processUrl");
    const closeUrlModalSpan = document.getElementsByClassName("closeUrlModalSpan")[0];

    openUrlModalBtn.addEventListener('click', () => {
        urlModal.style.display = "block";
    });

    closeUrlModalSpan.addEventListener('click', () => {
        urlModal.style.display = "none";
    });

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
                addToTranscript("System", `Uploaded file: ${fileName} (${json.fileSize} bytes)`);
                uploadForm.reset();
            }
        } catch (e) {
            console.error('Error processing upload response:', e);
        }
    });
};

// Initialize everything when DOM is loaded
window.addEventListener('load', initUIEvents);