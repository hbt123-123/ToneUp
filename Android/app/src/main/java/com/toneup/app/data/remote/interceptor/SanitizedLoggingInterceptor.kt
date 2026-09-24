package com.toneup.app.data.remote.interceptor

import com.toneup.app.BuildConfig
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Inject
import javax.inject.Singleton

/** Debug 构建启用，Authorization 头完全脱敏 */
@Singleton
class SanitizedLoggingInterceptor @Inject constructor() {

    fun create(): HttpLoggingInterceptor? {
        if (!BuildConfig.ENABLE_NETWORK_LOG) return null
        return HttpLoggingInterceptor { message ->
            val safe = Regex("Authorization: Bearer [^,\\s]+").replace(message) { match ->
                // 不回显 token 的任何真实字符；len 度量 token 本身而非整行
                val tokenLen = match.value.substringAfter("Bearer ").trim().length
                "Authorization: Bearer *** (len=$tokenLen)"
            }
            android.util.Log.d("OkHttp", safe)
        }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
    }
}
