package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.local.CatalogCachePayload
import com.toneup.app.data.local.CatalogCacheStore
import com.toneup.app.data.remote.api.CatalogApi
import com.toneup.app.data.remote.dto.BankDetailDto
import com.toneup.app.data.remote.dto.CatalogDto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** catalog 当日内存缓存；题库详情按 bank_id 缓存 */
object CatalogCache {
    @Volatile var catalog: CatalogDto? = null
    @Volatile private var cachedAtMillis: Long = 0
    private val bankDetails = ConcurrentHashMap<String, BankDetailDto>()

    const val TTL_MILLIS = 24 * 60 * 60 * 1000L

    fun catalogIfFresh(): CatalogDto? =
        catalog?.takeIf { System.currentTimeMillis() - cachedAtMillis < TTL_MILLIS }

    /** cachedAtMillis 可指定：从磁盘水合时恢复原时间戳，避免 fresh 判定失真 */
    fun putCatalog(dto: CatalogDto, cachedAtMillis: Long = System.currentTimeMillis()) {
        catalog = dto
        this.cachedAtMillis = cachedAtMillis
    }

    fun bankDetail(bankId: String): BankDetailDto? = bankDetails[bankId]
    fun putBankDetail(dto: BankDetailDto) {
        bankDetails[dto.id] = dto
    }

    fun reset() {
        catalog = null
        cachedAtMillis = 0
        bankDetails.clear()
        Log.d("CatalogCache", "reset")
    }
}

@Singleton
class CatalogRepository @Inject constructor(
    private val catalogApi: CatalogApi,
    private val jsonProvider: JsonProvider,
    private val cacheStore: CatalogCacheStore
) {
    suspend fun catalog(forceRefresh: Boolean = false): CatalogDto {
        if (!forceRefresh) {
            CatalogCache.catalogIfFresh()?.let { return it }
            CatalogCache.catalog?.let { return it } // 过期但可先展示，后台再刷
            // EC-05 持久缓存：内存 miss 时先发磁盘缓存（cache-first 水合）
            cacheStore.read()?.let { payload ->
                payload.catalog?.let { dto ->
                    CatalogCache.putCatalog(dto, payload.cachedAtMillis)
                    return dto
                }
            }
        }
        val dto = EnvelopeUnwrapper.unwrap(jsonProvider.json) { catalogApi.catalog() }
        CatalogCache.putCatalog(dto)
        runCatching { cacheStore.write(CatalogCachePayload(cachedAtMillis = System.currentTimeMillis(), catalog = dto)) }
        return dto
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
