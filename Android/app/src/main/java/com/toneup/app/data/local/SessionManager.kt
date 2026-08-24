package com.toneup.app.data.local

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class SessionUser(
    val userId: Long,
    val username: String,
    val role: String
)

/**
 * 全局会话状态：令牌镜像（供拦截器无锁读取）、当前用户、401 失效事件。
 * 401 时保留当前路由供登录后恢复。
 */
@Singleton
class SessionManager @Inject constructor(
    private val tokenStore: SecureTokenStore
) {
    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    private val _user = MutableStateFlow<SessionUser?>(null)
    val user: StateFlow<SessionUser?> = _user

    private val _unauthorizedEvents = MutableSharedFlow<UnauthorizedEvent>(extraBufferCapacity = 1)
    val unauthorizedEvents: SharedFlow<UnauthorizedEvent> = _unauthorizedEvents

    /** 触发 401 时正在访问的路由，用于登录后恢复原位 */
    @Volatile
    var pendingRestoreRoute: String? = null
        private set

    fun cachedToken(): String? = _token.value ?: tokenStore.token().also { _token.value = it }

    fun onLogin(token: String, user: SessionUser) {
        tokenStore.save(token)
        _token.value = token
        _user.value = user
        pendingRestoreRoute = null
    }

    fun restoreCachedUser(user: SessionUser) {
        if (_user.value == null) _user.value = user
    }

    fun currentUserId(): Long? = _user.value?.userId

    /** 收到 401：清会话并发出事件；[currentRoute] 供登录后恢复 */
    suspend fun onUnauthorized(currentRoute: String?) {
        clearSession()
        pendingRestoreRoute = currentRoute
        _unauthorizedEvents.emit(UnauthorizedEvent(restoreRoute = currentRoute))
    }

    /** 登录成功后取走恢复路由（一次性） */
    fun consumeRestoreRoute(): String? {
        val route = pendingRestoreRoute
        pendingRestoreRoute = null
        return route
    }

    fun clearSession() {
        tokenStore.clear()
        _token.value = null
        _user.value = null
    }

    data class UnauthorizedEvent(val restoreRoute: String?)
}
