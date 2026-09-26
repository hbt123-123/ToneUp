package com.toneup.app.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.toneup.app.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

object SessionDataSerializer : Serializer<SessionData> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val defaultValue: SessionData = SessionData()

    override suspend fun readFrom(input: InputStream): SessionData {
        // H-7：readBytes 的 IO 失败按 IOException 原样传播——只有解析失败才是"损坏"，
        // 误把瞬时 IO 错误标成 CorruptionException 会让 DataStore 走错误的恢复路径
        val bytes = input.readBytes()
        return try {
            json.decodeFromString(SessionData.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw CorruptionException("cannot parse session data", e)
        }
    }

    override suspend fun writeTo(t: SessionData, output: OutputStream) {
        output.write(json.encodeToString(SessionData.serializer(), t).encodeToByteArray())
    }
}

/**
 * 按 user_id 命名空间隔离的 Proto 风格 DataStore：
 * 每个用户独立文件 practice_<userId>.bin，退出登录时整文件删除。
 */
@Singleton
class SessionDataStoreManager @Inject constructor(
    // M-75：显式限定 Application Context（原依赖 NetworkModule 的冗余无限定 Context 绑定，已随 M-75 删除）
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) {
    // TODO(M-17)：全局 Mutex 会让不同 userId 的 store 获取互相串行。改为锁外快路径会
    // 重新引入与 wipeUser 的竞态（H-8 修复目标），同 userId 并发 create 又必须全局互斥
    // （否则多 DataStore 实例同文件抛 IllegalStateException）；单用户场景竞争概率极低，
    // 暂维持现状，后续如确有需要可引入按 userId 分段锁 + 引用计数。

    private val mutex = Mutex()
    private val stores = ConcurrentHashMap<Long, DataStore<SessionData>>()

    suspend fun storeFor(userId: Long): DataStore<SessionData> {
        // H-8：统一走互斥锁，避免锁外快路径读到即将被 wipeUser 移除/删文件的旧实例
        return mutex.withLock {
            stores.getOrPut(userId) {
                DataStoreFactory.create(
                    serializer = SessionDataSerializer,
                    scope = scope,
                    produceFile = { File(context.filesDir, "practice_$userId.bin") }
                )
            }
        }
    }

    /** 退出登录 / 切换账号：销毁内存引用并删除该用户命名空间文件 */
    suspend fun wipeUser(userId: Long) {
        val file = File(context.filesDir, "practice_$userId.bin")
        mutex.withLock {
            val store = stores.remove(userId)
            // 锁内排空旧实例在途写入后再删文件，防止并发 storeFor 为同一文件重建实例；
            // H-9：updateData 走一次完整读-写事务，强制把 pending 写落盘（data.first() 只读快照不保证 flush）
            if (store != null) runCatching { store.updateData { it } }
            // M-18：去掉 exists() 前置判断（TOCTOU 竞态且多余），直接删除并核对结果，
            // 文件存在但删除失败（被占用/IO 错误）时留痕，避免静默残留旧用户数据
            val deleted = file.delete()
            if (!deleted && file.exists()) {
                Log.w(TAG, "failed to delete session data file: ${file.name}")
            }
        }
    }

    private companion object {
        const val TAG = "SessionDataStoreManager"
    }
}
