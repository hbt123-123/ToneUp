package com.toneup.app.data.local

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import com.toneup.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

object SessionDataSerializer : Serializer<SessionData> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override val defaultValue: SessionData = SessionData()

    override suspend fun readFrom(input: InputStream): SessionData = try {
        json.decodeFromString(SessionData.serializer(), input.readBytes().decodeToString())
    } catch (e: Exception) {
        throw CorruptionException("cannot parse session data", e)
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
    private val context: Context,
    @ApplicationScope private val scope: CoroutineScope
) {
    private val mutex = Mutex()
    private val stores = mutableMapOf<Long, DataStore<SessionData>>()

    suspend fun storeFor(userId: Long): DataStore<SessionData> {
        stores[userId]?.let { return it }
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
        val store = mutex.withLock { stores.remove(userId) }
        if (store != null) runCatching { store.data.first() }
        val file = File(context.filesDir, "practice_$userId.bin")
        if (file.exists()) file.delete()
    }
}
