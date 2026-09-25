package com.toneup.app.data.remote.interceptor

import com.toneup.app.BuildConfig
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Inject
import javax.inject.Singleton

/** Debug 构建启用，敏感请求头全部脱敏 */
@Singleton
class SanitizedLoggingInterceptor @Inject constructor() {

    fun create(): HttpLoggingInterceptor? {
        if (!BuildConfig.ENABLE_NETWORK_LOG) return null
        return HttpLoggingInterceptor { message ->
            // H-17：HEADERS 级别会打印全部头，仅遮 Bearer 会漏 Cookie/Set-Cookie/X-Api-Key 等；
            // (?im) 多行匹配逐头脱敏，值整体不回显任何真实字符
            val safe = Regex(
                "(?im)^(Authorization|Proxy-Authorization|Cookie|Set-Cookie|X-Api-Key):\\s*(.+)$"
            ).replace(message) { match ->
                val name = match.groupValues[1]
                val value = match.groupValues[2].trim()
                if (value.startsWith("Bearer ")) {
                    // len 度量 token 本身
                    "$name: Bearer *** (len=${value.removePrefix("Bearer ").trim().length})"
                } else {
                    "$name: *** (len=${value.length})"
                }
            }
            android.util.Log.d("OkHttp", safe)
        }.apply { level = HttpLoggingInterceptor.Level.HEADERS }
    }
}
