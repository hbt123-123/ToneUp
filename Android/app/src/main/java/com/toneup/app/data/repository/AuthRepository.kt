package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.local.SessionUser
import com.toneup.app.data.remote.api.AuthApi
import com.toneup.app.data.remote.dto.LoginRequest
import com.toneup.app.data.remote.dto.RegisterRequest
import com.toneup.app.data.remote.dto.UserDto
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val jsonProvider: JsonProvider,
    private val sessionManager: SessionManager,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val attemptResultCache: AttemptResultCache
) {
    suspend fun login(username: String, password: String): UserDto {
        val token = EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            authApi.login(LoginRequest(username, password))
        }.accessToken
        val user = me()
        sessionManager.onLogin(token, SessionUser(user.id, user.username, user.role))
        return user
    }

    suspend fun register(username: String, password: String): UserDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            authApi.register(RegisterRequest(username, password))
        }

    suspend fun me(): UserDto {
        val dto = EnvelopeUnwrapper.unwrap(jsonProvider.json) { authApi.me() }
        sessionManager.restoreCachedUser(SessionUser(dto.id, dto.username, dto.role))
        return dto
    }

    /** 校验本地令牌是否仍有效；无效时清会话 */
    suspend fun restoreSession(): UserDto? {
        if (sessionManager.cachedToken() == null) return null
        return try {
            me()
        } catch (e: AppException.Unauthorized) {
            sessionManager.clearSession()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "session restore skipped: ${e.message}")
            null
        }
    }

    /** 退出登录：先删该用户草稿/队列文件，再清令牌与内存缓存 */
    suspend fun logout() {
        sessionManager.currentUserId()?.let { sessionDataStoreManager.wipeUser(it) }
        CatalogCache.reset()
        attemptResultCache.clear()
        sessionManager.clearSession()
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
