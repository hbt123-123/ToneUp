package com.toneup.app

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.CreateSessionRequest
import com.toneup.app.data.remote.dto.CreateSessionResponseDto
import com.toneup.app.data.remote.dto.DraftRequest
import com.toneup.app.data.remote.dto.DraftResponseDto
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.SessionDetailDto
import com.toneup.app.data.remote.dto.SessionDto
import com.toneup.app.data.remote.dto.SessionListItemDto
import com.toneup.app.data.remote.dto.SessionProgressDto
import com.toneup.app.data.remote.dto.SessionResultDto
import com.toneup.app.data.remote.dto.SessionSummaryDto
import com.toneup.app.data.remote.dto.SubmitSessionRequest
import com.toneup.app.data.remote.dto.SubmitSessionResponseDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EC-01 练习会话七端点契约驱动的反序列化测试（不依赖 Android 运行时，
 * 纯 JVM 单测；本机不跑 Gradle，仅作为代码书写 + 字段对齐核查的载体）。
 *
 * JSON 样例取自 `backend/app/api/practice_sessions.py` 七端点返回的
 * `envelope()` / `page()` 字段形状，逐字段对照确保蛇形键名与可空性
 * 与后端契约一一对应——这是 kotlinx-serialization 在测试期暴露字段
 * 名偏差的唯一手段（QA failure 场景：DTO 字段名与契约不一致将在此红）。
 */
class SessionApiContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        encodeDefaults = true
    }

    // ── POST /api/practice-sessions（create）───────────────────────────────────

    @Test
    fun `create response parses session_id bank_id title total_count questions`() {
        val payload = """
            {
              "success": true,
              "data": {
                "session_id": 42,
                "bank_id": "geo-2024",
                "title": "地理题库 2026-09-23",
                "total_count": 20,
                "questions": [
                  {
                    "bank_id": "geo-2024",
                    "question_id": 101,
                    "collection_id": 7,
                    "year": 2024,
                    "type_code": "SINGLE",
                    "number": 1,
                    "content": "下列属于内生力的是？",
                    "display_order": 1
                  }
                ]
              },
              "message": "",
              "request_id": "req-1"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<CreateSessionResponseDto>>(payload)
        assertTrue(env.success)
        val data = env.data!!
        assertEquals(42L, data.sessionId)
        assertEquals("geo-2024", data.bankId)
        assertEquals("地理题库 2026-09-23", data.title)
        assertEquals(20, data.totalCount)
        assertEquals(1, data.questions.size)
        assertEquals(101L, data.questions[0].questionId)
        assertEquals("SINGLE", data.questions[0].typeCode)
        // include_answer=False 时后端省略 answer_text/solution，DTO 默认 null
        assertNull(data.questions[0].answerText)
        assertNull(data.questions[0].solution)
    }

    @Test
    fun `create request serializes snake_case fields and defaults count to 20`() {
        val req = CreateSessionRequest(bankId = "geo-2024")
        val encoded = json.encodeToString(CreateSessionRequest.serializer(), req)
        assertTrue(encoded.contains("\"bank_id\":\"geo-2024\""))
        assertTrue(encoded.contains("\"count\":20"))
        assertFalse(encoded.contains("collection_ids"))
        assertFalse(encoded.contains("type_codes"))
    }

    @Test
    fun `create request with optional filters serializes ids and type_codes`() {
        val req = CreateSessionRequest(
            bankId = "his-2023",
            collectionIds = listOf(1L, 2L, 3L),
            typeCodes = listOf("SINGLE", "JUDGE"),
            count = 50
        )
        val encoded = json.encodeToString(CreateSessionRequest.serializer(), req)
        assertTrue(encoded.contains("\"collection_ids\":[1,2,3]"))
        assertTrue(encoded.contains("\"type_codes\":[\"SINGLE\",\"JUDGE\"]"))
        assertTrue(encoded.contains("\"count\":50"))
    }

    // ── GET /api/practice-sessions（list）──────────────────────────────────────

    @Test
    fun `list response parses page items total has_more with answered field`() {
        val payload = """
            {
              "success": true,
              "data": {
                "items": [
                  {
                    "id": 9,
                    "bank_id": "geo-2024",
                    "title": "地理题库 2026-09-22",
                    "status": "submitted",
                    "total_count": 20,
                    "answered": 20,
                    "created_at": "2026-09-22 10:00:00"
                  },
                  {
                    "id": 7,
                    "bank_id": "geo-2024",
                    "title": "地理题库 2026-09-20",
                    "status": "active",
                    "total_count": 15,
                    "answered": 3,
                    "created_at": "2026-09-20 09:30:00"
                  }
                ],
                "total": 2,
                "has_more": false
              },
              "message": "",
              "request_id": "req-2"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<PageData<SessionListItemDto>>>(payload)
        val data = env.data!!
        assertEquals(2, data.items.size)
        assertEquals(2, data.total)
        assertFalse(data.hasMore)
        val first = data.items[0]
        assertEquals(9L, first.id)
        assertEquals("submitted", first.status)
        assertEquals(20, first.answered)
        assertEquals("2026-09-22 10:00:00", first.createdAt)
        val second = data.items[1]
        assertEquals("active", second.status)
        assertEquals(3, second.answered)
    }

    // ── GET /api/practice-sessions/{sid}（detail）─────────────────────────────

    @Test
    fun `detail response parses session fields questions and progress`() {
        val payload = """
            {
              "success": true,
              "data": {
                "session": {
                  "id": 7,
                  "bank_id": "geo-2024",
                  "title": "地理题库 2026-09-20",
                  "status": "active",
                  "total_count": 15,
                  "current_index": 3,
                  "elapsed_seconds": 480,
                  "draft": {"answers": {"1": "A"}, "scroll": 200}
                },
                "questions": [
                  {
                    "bank_id": "geo-2024",
                    "question_id": 201,
                    "type_code": "JUDGE",
                    "content": "地球是圆的。",
                    "display_order": 1
                  }
                ],
                "progress": {"answered": 3, "total": 15}
              },
              "message": "",
              "request_id": "req-3"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<SessionDetailDto>>(payload)
        val data = env.data!!
        val s: SessionDto = data.session
        assertEquals(7L, s.id)
        assertEquals("geo-2024", s.bankId)
        assertEquals("active", s.status)
        assertEquals(15, s.totalCount)
        assertEquals(3, s.currentIndex)
        assertEquals(480, s.elapsedSeconds)
        val answers = s.draft["answers"] as kotlinx.serialization.json.JsonObject
        assertEquals("A", (answers["1"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(1, data.questions.size)
        assertEquals(201L, data.questions[0].questionId)
        val p: SessionProgressDto = data.progress
        assertEquals(3, p.answered)
        assertEquals(15, p.total)
    }

    @Test
    fun `detail session status constants align with backend literals`() {
        assertEquals("active", SessionDto.STATUS_ACTIVE)
        assertEquals("submitted", SessionDto.STATUS_SUBMITTED)
    }

    // ── PUT /api/practice-sessions/{sid}/draft（update draft）─────────────────

    @Test
    fun `draft request serializes current_index draft elapsed_seconds`() {
        val req = DraftRequest(
            currentIndex = 5,
            draft = kotlinx.serialization.json.JsonObject(
                mapOf(
                    "answers" to kotlinx.serialization.json.JsonObject(
                        mapOf("2" to kotlinx.serialization.json.JsonPrimitive("B"))
                    )
                )
            ),
            elapsedSeconds = 600
        )
        val encoded = json.encodeToString(DraftRequest.serializer(), req)
        assertTrue(encoded.contains("\"current_index\":5"))
        assertTrue(encoded.contains("\"elapsed_seconds\":600"))
        assertTrue(encoded.contains("\"draft\":"))
    }

    @Test
    fun `draft response parses current_index and updated_at`() {
        val payload = """
            {
              "success": true,
              "data": {"current_index": 5, "updated_at": "2026-09-23 10:30:00"},
              "message": "",
              "request_id": "req-4"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<DraftResponseDto>>(payload)
        assertEquals(5, env.data!!.currentIndex)
        assertEquals("2026-09-23 10:30:00", env.data.updatedAt)
    }

    // ── POST /api/practice-sessions/{sid}/submit（submit）─────────────────────

    @Test
    fun `submit request serializes client_request_id`() {
        val req = SubmitSessionRequest(clientRequestId = "abc-123-xyz")
        val encoded = json.encodeToString(SubmitSessionRequest.serializer(), req)
        assertTrue(encoded.contains("\"client_request_id\":\"abc-123-xyz\""))
    }

    @Test
    fun `submit response parses summary fields and replayed flag`() {
        val payload = """
            {
              "success": true,
              "data": {
                "summary": {
                  "total": 20,
                  "answered": 18,
                  "correct": 15,
                  "accuracy_rate": 0.8333,
                  "elapsed_seconds": 1200
                },
                "replayed": false
              },
              "message": "",
              "request_id": "req-5"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<SubmitSessionResponseDto>>(payload)
        val data = env.data!!
        val summary: SessionSummaryDto = data.summary
        assertEquals(20, summary.total)
        assertEquals(18, summary.answered)
        assertEquals(15, summary.correct)
        assertEquals(0.8333, summary.accuracyRate, 0.0001)
        assertEquals(1200, summary.elapsedSeconds)
        assertFalse(data.replayed)
    }

    @Test
    fun `submit replay response carries replayed true`() {
        val payload = """
            {
              "success": true,
              "data": {
                "summary": {
                  "total": 10,
                  "answered": 10,
                  "correct": 10,
                  "accuracy_rate": 1.0,
                  "elapsed_seconds": 300
                },
                "replayed": true
              },
              "message": "",
              "request_id": "req-6"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<SubmitSessionResponseDto>>(payload)
        assertTrue(env.data!!.replayed)
        assertEquals(1.0, env.data.summary.accuracyRate, 0.0)
    }

    // ── GET /api/practice-sessions/{sid}/result（result）──────────────────────

    @Test
    fun `result response parses items with nullable is_correct and summary`() {
        val payload = """
            {
              "success": true,
              "data": {
                "items": [
                  {"question_id": 201, "position": 0, "answered": true, "is_correct": true, "time_spent": 30},
                  {"question_id": 202, "position": 1, "answered": true, "is_correct": false, "time_spent": 45},
                  {"question_id": 203, "position": 2, "answered": false, "is_correct": null, "time_spent": 0}
                ],
                "summary": {
                  "total": 3,
                  "answered": 2,
                  "correct": 1,
                  "accuracy_rate": 0.5,
                  "elapsed_seconds": 75
                }
              },
              "message": "",
              "request_id": "req-7"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<SessionResultDto>>(payload)
        val data = env.data!!
        assertEquals(3, data.items.size)
        assertEquals(201L, data.items[0].questionId)
        assertEquals(0, data.items[0].position)
        assertTrue(data.items[0].answered)
        assertEquals(true, data.items[0].isCorrect)
        assertEquals(30, data.items[0].timeSpent)
        assertEquals(false, data.items[1].isCorrect)
        // 未答题：is_correct=null，time_spent=0
        assertFalse(data.items[2].answered)
        assertNull(data.items[2].isCorrect)
        assertEquals(0, data.items[2].timeSpent)
        val summary = data.summary
        assertEquals(3, summary.total)
        assertEquals(2, summary.answered)
        assertEquals(1, summary.correct)
        assertEquals(0.5, summary.accuracyRate, 0.0)
        assertEquals(75, summary.elapsedSeconds)
    }

    // ── DELETE /api/practice-sessions/{sid}（delete）──────────────────────────

    @Test
    fun `delete response parses success with null data and deleted message`() {
        // backend: envelope(message="deleted") → data 默认 null
        val payload = """
            {
              "success": true,
              "data": null,
              "message": "deleted",
              "request_id": "req-8"
            }
        """.trimIndent()
        val env = json.decodeFromString<ApiEnvelope<Unit>>(payload)
        assertTrue(env.success)
        assertNull(env.data)
        assertEquals("deleted", env.message)
    }
}
