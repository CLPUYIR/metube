package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.net.URISyntaxException
import java.util.concurrent.atomic.AtomicInteger
import com.example.data.downloader.StreamCaptureRegistry
import com.example.data.downloader.StreamUrlResolver

/**
 * Production WebViewClient that intercepts ad requests, injects visibility
 * spoofing scripts, and injects DOM-hiding CSS / ad-skipping logic.
 * Handles deep links safely to prevent crashes.
 */
class YMusicWebViewClient(
    private val context: Context,
    private val onAdBlocked: (count: Int) -> Unit = {},
    private val onRendererCrashed: () -> Unit = {}
) : WebViewClient() {

    private val blockedCount = AtomicInteger(0)

    private val allowedDomains = listOf(
        "youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "accounts.google.com",
        "google.com",
        "gstatic.com",
        "googleusercontent.com",
        "googlevideo.com",
        "ytimg.com"
    )

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val uri = request?.url ?: return super.shouldInterceptRequest(view, request)

        try {
            if (AdBlockEngine.shouldBlock(uri)) {
                val total = blockedCount.incrementAndGet()
                onAdBlocked(total)
                // Returning a valid HTTP 403 Forbidden with non-empty body (non-zero bytes)
                // is essential: returning a 0-byte stream across Chromium's multi-process IPC
                // triggers [socket.cc(182): incorrect payload size 0], terminating the isolated
                // renderer process (code 11 / SIGSEGV).
                return WebResourceResponse(
                    "text/plain",
                    "UTF-8",
                    403,
                    "Forbidden",
                    mapOf(
                        "Cache-Control" to "no-store, no-cache, must-revalidate",
                        "Content-Type" to "text/plain; charset=utf-8"
                    ),
                    ByteArrayInputStream("/* blocked */\n".toByteArray(Charsets.UTF_8))
                )
            }

            val host = uri.host?.lowercase() ?: ""
            val path = uri.path?.lowercase() ?: ""
            if (host.contains("googlevideo.com") && path.contains("/videoplayback")) {
                val docId = uri.getQueryParameter("docid")
                StreamCaptureRegistry.registerStream(uri.toString(), docId)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return super.shouldInterceptRequest(view, request)
    }

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return false
        val urlString = uri.toString()
        val scheme = uri.scheme?.lowercase() ?: ""

        try {
            // Keep all YouTube and Google domain URLs inside our in-app WebView
            val host = uri.host?.lowercase() ?: ""
            if (allowedDomains.any { host == it || host.endsWith(".$it") }) {
                return false
            }

            // Handle custom Android intent: scheme safely without crashing
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(urlString, Intent.URI_INTENT_SCHEME)
                    val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                    if (!fallbackUrl.isNullOrEmpty()) {
                        view?.loadUrl(fallbackUrl)
                        return true
                    }
                } catch (e: URISyntaxException) {
                    e.printStackTrace()
                }
                // Suppress unknown intent scheme from crashing
                return true
            }

            // Suppress non-http custom schemes like vnd.youtube, market, etc.
            if (scheme != "http" && scheme != "https") {
                return true
            }

            // External non-YouTube link (e.g. external link in video description)
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Avoid executing JS in onPageStarted as DOM is not initialized yet and can destabilize V8
    }

    override fun onPageCommitVisible(view: WebView?, url: String?) {
        super.onPageCommitVisible(view, url)
        try {
            view?.evaluateJavascript(AdBlockEngine.BACKGROUND_PLAYBACK_JS, null)
            view?.evaluateJavascript(AdBlockEngine.getCssInjectionScript(), null)
            view?.evaluateJavascript(JsMediaBridge.BRIDGE_INJECTION_SCRIPT, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        url?.let {
            val vid = StreamUrlResolver.extractVideoId(it)
            if (!vid.isNullOrEmpty()) {
                StreamCaptureRegistry.setActiveVideo(vid)
            }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        url?.let {
            val vid = StreamUrlResolver.extractVideoId(it)
            if (!vid.isNullOrEmpty()) {
                StreamCaptureRegistry.setActiveVideo(vid)
            }
        }
        try {
            view?.evaluateJavascript(AdBlockEngine.BACKGROUND_PLAYBACK_JS, null)
            view?.evaluateJavascript(AdBlockEngine.getCssInjectionScript(), null)
            view?.evaluateJavascript(AdBlockEngine.ADBLOCK_JS, null)
            view?.evaluateJavascript(JsMediaBridge.BRIDGE_INJECTION_SCRIPT, null)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onRenderProcessGone(
        view: WebView?,
        detail: android.webkit.RenderProcessGoneDetail?
    ): Boolean {
        val didCrash = detail?.didCrash() ?: false
        android.util.Log.e("YMusic", "onRenderProcessGone detected (didCrash=$didCrash)")

        // Clean up the terminated WebView instance cleanly
        try {
            val parent = view?.parent as? android.view.ViewGroup
            parent?.removeView(view)
            view?.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            onRendererCrashed()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Return true to prevent host application termination if Chromium GPU / renderer crashes
        return true
    }
}
