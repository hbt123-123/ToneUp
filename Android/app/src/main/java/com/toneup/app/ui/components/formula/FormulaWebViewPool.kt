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
            // H-33：以池内实际规模为准决定是否继续创建——_prewarmed 标志滞后于
            // all.size，重复 prewarm 会在标志置位前各建一实例导致超限
            if (synchronized(lock) { all.size >= POOL_SIZE }) {
                _prewarmed.value = true
                return@post
            }
            // 分帧逐个创建：每帧仅构造一个 WebView，避免单帧持锁连续构造阻塞主线程（§7.2 冷启动）
            val created = create()
            synchronized(lock) {
                all.add(created)
                idle.addLast(created)
                if (all.size >= POOL_SIZE) _prewarmed.value = true
            }
            if (!_prewarmed.value) prewarm()
        }
    }

    /** 主线程调用；池空时临时新建（用毕归还，超限销毁） */
    fun acquire(): PooledWebView {
        check(Looper.myLooper() == Looper.getMainLooper()) { "acquire must be on main thread" }
        synchronized(lock) {
            idle.pollFirst()?.let { return it }
        }
        Log.d(TAG, "pool empty, creating ad-hoc instance")
        // 构造移出锁外：WebView 构造期间不阻塞 release/prewarm 的主线程任务
        val created = create()
        synchronized(lock) { all.add(created) }
        return created
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

        fun jsString(raw: String): String {
            val encoded = json.encodeToString(raw)
            // H-34：U+2028/U+2029 是 JSON 合法字符但属 JS 行终止符，
            // 直接内插进 evaluateJavascript 会截断语句，必须显式转义
            return encoded.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        }
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
        // 挂起与直渲染统一挂 800ms 守卫：模板加载异常（onPageFinished 不回调）时也能走降级链路
        renderTimeoutPending = true
        mainHandler.removeCallbacks(timeoutRunnable)
        mainHandler.postDelayed(timeoutRunnable, FormulaWebViewPool.RENDER_TIMEOUT_MS)
        if (!ready) {
            pending = html to dark
            return
        }
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
            // H-35：失败后丢弃挂起渲染，避免模板 onPageFinished 就绪后重新渲染
            // 与已上报的失败态错位
            pending = null
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
