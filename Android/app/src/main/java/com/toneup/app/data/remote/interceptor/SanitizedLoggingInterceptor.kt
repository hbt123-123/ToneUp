package com.toneup.app.data.remote.interceptor

import android.util.Base64
import com.toneup.app.BuildConfig
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Inject
import javax.inject.Singleton

/** Debug 构建启用，Authorization 头脱敏 */
@Singleton
class SanitizedLoggingInterceptor @Inject constructor() {

    fun create(): HttpLoggingInterceptor? {
        if (!BuildConfig.ENABLE_NETWORK_LOG) return null
        return HttpLoggingInterceptor { message ->
            val safe = Regex("Authorization: Bearer [^,\\s]+").replace(message) { match ->
                val raw = match.value
                val suffix = raw.takeLast(6)
                "Authorization: Bearer ***${suffix}(len=${Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP).length})"
            }
            android.util.Log.d("OkHttp", safe)
        }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
    }
}
