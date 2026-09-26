package com.toneup.app.data.local

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.toneup.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import javax.inject.Singleton

/** 网络可用性监听：断网续答后自动重放依赖此信号 */
@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    // M-10：shareIn 需要应用级协程 scope 承载共享热流
    @ApplicationScope scope: CoroutineScope
) {
    fun isOnline(): Boolean {
        // M-11：getSystemService 泛型重载免强转，系统服务缺失时按离线处理
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        // H-5：VALIDATED 表示已通过系统连通性探测，排除强制门户 Wi-Fi 的假在线
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // M-10：callbackFlow 是冷流，每个 collector 会各自注册 NetworkCallback 并独立查询；
    // 用 shareIn 提升为共享热流，回调仅注册一份，无 collector 5 秒后自动注销
    // M-11：replay=1 让新 collector 立即拿到最近在线状态；distinctUntilChanged 后置，
    // 过滤上游重启时 replay 值与新快照相同造成的重复发射
    val onlineFlow: Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        if (cm == null) {
            // M-11：系统服务缺失（理论上不发生）时降级为一次性离线快照，不让流崩溃
            trySend(false)
            awaitClose { }
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(isOnline())
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // H-6：网络保持连接但验证状态变化（如失去 VALIDATED）也要更新，防止流值过期
                trySend(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
            }
        }
        try {
            // M-11：注册可能抛出（回调过多/权限缺失），降级为一次性在线快照而非让流崩溃
            cm.registerDefaultNetworkCallback(callback)
        } catch (e: Exception) {
            trySend(isOnline())
            awaitClose { }
            return@callbackFlow
        }
        trySend(isOnline())
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000L), replay = 1)
        .distinctUntilChanged()
}
