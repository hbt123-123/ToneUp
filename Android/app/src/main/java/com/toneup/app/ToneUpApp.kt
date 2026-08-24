package com.toneup.app

import android.app.Application
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.interceptor.AuthInterceptor
import com.toneup.app.ui.components.formula.FormulaWebViewPool
import com.toneup.app.ui.components.formula.FormulaWebViewPoolHolder
import com.toneup.app.ui.components.question.RendererRegistry
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class ToneUpApp : Application(), ImageLoaderFactory {

    @Inject lateinit var formulaWebViewPool: FormulaWebViewPool

    @Inject lateinit var authInterceptor: AuthInterceptor

    override fun onCreate() {
        super.onCreate()
        FormulaWebViewPoolHolder.init(formulaWebViewPool)
        formulaWebViewPool.prewarm()
        validateRendererRegistry()
    }

    /** Coil：题目图片懒加载 + 占位 + 200MB LRU 磁盘缓存，带鉴权头 */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor(authInterceptor)
                    .build()
            }
            .diskCache {
                coil.disk.DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()

    private fun validateRendererRegistry() {
        val problems = RendererRegistry.validateIntegrity()
        for (problem in problems) {
            Log.e("RendererRegistry", problem)
        }
    }

    private companion object {
        const val IMAGE_DISK_CACHE_BYTES = 200L * 1024 * 1024 // LRU 上限 200MB（§12）
    }
}
