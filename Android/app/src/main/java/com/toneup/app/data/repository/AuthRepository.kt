package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.local.CatalogCacheStore
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.local.SessionUser
import com.toneup.app.data.remote.api.AuthApi
import com.toneup.app.data.remote.dto.LoginRequest
import com.toneup.app.data.remote.dto.RegisterRequest
import com.toneup.app.data.remote.dto.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val authApi: AuthApi,
    private val jsonProvider: JsonProvider,
    private val sessionManager: SessionManager,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val attemptResultCache: AttemptResultCache,
    private val catalogCacheStore: CatalogCacheStore
) {
    // M-59：login/logout/restoreSession 均变更多处共享单例状态（令牌、用户、缓存），
    // 用 Mutex 串行化，避免重叠调用交错导致串号/缓存残留
    private val sessionMutex = Mutex()

    suspend fun login(username: String, password: String): UserDto = sessionMutex.withLock {
        val token = EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            authApi.login(LoginRequest(username, password))
        }.accessToken
        // 先落库新令牌再调 me()：否则全新安装时 me() 无 Authorization 必然 401，
        // 残留旧令牌时 me() 会返回上一账号信息导致串号
        val previousToken = sessionManager.cachedToken()
        sessionManager.stageToken(token)
        val user = try {
            me()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // H-19：瞬时失败（网络/5xx）不应把整会话清掉——恢复旧令牌保留会话；
            // 只有新令牌被后端拒绝（401）或本来就没有旧会话时才清
            if (previousToken != null && e !is AppException.Unauthorized) {
                sessionManager.stageToken(previousToken)
            } else {
                sessionManager.clearSession()
            }
            throw e
        }
        sessionManager.onLogin(token, SessionUser(user.id, user.username, user.role))
        // M-60：与 logout 对称清理账号级缓存，防止 401 后直接换号登录时读到上一账号的题目/答题结果缓存
        attemptResultCache.clear()
        CatalogCache.reset()
        runCatching { catalogCacheStore.clear() }
        user
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
    suspend fun restoreSession(): UserDto? = sessionMutex.withLock {
        if (sessionManager.cachedToken() == null) return@withLock null
        try {
            me()
        } catch (e: AppException.Unauthorized) {
            sessionManager.clearSession()
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // M-61：与 login()/H-19 口径对齐——瞬时失败（网络/5xx）保留令牌与本地会话，
            // 仅返回 null 走"未校验"路径，等待下次启动/重试再校验，避免网络抖动误登出
            Log.w(TAG, "session restore skipped: ${e.message}")
            null
        }
    }

    /** 退出登录：先删该用户草稿/队列文件，再清令牌与内存/磁盘缓存（EC-05 双清） */
    suspend fun logout() = sessionMutex.withLock {
        // M-62：wipeUser 失败不得中断登出流程（否则令牌残留，用户实际仍处于登录态）
        try {
            sessionManager.currentUserId()?.let { sessionDataStoreManager.wipeUser(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "wipeUser failed during logout: ${e.message}")
        }
        CatalogCache.reset()
        runCatching { catalogCacheStore.clear() }
        attemptResultCache.clear()
        sessionManager.clearSession()
    }

    private companion object {
        const val TAG = "AuthRepository"
    }
}
