package com.toneup.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.toneup.app.domain.logic.SessionDraftMerge

/**
 * EC-01 草稿合并纯函数单测：服务端优先、本地兜底（任务 9 QA）。
 */
class SessionDraftMergeTest {

    private val json = Json

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is kotlinx.serialization.json.JsonElement -> put(k, v)
                else -> put(k, v.toString())
            }
        }
    }

    // ---------- parseServerDraft ----------

    @Test
    fun `parse null server draft returns empty map`() {
        assertEquals(emptyMap<Long, JsonObject>(), SessionDraftMerge.parseServerDraft(null))
    }

    @Test
    fun `parse ignores non-numeric keys and non-object values`() {
        val raw = json.parseToJsonElement(
            """{"101": {"choice": "A"}, "not-a-number": {"choice": "B"}, "102": "scalar"}"""
        ) as JsonObject
        val parsed = SessionDraftMerge.parseServerDraft(raw)
        assertEquals(setOf(101L), parsed.keys)
        assertEquals("A", (parsed[101L]!!["choice"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    // ---------- resolve：服务端优先、本地兜底 ----------

    @Test
    fun `server draft wins over local`() {
        val server = obj("101" to obj("101" to "server-answer"))
        val local = listOf(101L to obj("101" to "local-answer"))
        val resolved = SessionDraftMerge.resolve(server, local, 101L)
        assertEquals("server-answer", (resolved!!["101"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `local used when server missing the question`() {
        val server = obj("999" to obj("999" to "other-question"))
        val local = listOf(101L to obj("101" to "local-answer"))
        val resolved = SessionDraftMerge.resolve(server, local, 101L)
        assertEquals("local-answer", (resolved!!["101"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `local fallback works with null server draft`() {
        val local = listOf(101L to obj("101" to "local-answer"))
        val resolved = SessionDraftMerge.resolve(null, local, 101L)
        assertEquals("local-answer", (resolved!!["101"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `resolve returns null when both missing`() {
        assertNull(SessionDraftMerge.resolve(null, emptyList(), 101L))
        assertNull(SessionDraftMerge.resolve(obj("999" to obj("999" to "x")), emptyList(), 101L))
    }

    // ---------- toServerDraft ----------

    @Test
    fun `toServerDraft encodes questionId keys`() {
        val draft = SessionDraftMerge.toServerDraft(
            listOf(101L to obj("choice" to "A"), 102L to obj("choice" to "B"))
        )
        assertEquals(setOf("101", "102"), draft.keys)
        val back = SessionDraftMerge.parseServerDraft(draft)
        assertEquals(setOf(101L, 102L), back.keys)
    }

    @Test
    fun `toServerDraft later entry overrides earlier for same question`() {
        val draft = SessionDraftMerge.toServerDraft(
            listOf(101L to obj("choice" to "A"), 101L to obj("choice" to "C"))
        )
        assertEquals("C", ((draft["101"] as kotlinx.serialization.json.JsonObject)["choice"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `empty entries produce empty draft`() {
        assertEquals(0, SessionDraftMerge.toServerDraft(emptyList()).size)
    }
}
