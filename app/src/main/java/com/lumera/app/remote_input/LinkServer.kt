package com.lumera.app.remote_input

import android.net.Uri
import android.os.Handler
import android.os.Looper
import fi.iki.elonen.NanoHTTPD
import java.util.UUID

/**
 * A lightweight HTTP server that serves a mobile-friendly form
 * and receives the pasted URL from the user's phone.
 */
class LinkServer(
    port: Int,
    private val pairingToken: String,
    private val onLinkReceived: (String) -> Unit
) : NanoHTTPD(port) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val csrfToken = UUID.randomUUID().toString()

    override fun serve(session: IHTTPSession): Response {
        if (session.uri == "/ping") return DisconnectBanner.pingResponse()
        // Require the pairing token from the QR/link URL on every request — proves
        // the caller actually saw the URL shown on this TV, unlike the CSRF token
        // below (which is only good against forged submissions once you have the page).
        if (session.parms["pin"] != pairingToken) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }
        return when (session.method) {
            Method.GET -> serveForm()
            Method.POST -> handleSubmission(session)
            else -> newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "Method not allowed")
        }
    }

    private fun serveForm(): Response {
        val html = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Remote Paste</title>
                <style>
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                        background-color: #121212;
                        color: #ffffff;
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        padding: 20px;
                    }
                    .container {
                        background-color: #1e1e1e;
                        border-radius: 16px;
                        padding: 32px 24px;
                        width: 100%;
                        max-width: 400px;
                        box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                        text-align: center;
                    }
                    h1 {
                        color: #fff;
                        font-size: 1.5rem;
                        font-weight: 600;
                        margin-bottom: 0.5rem;
                        text-align: center;
                    }
                    p {
                        color: #aaaaaa;
                        font-size: 14px;
                        text-align: center;
                        margin-bottom: 24px;
                    }
                    input {
                        width: 100%;
                        padding: 16px;
                        font-size: 16px;
                        border: 2px solid #333;
                        border-radius: 12px;
                        background: rgba(0, 0, 0, 0.3);
                        color: #fff;
                        outline: none;
                        transition: border-color 0.2s;
                        margin-bottom: 16px;
                    }
                    input:focus {
                        border-color: #555;
                    }
                    input::placeholder {
                        color: rgba(255, 255, 255, 0.4);
                    }
                    button {
                        width: 100%;
                        padding: 14px 24px;
                        font-size: 1rem;
                        font-weight: 600;
                        border: none;
                        border-radius: 24px;
                        background-color: #ffffff;
                        color: #000000;
                        cursor: pointer;
                        transition: transform 0.1s, opacity 0.2s;
                    }
                    button:active {
                        transform: scale(0.98);
                    }
                    button:disabled {
                        opacity: 0.6;
                        cursor: not-allowed;
                    }
                    .success {
                        text-align: center;
                        color: #10b981;
                        font-size: 18px;
                        padding: 40px 0;
                    }
                    .success svg {
                        width: 64px;
                        height: 64px;
                        margin-bottom: 16px;
                    }
                </style>
            </head>
            <body>
                <div class="container" id="form-container">
                    <h1>📋 Remote Paste</h1>
                    <p>Paste your addon URL below and tap Send</p>
                    <form id="pasteForm">
                        <input type="hidden" name="csrf_token" value="$csrfToken">
                        <input type="url" name="url" id="urlInput"
                               placeholder="https://..."
                               autocomplete="off"
                               autocapitalize="off"
                               required>
                        <button type="submit" id="submitBtn">Send to TV</button>
                    </form>
                </div>
                <div class="container success" id="success-container" style="display: none;">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"/>
                        <polyline points="22 4 12 14.01 9 11.01"/>
                    </svg>
                    <div>URL sent successfully!</div>
                    <p style="margin-top: 12px;">You can close this page now.</p>
                </div>
                <script>
                    document.getElementById('pasteForm').addEventListener('submit', async (e) => {
                        e.preventDefault();
                        const btn = document.getElementById('submitBtn');
                        const url = document.getElementById('urlInput').value;
                        btn.disabled = true;
                        btn.textContent = 'Sending...';
                        try {
                            const formData = new FormData(document.getElementById('pasteForm'));
                            const res = await fetch('/' + window.location.search, { method: 'POST', body: formData });
                            if (!res.ok) throw new Error('Server rejected the request');
                            document.getElementById('form-container').style.display = 'none';
                            document.getElementById('success-container').style.display = 'block';
                        } catch (err) {
                            btn.disabled = false;
                            btn.textContent = 'Send to TV';
                            alert('Failed to send. Please try again.');
                        }
                    });
                </script>
                ${DisconnectBanner.htmlSnippet}
            </body>
            </html>
        """.trimIndent()

        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    private fun handleSubmission(session: IHTTPSession): Response {
        try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)

            // Validate CSRF token
            val token = session.parms["csrf_token"]
            if (token != csrfToken) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "Invalid request")
            }

            val url = session.parms["url"]

            if (!url.isNullOrBlank()) {
                // Validate URL scheme
                val scheme = Uri.parse(url).scheme?.lowercase()
                if (scheme != "http" && scheme != "https") {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Only HTTP/HTTPS URLs are supported")
                }
                mainHandler.post {
                    onLinkReceived(url)
                }
            }

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")
        } catch (e: Exception) {
            if (com.lumera.app.BuildConfig.DEBUG) android.util.Log.w("LinkServer", "Error handling submission", e)
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error processing request")
        }
    }
}
