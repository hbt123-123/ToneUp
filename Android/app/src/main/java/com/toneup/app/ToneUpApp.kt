package com.toneup.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.toneup.app.data.local.SessionManager
import com.toneup.app.ui.components.formula.FormulaWebViewPool
import com.toneup.app.ui.components.formula.FormulaWebViewPoolHolder
import com.toneup.app.ui.components.question.RendererRegistry
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class ToneUpApp : Application(), ImageLoaderFactory {

    @Inject lateinit var formulaWebViewPool: FormulaWebViewPool

    // M-107：复用 Hilt @Singleton OkHttpClient（已含 AuthInterceptor/日志/超时配置），
    // Coil 不再自建独立客户端，避免连接池与拦截器双份开销
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        FormulaWebViewPoolHolder.init(formulaWebViewPool)
        formulaWebViewPool.prewarm()
        validateRendererRegistry()
    }

    /** Coil：题目图片懒加载 + 占位 + 200MB LRU 磁盘缓存，带鉴权头 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // M-107：直接传入注入的单例客户端（原 lambda 内新建 OkHttpClient）
            .okHttpClient(okHttpClient)
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private fun validateRendererRegistry() {
        // M-108：注册表与 QuestionType 均为编译期静态结构，校验失败必属开发期遗漏，
        // 静默放行会让对应题型永远走 FallbackRenderer，故保留 Log.e 并启动即 fail-fast
        val problems = RendererRegistry.validateIntegrity()
        if (problems.isNotEmpty()) {
            problems.forEach { Log.e("RendererRegistry", it) }
            throw IllegalStateException(
                "RendererRegistry 完整性校验失败：${problems.joinToString("; ")}"
            )
        }
    }

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 200L * 1024 * 1024 // LRU 上限 200MB（§12）
    }
}
