package com.toneup.app.ui.components.formula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * WebView 对象池（需求 §7.2）：默认 3 实例，启动预热加载本地模板页；
 * 切题取用/归还，严禁每次新建销毁。
 */
@Singleton
class FormulaWebViewPool @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val idle = ArrayDeque<PooledWebView>()
    private val all = mutableListOf<PooledWebView>()

    private val _prewarmed = MutableStateFlow(false)
    val prewarmed: StateFlow<Boolean> = _prewarmed

    fun prewarm() {
        mainHandler.post {
            synchronized(lock) {
                if (_prewarmed.value) return@post
                repeat(POOL_SIZE) { all.add(create()) }
                _prewarmed.value = true
            }
        }
    }

    /** 主线程调用；池空时临时新建（用毕归还，超限销毁） */
    fun acquire(): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "acquire must be on main thread" }
        synchronized(lock) {
            idle.pollFirst()?.let { return it }
            Log.d(TAG, "pool empty, creating ad-hoc instance")
            return create().also { all.add(it) }
        }
    }

    fun release(pooled: PooledWebView) {
        pooled.reset()
        mainHandler.post {
            synchronized(lock) {
                if (all.size > POOL_SIZE) {
                    all.remove(pooled)
                    destroy(pooled)
                } else {
                    idle.addLast(pooled)
                }
            }
        }
    }

    fun releaseAll() {
        mainHandler.post {
            synchronized(lock) {
                all.forEach(::destroy)
                idle.clear()
                all.clear()
                _prewarmed.value = false
            }
        }
    }

    private fun create(): PooledWebView = buildWebView()

    private fun destroy(pooled: PooledWebView) {
        pooled.webView.apply {
            loadUrl("about:blank")
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): PooledWebView {
        val webView = WebView(context)
        webView.settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.isVerticalScrollBarEnabled = false

        val pooled = PooledWebView(webView)
        webView.addJavascriptInterface(Bridge(pooled), BRIDGE_NAME)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                // 安全边界：仅本地资产，禁任意导航（§7.2）
                request.url.scheme != "file"

            override fun onPageFinished(view: WebView, url: String) {
                if (url == TEMPLATE_URL && !pooled.ready) {
                    pooled.ready = true
                    pooled.takePending()?.let { (html, dark) -> pooled.render(html, dark) }
                }
            }
        }
        webView.loadUrl(TEMPLATE_URL)
        return pooled
    }

    class Bridge(private val pooled: PooledWebView) {
        @JavascriptInterface
        fun onHeight(heightPx: Int) {
            Handler(Looper.getMainLooper()).post {
                pooled.heightListener?.invoke(heightPx)
            }
        }

        @JavascriptInterface
        fun onRendered() {
            Handler(Looper.getMainLooper()).post {
                pooled.onJsRendered()
            }
        }

        @JavascriptInterface
        fun onError(message: String) {
            Log.w(TAG, "formula render error: $message")
            Handler(Looper.getMainLooper()).post { pooled.fail() }
        }
    }

    companion object {
        const val POOL_SIZE = 3
        const val RENDER_TIMEOUT_MS = 800L
        internal const val TAG = "FormulaPool"
        internal const val BRIDGE_NAME = "AndroidBridge"
        internal const val TEMPLATE_URL = "file:///android_asset/katex/index.html"
        private val json = Json

        fun jsString(raw: String): String = json.encodeToString(raw)
    }
}

/**
 * 池化实例包装：就绪态、挂起渲染、高度/成功/失败回调与 800ms 超时守卫（§7.6-1）。
 */
class PooledWebView internal constructor(val webView: WebView) {

    private val mainHandler = Handler(Looper.getMainLooper())

    var ready: Boolean = false
    var failed: Boolean = false
        private set
    private var pending: Pair<String, Boolean>? = null
    private var renderTimeoutPending = false

    var heightListener: ((Int) -> Unit)? = null
    var successListener: (() -> Unit)? = null
    var failureListener: (() -> Unit)? = null

    private val timeoutRunnable = Runnable {
        if (renderTimeoutPending) fail()
    }

    fun takePending(): Pair<String, Boolean>? {
        val p = pending
        pending = null
        return p
    }

    fun render(html: String, dark: Boolean) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "render must run on main thread" }
        failed = false
        if (!ready) {
            pending = html to dark
            return
        }
        renderTimeoutPending = true
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, FormulaWebViewPool.RENDER_TIMEOUT_MS)
        webView.evaluateJavascript("renderContent(${FormulaWebViewPool.jsString(html)}, $dark)", null)
    }

    fun setDark(dark: Boolean) {
        if (ready) webView.evaluateJavascript("setDark($dark)", null)
    }

    fun onJsRendered() {
        renderTimeoutPending = false
        if (!failed) successListener?.invoke()
    }

    fun fail() {
        if (!failed) {
            renderTimeoutPending = false
            failed = true
            failureListener?.invoke()
        }
    }

    fun reset() {
        heightListener = null
        successListener = null
        failureListener = null
        pending = null
        failed = false
        renderTimeoutPending = false
        mainHandler.removeCallbacks(timeoutRunnable)
    }
}
