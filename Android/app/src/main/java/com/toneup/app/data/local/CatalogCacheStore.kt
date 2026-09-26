package com.toneup.app.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toneup.app.data.remote.dto.CatalogDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** EC-05 持久缓存载荷：时间戳随 DTO 一起落盘（水合后内存 fresh 判定不失真） */
@Serializable
data class CatalogCachePayload(
    val cachedAtMillis: Long = 0,
    val catalog: CatalogDto? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** 纯解析：缓存 JSON 损坏 → null（走正常网络路径，不崩溃） */
        fun parseOrNull(raw: String): CatalogCachePayload? = try {
            json.decodeFromString(serializer(), raw)
        } catch (e: Exception) {
            null
        }

        /** 纯编码：供持久化实现使用 */
        fun encode(payload: CatalogCachePayload): String =
            json.encodeToString(serializer(), payload)
    }
}

/** catalog 磁盘缓存抽象（open 仅为 JVM 单测 Fake 继承） */
interface CatalogCacheStore {
    suspend fun read(): CatalogCachePayload?
    suspend fun write(payload: CatalogCachePayload)
    suspend fun clear()
}

private val Context.catalogDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "toneup_catalog_cache"
)

/** Preferences DataStore 持久化实现：JSON 串入键 `catalog_cache`，登出随 reset 双清 */
@Singleton
class CatalogDataStoreManager @Inject constructor(
    // H-3：显式限定 Application Context，防止 Activity Context 被注入造成泄漏
    @ApplicationContext private val context: Context
) : CatalogCacheStore {
    private val key = stringPreferencesKey("catalog_cache")

    override suspend fun read(): CatalogCachePayload? = try {
        val raw = context.catalogDataStore.data.first()[key] ?: return null
        // M-7：缺时间戳（默认 0）或缺目录的载荷不视为有效缓存，
        // 防止默认/零时间戳载荷以 epoch 时间戳命中缓存
        CatalogCachePayload.parseOrNull(raw)
            ?.takeIf { it.cachedAtMillis > 0 && it.catalog != null }
    } catch (e: CancellationException) {
        // H-4：取消必须透传，吞掉会破坏结构化并发
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun write(payload: CatalogCachePayload) {
        try {
            context.catalogDataStore.edit { prefs ->
                prefs[key] = CatalogCachePayload.encode(payload)
            }
        } catch (e: CancellationException) {
            // M-8：取消必须透传（runCatching 会连 CancellationException 一起吞掉）
            throw e
        } catch (e: Exception) {
            // M-8：持久化失败不再静默——留痕日志；读侧有新鲜度校验兜底，下次进目录仍会走网络刷新
            Log.w(TAG, "persist catalog cache failed", e)
        }
    }

    override suspend fun clear() {
        try {
            context.catalogDataStore.edit { it.remove(key) }
        } catch (e: CancellationException) {
            // M-9：取消必须透传
            throw e
        } catch (e: Exception) {
            // M-9：登出清除失败必须留痕——失败意味着旧用户目录缓存可能残留给下一账号
            Log.w(TAG, "clear catalog cache failed on logout", e)
        }
    }

    private companion object {
        const val TAG = "CatalogCacheStore"
    }
}
