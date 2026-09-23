package com.toneup.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.toneup.app.data.remote.dto.CatalogDto
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
    private val context: Context
) : CatalogCacheStore {
    private val key = stringPreferencesKey("catalog_cache")

    override suspend fun read(): CatalogCachePayload? = try {
        val raw = context.catalogDataStore.data.first()[key] ?: return null
        CatalogCachePayload.parseOrNull(raw)
    } catch (e: Exception) {
        null
    }

    override suspend fun write(payload: CatalogCachePayload) {
        runCatching {
            context.catalogDataStore.edit { prefs ->
                prefs[key] = CatalogCachePayload.encode(payload)
            }
        }
    }

    override suspend fun clear() {
        runCatching {
            context.catalogDataStore.edit { it.remove(key) }
        }
    }
}
