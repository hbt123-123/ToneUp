package com.toneup.app.data.repository

import com.toneup.app.data.local.CatalogCachePayload
import com.toneup.app.data.local.CatalogCacheStore
import com.toneup.app.data.remote.api.CatalogApi
import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.BankDetailDto
import com.toneup.app.data.remote.dto.CatalogDto
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * EC-05 catalog 持久缓存 JVM 单测（任务 14 QA）：
 * 缓存命中→先出缓存→刷新覆盖；缓存 JSON 损坏 → null 走正常网络路径不崩溃。
 */
class CatalogRepositoryCacheTest {

    private class FakeCatalogApi : CatalogApi {
        var catalogCalls = 0
        var envelope: ApiEnvelope<CatalogDto> = ApiEnvelope(success = true, data = CatalogDto())

        override suspend fun catalog(): ApiEnvelope<CatalogDto> {
            catalogCalls++
            return envelope
        }

        override suspend fun bankDetail(bankId: String): ApiEnvelope<BankDetailDto> {
            throw UnsupportedOperationException("not used here")
        }
    }

    private class FakeCacheStore : CatalogCacheStore {
        var payload: CatalogCachePayload? = null
        var writeCalls = 0
        var cleared = false

        override suspend fun read(): CatalogCachePayload? = payload
        override suspend fun write(payload: CatalogCachePayload) {
            writeCalls++
            this.payload = payload
        }
        override suspend fun clear() {
            cleared = true
            payload = null
        }
    }

    private lateinit var api: FakeCatalogApi
    private lateinit var store: FakeCacheStore
    private lateinit var repo: CatalogRepository

    @Before
    fun setUp() {
        CatalogCache.reset()
        api = FakeCatalogApi()
        store = FakeCacheStore()
        repo = CatalogRepository(api, JsonProvider(Json), store)
    }

    private val cachedDto = CatalogDto()
    private val freshDto = CatalogDto()

    @Test
    fun `cache hit serves disk payload without network`() = runTest {
        store.payload = CatalogCachePayload(cachedAtMillis = System.currentTimeMillis(), catalog = cachedDto)
        val result = repo.catalog()
        assertEquals(cachedDto, result)
        assertEquals(0, api.catalogCalls)
    }

    @Test
    fun `network refresh overwrites cache store`() = runTest {
        api.envelope = ApiEnvelope(success = true, data = freshDto)
        val result = repo.catalog(forceRefresh = true)
        assertEquals(freshDto, result)
        assertEquals(1, api.catalogCalls)
        assertEquals(1, store.writeCalls)
        assertEquals(freshDto, store.payload?.catalog)
        assertNotNull(store.payload?.cachedAtMillis)
    }

    @Test
    fun `disk hydrate then force refresh goes to network`() = runTest {
        store.payload = CatalogCachePayload(cachedAtMillis = System.currentTimeMillis(), catalog = cachedDto)
        api.envelope = ApiEnvelope(success = true, data = freshDto)
        val first = repo.catalog()
        assertEquals(cachedDto, first)
        assertEquals(0, api.catalogCalls)
        val second = repo.catalog(forceRefresh = true)
        assertEquals(freshDto, second)
        assertEquals(1, api.catalogCalls)
    }

    @Test
    fun `missing cache falls through to network`() = runTest {
        api.envelope = ApiEnvelope(success = true, data = freshDto)
        val result = repo.catalog()
        assertEquals(freshDto, result)
        assertEquals(1, api.catalogCalls)
    }

    // ---------- 损坏缓存安全语义（CatalogCachePayload 纯函数） ----------

    @Test
    fun `corrupt json parses to null`() {
        assertNull(CatalogCachePayload.parseOrNull("not a json"))
        assertNull(CatalogCachePayload.parseOrNull("{\"cachedAtMillis\": \"oops\"}"))
    }

    @Test
    fun `payload json round-trips`() {
        val p = CatalogCachePayload(cachedAtMillis = 123L, catalog = cachedDto)
        val back = CatalogCachePayload.parseOrNull(CatalogCachePayload.encode(p))
        assertNotNull(back)
        assertEquals(123L, back!!.cachedAtMillis)
        assertEquals(cachedDto, back.catalog)
    }
}
