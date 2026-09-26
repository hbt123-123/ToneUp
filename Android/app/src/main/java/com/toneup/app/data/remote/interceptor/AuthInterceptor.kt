package com.toneup.app.data.remote.interceptor

import com.toneup.app.data.local.SessionManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/** 自动附加 Bearer 令牌 */
@Singleton
class AuthInterceptor @Inject constructor(
    private val sessionManager: SessionManager
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = sessionManager.cachedToken()
        // M-51：空白/纯空格 token（如 SecureTokenStore 写坏的残留值）不当作有效凭据，
        // 避免发出 "Bearer  " 这类畸形鉴权头
        val authorized = if (!token.isNullOrBlank()) {
            request.newBuilder().header("Authorization", "Bearer $token").build()
        } else {
            request
        }
        return chain.proceed(authorized)
    }
}
