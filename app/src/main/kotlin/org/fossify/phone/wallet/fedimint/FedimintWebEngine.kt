package org.fossify.phone.wallet.fedimint

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the Fedimint Web SDK inside an internal WebView, with a very small JS<->Kotlin bridge.
 *
 * The wallet UI remains native; the WebView is used as an execution engine only.
 */
object FedimintWebEngine {
    private const val TAG = "FedimintWebEngine"
    // Versioned query forces WebView to load the latest bundled engine script after updates.
    private const val ENGINE_URL = "https://appassets.androidplatform.net/assets/fedimint/engine.html?v=20260217d"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, (String) -> Unit>()

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var isReady: Boolean = false

    @Volatile
    private var readyLatch: CountDownLatch? = null

    @Volatile
    private var lastError: Throwable? = null

    @Volatile
    private var initProbeStarted: Boolean = false

    private fun createAssetLoader(context: Context): WebViewAssetLoader {
        return WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
            .build()
    }

    private fun ensureWebView(context: Context) {
        val existing = webView
        if (existing != null) {
            if (!isReady && !initProbeStarted) {
                startReadyProbe(existing)
            }
            return
        }

        val appContext = context.applicationContext
        val assetLoader = createAssetLoader(appContext)

        val wv = WebView(appContext)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            // The engine page is loaded over a local HTTPS origin via WebViewAssetLoader.
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                val msg = consoleMessage.message().orEmpty()
                if (msg.contains("error", ignoreCase = true) || msg.contains("uncaught", ignoreCase = true)) {
                    lastError = RuntimeException("JS: $msg")
                }
                return super.onConsoleMessage(consoleMessage)
            }
        }

        wv.addJavascriptInterface(Bridge(), "CyberPhoneFedimint")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                // Serve local engine assets from appassets. All other traffic (Fedimint federation networking)
                // must pass through normally, otherwise wallet sync/join will fail.
                return assetLoader.shouldInterceptRequest(url)
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Engine page finished loading: $url")
                startReadyProbe(view)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                super.onReceivedError(view, request, error)
                if (request.isForMainFrame) {
                    val description = error.description?.toString().orEmpty().ifBlank { "unknown error" }
                    lastError = RuntimeException("Engine load error: $description")
                    readyLatch?.countDown()
                }
            }
        }

        webView = wv
        isReady = false
        readyLatch = CountDownLatch(1)
        initProbeStarted = false
        lastError = null

        // Avoid stale appassets content across app updates/process reuse.
        runCatching { wv.clearCache(true) }
        wv.loadUrl(ENGINE_URL)
        // Launch readiness probing immediately; don't rely only on onPageFinished callbacks.
        startReadyProbe(wv)
    }

    private fun startReadyProbe(wv: WebView) {
        if (initProbeStarted) return
        initProbeStarted = true

        val startedAt = System.currentTimeMillis()
        val timeoutMs = 15_000L

        fun step() {
            if (isReady) return
            if (webView !== wv) return

            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > timeoutMs) {
                lastError = RuntimeException(
                    "Fedimint engine bootstrap missing. This WebView may not support required JS module features."
                )
                readyLatch?.countDown()
                return
            }

            try {
                wv.evaluateJavascript(
                    "(function(){ return !!(globalThis.__cyber_fedimint && typeof globalThis.__cyber_fedimint.request === 'function'); })();"
                ) { value ->
                    val v = value?.trim().orEmpty().lowercase()
                    val ready = v == "true" || v == "\"true\""
                    if (ready) {
                        isReady = true
                        readyLatch?.countDown()
                    } else {
                        mainHandler.postDelayed({ step() }, 200L)
                    }
                }
            } catch (t: Throwable) {
                lastError = t
                mainHandler.postDelayed({ step() }, 300L)
            }
        }

        step()
    }

    private fun resetEngine(reason: String? = null) {
        val done = CountDownLatch(1)
        runOnMain {
            if (!reason.isNullOrBlank()) {
                Log.w(TAG, "Resetting Fedimint engine: $reason")
            }
            try {
                webView?.stopLoading()
            } catch (_: Throwable) {
            }
            try {
                webView?.destroy()
            } catch (_: Throwable) {
            }
            webView = null
            isReady = false
            readyLatch = null
            initProbeStarted = false
            done.countDown()
        }
        try {
            done.await(5, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = e
        }
    }

    private fun probeJsReady(timeoutMs: Long): Boolean {
        val latch = CountDownLatch(1)
        var ready = false

        runOnMain {
            val wv = webView
            if (wv == null) {
                latch.countDown()
                return@runOnMain
            }

            try {
                wv.evaluateJavascript(
                    "(function(){ return !!(globalThis.__cyber_fedimint && typeof globalThis.__cyber_fedimint.request === 'function'); })();"
                ) { value ->
                    val v = value?.trim().orEmpty().lowercase()
                    ready = v == "true" || v == "\"true\""
                    if (ready) {
                        isReady = true
                        readyLatch?.countDown()
                    }
                    latch.countDown()
                }
            } catch (t: Throwable) {
                lastError = t
                latch.countDown()
            }
        }

        try {
            latch.await(timeoutMs.coerceAtLeast(500L), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            lastError = e
            return false
        }
        return ready
    }

    private fun waitUntilReady(timeoutMs: Long): Boolean {
        if (isReady) return true

        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1000L)
        while (System.currentTimeMillis() < deadline) {
            if (isReady) return true

            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val waitSlice = minOf(1_200L, remaining)
            val latchReady = readyLatch?.await(waitSlice, TimeUnit.MILLISECONDS) == true
            if (latchReady && isReady) return true

            if (probeJsReady(timeoutMs = minOf(1_200L, remaining))) return true

            if (isReady) return true
            try {
                Thread.sleep(120L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastError = e
                return false
            }
        }

        if (lastError == null) {
            lastError = RuntimeException(
                "Fedimint JS bridge missing: __cyber_fedimint was not initialized. " +
                    "This WebView likely lacks required JS module/worker features."
            )
        }
        return false
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    fun getLastErrorMessage(): String? = lastError?.message

    /**
     * Calls a method on the JS engine and returns its JSON response string.
     *
     * Must be called off the UI thread.
     */
    fun callBlocking(context: Context, method: String, params: JSONObject, timeoutMs: Long = 60_000L): String? {
        if (Looper.getMainLooper().thread === Thread.currentThread()) {
            throw IllegalStateException("FedimintWebEngine.callBlocking must not run on the UI thread")
        }

        for (attempt in 0..1) {
            val response = callOnce(context, method, params, timeoutMs)
            if (response != null) {
                return response
            }
            if (attempt == 0) {
                val reason = lastError?.message.orEmpty().ifBlank { "unknown startup error" }
                resetEngine("call $method failed before response: $reason")
            }
        }
        return null
    }

    private fun callOnce(context: Context, method: String, params: JSONObject, timeoutMs: Long): String? {
        val id = nextId.getAndIncrement()
        val latch = CountDownLatch(1)
        var response: String? = null

        pending[id] = { payload ->
            response = payload
            latch.countDown()
        }

        val request = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params)
        }

        // 1) Ensure the engine page is loaded.
        runOnMain {
            try {
                ensureWebView(context)
                // Fallback: if a WebView already existed in a pre-ready state, make sure probing runs.
                val wv = webView
                if (wv != null && !isReady && !initProbeStarted) {
                    startReadyProbe(wv)
                }
            } catch (t: Throwable) {
                lastError = t
                pending.remove(id)
                latch.countDown()
            }
        }

        // 2) Wait until the JS runtime has registered the request handler.
        val readyOk = waitUntilReady(timeoutMs)
        if (!readyOk) {
            val reason = lastError?.message.orEmpty().ifBlank {
                val hasWebView = webView != null
                "Fedimint engine not ready (timeout) calling $method [webView=$hasWebView,isReady=$isReady,probeStarted=$initProbeStarted]"
            }
            val t = RuntimeException(reason)
            lastError = t
            pending.remove(id)
            return null
        }

        // 3) Dispatch the request into the JS engine.
        runOnMain {
            try {
                val wv = webView ?: throw IllegalStateException("WebView not initialized")
                wv.evaluateJavascript(
                    "globalThis.__cyber_fedimint && globalThis.__cyber_fedimint.request(${JSONObject.quote(request.toString())});",
                    null
                )
            } catch (t: Throwable) {
                lastError = t
                pending.remove(id)
                latch.countDown()
            }
        }

        val ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        pending.remove(id)

        if (!ok) {
            val t = RuntimeException("Fedimint engine timeout calling $method")
            lastError = t
            return null
        }

        return response
    }

    private class Bridge {
        @JavascriptInterface
        fun onReady() {
            isReady = true
            readyLatch?.countDown()
            Log.d(TAG, "Fedimint engine ready")
        }

        @JavascriptInterface
        fun onResponse(id: Int, payload: String) {
            pending.remove(id)?.invoke(payload)
        }

        @JavascriptInterface
        fun onError(message: String?) {
            val msg = message?.trim().orEmpty().ifBlank { "Fedimint engine initialization failed" }
            lastError = RuntimeException(msg)
            readyLatch?.countDown()
            Log.e(TAG, "Fedimint engine JS error: $msg")
        }
    }
}
