package com.toneup.app.ui.components.formula

import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.toneup.app.domain.logic.MarkdownSanitizer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 公式混排文本（需求 §7.2 主案）：
 * 输入原始文本 → Markdown 白名单净化 → 池化 WebView 渲染 KaTeX。
 * 降级链路（§7.6）：渲染失败或超时退化为原文；连续失败 ≥3 提示反馈。
 */
@Composable
fun FormulaText(
    text: String,
    modifier: Modifier = Modifier,
    forceRawText: Boolean = false,
    onRenderEvent: ((FormulaRenderEvent) -> Unit)? = null
) {
    var heightPx by remember(text) { mutableIntStateOf(-1) }
    var failed by remember(text) { mutableStateOf(false) }
    var pooledRef by remember { mutableStateOf<PooledWebView?>(null) }
    val consecutiveFailures = remember { AtomicInteger(0) }

    // 仅以生效配色判定深浅色：用户强制浅色而系统深色时，WebView 不应按深色渲染
    val darkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val density = LocalDensity.current

    val preparedHtml = remember(text) {
        MarkdownSanitizer.toParagraphs(MarkdownSanitizer.sanitize(text))
    }
    var lastRendered by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    if (forceRawText || failed) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, modifier = modifier)
        if (consecutiveFailures.get() >= FAILURE_THRESHOLD) {
            Text(
                text = "公式多次渲染失败，如持续出现请在“我的”中反馈该题内容",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        return
    }

    Box(modifier.fillMaxWidth()) {
        AndroidView(
            factory = { ctx ->
                val pool = FormulaWebViewPoolHolder.require()
                val pooled = pool.acquire()
                pooledRef = pooled
                pooled.heightListener = { h ->
                    if (h > 0) {
                        consecutiveFailures.set(0)
                        heightPx = h
                    }
                }
                pooled.successListener = {
                    onRenderEvent?.invoke(FormulaRenderEvent.Success)
                }
                pooled.failureListener = {
                    consecutiveFailures.incrementAndGet()
                    failed = true
                    onRenderEvent?.invoke(FormulaRenderEvent.Failure)
                }
                (pooled.webView.parent as? ViewGroup)?.removeView(pooled.webView)
                pooled.webView.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                pooled.webView
            },
            update = { webView ->
                val pooled = pooledRef ?: return@AndroidView
                if (pooled.webView === webView) {
                    // 高度回调/父级重组反复触发 update：未变则跳过，避免 render→onHeight→重组→render 循环
                    if (lastRendered?.first == preparedHtml && lastRendered?.second == darkTheme) {
                        return@AndroidView
                    }
                    lastRendered = preparedHtml to darkTheme
                    pooled.setDark(darkTheme)
                    pooled.render(preparedHtml, darkTheme)
                }
            },
            onRelease = {
                pooledRef?.let { pooled ->
                    FormulaWebViewPoolHolder.getOrNull()?.release(pooled)
                }
                pooledRef = null
                lastRendered = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (heightPx > 0) {
                        Modifier.height(with(density) { heightPx.toDp() })
                    } else {
                        Modifier.heightIn(min = 32.dp)
                    }
                )
        )
    }
}

sealed class FormulaRenderEvent {
    data object Success : FormulaRenderEvent()
    data object Failure : FormulaRenderEvent()
}

private const val FAILURE_THRESHOLD = 3

/** 应用启动时由 ToneUpApp 初始化 */
object FormulaWebViewPoolHolder {
    private var pool: FormulaWebViewPool? = null

    fun init(instance: FormulaWebViewPool) {
        pool = instance
    }

    fun getOrNull(): FormulaWebViewPool? = pool

    fun require(): FormulaWebViewPool =
        pool ?: throw IllegalStateException("FormulaWebViewPool not initialised")
}
