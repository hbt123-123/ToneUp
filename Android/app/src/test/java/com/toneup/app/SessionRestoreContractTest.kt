package com.toneup.app

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.toneup.app.data.local.LastPracticeContext
import com.toneup.app.data.local.SessionData

/**
 * 任务 9 QA：lastIndex 保存/恢复往返断言 + 旧版本数据向后兼容。
 *
 * SessionDataStoreManager 的序列化配置为
 * `Json { ignoreUnknownKeys = true; encodeDefaults = true }`，此处对齐。
 */
class SessionRestoreContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `lastContext roundtrip preserves questionIndex and serverSessionId`() {
        val ctx = LastPracticeContext(
            userId = 7L,
            bankId = "gwy_xingce",
            sessionId = "srv_42",
            questionIndex = 13,
            title = "行测 2024",
            year = 2024,
            typeCode = "danxuan",
            serverSessionId = 42L,
            updatedAtMillis = 1_760_000_000_000
        )
        val encoded = json.encodeToString(LastPracticeContext.serializer(), ctx)
        val decoded = json.decodeFromString(LastPracticeContext.serializer(), encoded)
        assertEquals(ctx, decoded)
        assertEquals(13, decoded.questionIndex)
        assertEquals(42L, decoded.serverSessionId)
    }

    @Test
    fun `legacy payload without new fields decodes with defaults`() {
        // 旧版本 SessionData：无 sessionSubmitRequestId；旧 LastPracticeContext：无 serverSessionId
        val legacy = """
            {
              "drafts": [],
              "pendingSubmissions": [],
              "lastContext": {
                "userId": 7,
                "bankId": "gwy_xingce",
                "sessionId": "sec_abc",
                "questionIndex": 5,
                "updatedAtMillis": 1760000000000
              },
              "markedKeys": ["gwy_xingce:101"]
            }
        """.trimIndent()
        val data = json.decodeFromString(SessionData.serializer(), legacy)
        assertNull(data.sessionSubmitRequestId)
        assertEquals(5, data.lastContext?.questionIndex)
        assertNull(data.lastContext?.serverSessionId)
        assertEquals(listOf("gwy_xingce:101"), data.markedKeys)
    }

    @Test
    fun `sessionData roundtrip preserves submit request id`() {
        val data = SessionData(
            sessionSubmitRequestId = "req-uuid-1"
        )
        val encoded = json.encodeToString(SessionData.serializer(), data)
        val decoded = json.decodeFromString(SessionData.serializer(), encoded)
        assertEquals("req-uuid-1", decoded.sessionSubmitRequestId)
    }

    @Test
    fun `new fields are nullable so local sessions keep null serverSessionId`() {
        val ctx = LastPracticeContext(
            userId = 1L,
            bankId = "b",
            sessionId = "sec_x",
            questionIndex = 0,
            updatedAtMillis = 1L
        )
        val encoded = json.encodeToString(LastPracticeContext.serializer(), ctx)
        assertTrue(!encoded.contains("serverSessionId\":") || encoded.contains("serverSessionId\":null"))
        val decoded = json.decodeFromString(LastPracticeContext.serializer(), encoded)
        assertNull(decoded.serverSessionId)
    }
}
