package com.toneup.app.data.local

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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

    // M-19：强制登出信号必须可靠——replay=1 保证事件在订阅前发射也能送达消费方
    // （如冷启动早期 401），extraBufferCapacity 提升到 4 为并发多条 401 提供缓冲；
    // 消费方语义是"跳转登录页"（launchSingleTop 幂等），重复消费无副作用
    private val _unauthorizedEvents =
        MutableSharedFlow<UnauthorizedEvent>(replay = 1, extraBufferCapacity = 4)
    val unauthorizedEvents: SharedFlow<UnauthorizedEvent> = _unauthorizedEvents

    /** 触发 401 时正在访问的路由，用于登录后恢复原位 */
    @Volatile
    var pendingRestoreRoute: String? = null
        private set

    // M-20：路由的写入与一次性取走共用此锁，消除读-改-写与 401 写入的交错竞态
    private val restoreRouteLock = Any()

    // H-10：updateAndGet 原子完成 miss 时落盘镜像回填，消除"读后写"与 clearSession 的交错竞态
    // （update{} 返回 Unit，updateAndGet 返回 CAS 后的新值）
    fun cachedToken(): String? =
        _token.updateAndGet { current -> current ?: tokenStore.token() }

    fun onLogin(token: String, user: SessionUser) {
        stageToken(token)
        _user.value = user
        // H-11：不在此清 pendingRestoreRoute——一次性消费由 consumeRestoreRoute() 负责，
        // 401 恢复流程依赖它存活到登录页取走；常规登录时该值本来就是 null
    }

    /** 登录流程暂存令牌：持久化并更新内存镜像（拦截器立即可见），用户信息待 /me 成功后经 [onLogin] 写入 */
    fun stageToken(token: String) {
        tokenStore.save(token)
        _token.value = token
    }

    fun restoreCachedUser(user: SessionUser) {
        if (_user.value == null) _user.value = user
    }

    fun currentUserId(): Long? = _user.value?.userId

    /** 收到 401：清会话并发出事件；[currentRoute] 供登录后恢复 */
    suspend fun onUnauthorized(currentRoute: String?) {
        clearSession()
        synchronized(restoreRouteLock) {
            // M-20：写入纳入与 consumeRestoreRoute 相同的锁
            pendingRestoreRoute = currentRoute
        }
        _unauthorizedEvents.emit(UnauthorizedEvent(restoreRoute = currentRoute))
    }

    /** 登录成功后取走恢复路由（一次性，读-清原子） */
    fun consumeRestoreRoute(): String? = synchronized(restoreRouteLock) {
        val route = pendingRestoreRoute
        pendingRestoreRoute = null
        route
    }

    fun clearSession() {
        // M-21：先清内存镜像再清落盘令牌——即使 tokenStore.clear() 抛异常，
        // 内存中的可用凭据也已被清除，不会残留
        _token.value = null
        _user.value = null
        try {
            tokenStore.clear()
        } catch (e: Exception) {
            // M-21：磁盘清除失败留痕（内存已失效，进程内无凭据残留风险）
            Log.w(TAG, "clear persisted token failed", e)
        }
    }

    data class UnauthorizedEvent(val restoreRoute: String?)

    private companion object {
        const val TAG = "SessionManager"
    }
}
