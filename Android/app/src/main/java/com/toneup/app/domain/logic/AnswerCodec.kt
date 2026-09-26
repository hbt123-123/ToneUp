package com.toneup.app.domain.logic

import com.toneup.app.domain.model.AnswerValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
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
            is AnswerValue.BlankLabels -> {
                val keys = answer.values.keys
                val maxIndex = keys.maxOrNull() ?: -1
                // M-76：仅当键恰为 0..max 连续时才输出数组格式；稀疏/负键/非零起始回退对象格式，
                // 避免缺口被静默补 "" 造成数组错位（异常形状显式暴露优于静默降级）
                if (keys.toSet() == (0..maxIndex).toSet()) {
                    put(
                        "value",
                        buildJsonArray {
                            for (i in 0..maxIndex) add(JsonPrimitive(answer.values.getValue(i)))
                        }
                    )
                } else {
                    put(
                        "value",
                        buildJsonObject { answer.values.forEach { (k, v) -> put(k.toString(), v) } }
                    )
                }
            }
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
                    // M-77：值必须是原语（嵌套对象/数组会使 jsonPrimitive 抛异常崩掉恢复流程），非法条目跳过
                    k.toIntOrNull()?.let { idx -> stringContentOrNull(v)?.let { c -> idx to c } }
                }.toMap()
            )
            is JsonArray -> when {
                typeHint == "ORDERING" ->
                    // M-78：JsonNull 也是 JsonPrimitive，content 会泄漏 "null" 字面量，统一安全提取
                    AnswerValue.Order(value.mapNotNull { stringContentOrNull(it) })
                typeHint == "CLOZE" -> AnswerValue.BlankLabels(
                    // M-78：非法元素以 "" 占位，保持空序 0..n-1 连续、不错位
                    value.withIndex().associate { (i, e) -> i to (stringContentOrNull(e) ?: "") }
                )
                else -> AnswerValue.MultiChoice(value.mapNotNull { stringContentOrNull(it) })
            }
            is JsonPrimitive -> when {
                // M-79：JsonNull 属于 JsonPrimitive 子类，{"value": null} 在此显式排除，避免 Text("null")
                value is JsonNull -> null
                typeHint != null && typeHint in choiceTypes -> AnswerValue.Choice(value.content)
                // M-80：type 缺失时按单字母标签（A-Z）推断为选择题，choice 答案不再被静默当作文本
                typeHint == null && value.content.length == 1 && value.content[0] in 'A'..'Z' ->
                    AnswerValue.Choice(value.content)
                else -> AnswerValue.Text(value.content)
            }
            else -> null
        }
    }

    /** M-77/78：安全取字符串原语内容；JsonNull（content 恒为 "null"）与嵌套结构返回 null */
    private fun stringContentOrNull(e: JsonElement): String? =
        (e as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    fun decodeInt(obj: JsonObject): Int? =
        (obj["value"] as? JsonPrimitive)?.let { runCatching { it.int }.getOrNull() }

    fun decodeText(obj: JsonObject): String =
        (obj["value"] as? JsonPrimitive)?.content ?: ""
}
