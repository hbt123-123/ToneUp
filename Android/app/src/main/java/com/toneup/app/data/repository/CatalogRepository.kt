package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.local.CatalogCachePayload
import com.toneup.app.data.local.CatalogCacheStore
import com.toneup.app.data.remote.api.CatalogApi
import com.toneup.app.data.remote.dto.BankDetailDto
import com.toneup.app.data.remote.dto.CatalogDto
import com.toneup.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** catalog 当日内存缓存；题库详情按 bank_id 缓存 */
object CatalogCache {
    // H-20：目录与时间戳合成单一 volatile 引用，消除两步写导致的撕裂读
    private data class CatalogEntry(val catalog: CatalogDto, val cachedAtMillis: Long)

    @Volatile private var entry: CatalogEntry? = null
    private val bankDetails = ConcurrentHashMap<String, BankDetailDto>()

    const val TTL_MILLIS = 24 * 60 * 60 * 1000L

    fun catalogIfFresh(): CatalogDto? =
        entry?.takeIf { System.currentTimeMillis() - it.cachedAtMillis < TTL_MILLIS }?.catalog

    /** 不做 fresh 判定的裸读（stale-while-revalidate 场景） */
    fun catalogStale(): CatalogDto? = entry?.catalog

    /** cachedAtMillis 可指定：从磁盘水合时恢复原时间戳，避免 fresh 判定失真 */
    fun putCatalog(dto: CatalogDto, cachedAtMillis: Long = System.currentTimeMillis()) {
        entry = CatalogEntry(dto, cachedAtMillis)
    }

    fun bankDetail(bankId: String): BankDetailDto? = bankDetails[bankId]
    fun putBankDetail(dto: BankDetailDto) {
        bankDetails[dto.id] = dto
    }

    fun reset() {
        entry = null
        bankDetails.clear()
        Log.d("CatalogCache", "reset")
    }
}

@Singleton
class CatalogRepository @Inject constructor(
    private val catalogApi: CatalogApi,
    private val jsonProvider: JsonProvider,
    private val cacheStore: CatalogCacheStore,
    @ApplicationScope private val appScope: CoroutineScope
) {
    /** 后台刷新任务（去重：同一时刻至多一个在途刷新） */
    private var refreshJob: Job? = null

    suspend fun catalog(forceRefresh: Boolean = false): CatalogDto {
        if (!forceRefresh) {
            CatalogCache.catalogIfFresh()?.let { return it }
            CatalogCache.catalogStale()?.let { stale ->
                // 内存过期：先展示，同时后台刷新
                refreshInBackground()
                return stale
            }
            // EC-05 持久缓存：内存 miss 时先发磁盘缓存（cache-first 水合）
            cacheStore.read()?.let { payload ->
                payload.catalog?.let { dto ->
                    CatalogCache.putCatalog(dto, payload.cachedAtMillis)
                    // 磁盘缓存过期：先渲染，同时后台刷新
                    // （否则新增题库/题目在用户手动下拉刷新前永远不可见）
                    if (System.currentTimeMillis() - payload.cachedAtMillis >=
                        CatalogCache.TTL_MILLIS
                    ) {
                        refreshInBackground()
                    }
                    return dto
                }
            }
        }
        return fetchAndCache()
    }

    /** 拉取并写入内存/磁盘缓存 */
    private suspend fun fetchAndCache(): CatalogDto {
        val dto = EnvelopeUnwrapper.unwrap(jsonProvider.json) { catalogApi.catalog() }
        CatalogCache.putCatalog(dto)
        runCatching {
            cacheStore.write(
                CatalogCachePayload(cachedAtMillis = System.currentTimeMillis(), catalog = dto)
            )
        }
        return dto
    }

    /** 后台刷新（应用级 scope，不随调用方取消）；失败静默，下次调用仍走缓存 */
    // H-21：check-then-act 与 refreshJob 写入同步化，消除并发双刷
    @Synchronized
    private fun refreshInBackground() {
        if (refreshJob?.isActive == true) return
        refreshJob = appScope.launch { runCatching { fetchAndCache() } }
    }

    suspend fun bankDetail(bankId: String, forceRefresh: Boolean = false): BankDetailDto {
        if (!forceRefresh) {
            CatalogCache.bankDetail(bankId)?.let { return it }
        }
        val dto = EnvelopeUnwrapper.unwrap(jsonProvider.json) { catalogApi.bankDetail(bankId) }
        CatalogCache.putBankDetail(dto)
        return dto
    }
}
