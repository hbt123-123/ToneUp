package com.toneup.app.domain.logic

import com.toneup.app.domain.model.AnswerValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 答案 <-> JSON 编解码（POST /api/attempts 的 answer 字段）。
 * 服务端客观题判分口径（后端契约 §7）：
 * - SINGLE/READING: "A"
 * - CLOZE: ["B","D",...] 按空序
 * - ORDERING: ["3","1","2",...] 顺序数组
 * 统一外层 {"value": ..., "type": typeCode}，便于主观题文本与扩展字段共存。
 */
object AnswerCodec {
    private val json = Json { encodeDefaults = true }

    private val choiceTypes = setOf("SINGLE", "JUDGE", "READING")

    fun encode(answer: AnswerValue, typeCode: String): JsonObject = buildJsonObject {
        when (answer) {
            is AnswerValue.Choice -> put("value", answer.label)
            is AnswerValue.MultiChoice ->
                put("value", JsonArray(answer.labels.map { JsonPrimitive(it) }))
            is AnswerValue.Text -> put("value", answer.text)
            is AnswerValue.Blanks -> put(
                "value",
                buildJsonObject { answer.values.forEach { (k, v) -> put(k.toString(), v) } }
            )
            is AnswerValue.BlankLabels -> put(
                "value",
                buildJsonArray {
                    val maxIndex = answer.values.keys.maxOrNull() ?: -1
                    for (i in 0..maxIndex) add(JsonPrimitive(answer.values[i] ?: ""))
                }
            )
            is AnswerValue.Order ->
                put("value", JsonArray(answer.ids.map { JsonPrimitive(it) }))
        }
        put("type", typeCode)
    }

    /** 解析服务端或草稿中的答案；未知形状返回 null */
    fun decode(obj: JsonObject?): AnswerValue? {
        obj ?: return null
        val value = obj["value"] ?: return null
        val typeHint = (obj["type"] as? JsonPrimitive)?.content
        return when (value) {
            is JsonObject -> AnswerValue.Blanks(
                value.entries.mapNotNull { (k, v) ->
                    k.toIntOrNull()?.let { it to v.jsonPrimitive.content }
                }.toMap()
            )
            is JsonArray -> when {
                typeHint == "ORDERING" -> AnswerValue.Order(value.map { it.jsonPrimitive.content })
                typeHint == "CLOZE" -> AnswerValue.BlankLabels(
                    value.withIndex().associate { it.index to it.value.jsonPrimitive.content }
                )
                else -> AnswerValue.MultiChoice(value.map { it.jsonPrimitive.content })
            }
            is JsonPrimitive -> if (typeHint != null && typeHint in choiceTypes) {
                AnswerValue.Choice(value.content)
            } else {
                AnswerValue.Text(value.content)
            }
            else -> null
        }
    }

    fun decodeInt(obj: JsonObject): Int? =
        (obj["value"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }

    fun decodeText(obj: JsonObject): String =
        (obj["value"] as? JsonPrimitive)?.content ?: ""
}
