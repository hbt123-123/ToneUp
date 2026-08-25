package com.toneup.app.domain.logic

import com.toneup.app.data.remote.dto.OptionDto
import com.toneup.app.data.remote.dto.QuestionDto
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * sub_questions / answer_text 防御性解析：
 * 客户端不假设数据纯净，未知形状一律安全降级。
 */
object SubQuestionParser {

    private val textKeys = listOf("content", "text", "stem", "question", "title", "prompt")

    fun textOf(obj: JsonObject): String? =
        textKeys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        }

    /** options 形状兼容：[{label,text}] 数组 或 {"A": "..."} 对象 */
    fun optionsOf(obj: JsonObject): List<OptionDto>? {
        val raw = obj["options"] ?: return null
        return when (raw) {
            is JsonArray -> raw.mapNotNull { item ->
                (item as? JsonObject)?.let { o ->
                    val label = (o["label"] as? JsonPrimitive)?.content
                        ?: o.keys.firstOrNull()
                    val text = (o["text"] as? JsonPrimitive)?.content
                    if (label != null) OptionDto(label, text ?: "") else null
                }
            }
            is JsonObject -> raw.entries.mapNotNull { (k, v) ->
                // 数组/嵌套对象无法文本化，安全降级跳过；数值/布尔原语仍按字面值保留
                val text = (v as? JsonPrimitive)?.content
                    ?: return@mapNotNull null
                OptionDto(k, text)
            }.sortedBy { it.label }
            else -> null
        }
    }

    /** 填空/完形空数：优先 sub_questions 数量，退化为题干下划线占位计数 */
    fun blankCount(question: QuestionDto): Int {
        question.subQuestions?.let { subs ->
            if (subs.isNotEmpty()) return subs.size
        }
        val source = question.passage ?: question.content
        return UNDERSCORES.findAll(source).count().coerceAtLeast(1)
    }

    fun passageBlankCount(question: QuestionDto): Int =
        UNDERSCORES.findAll(question.passage ?: "").count()
            .takeIf { it > 0 }
            ?: blankCount(question)

    private val UNDERSCORES = Regex("_{2,}")
}

/** answer_text → 正确答案结构（供渲染器 showAnswer 标注） */
object CorrectAnswerParser {

    fun singleLabel(answerText: String?): String? =
        answerText?.trim()?.trim('"', '\'', '[', ']')
            ?.split(Regex("[\\s,:，、]+"))?.firstOrNull { it.isNotBlank() }
            ?.uppercase()?.firstOrNull()?.toString()

    fun multiLabels(answerText: String?): List<String> =
        parseList(answerText).map { it.uppercase() }.filter { it.length <= 2 }

    fun clozeLabels(answerText: String?, blankCount: Int): List<String> {
        val parsed = parseList(answerText)
        return if (parsed.isEmpty()) {
            List(blankCount) { "" }
        } else {
            List(blankCount) { i -> parsed.getOrElse(i) { "" } }
        }
    }

    fun orderingSequence(answerText: String?): List<String> = parseList(answerText)

    private fun parseList(answerText: String?): List<String> {
        val text = answerText?.trim() ?: return emptyList()
        // JSON 数组形式 ["B","D"]
        if (text.startsWith("[")) {
            return runCatching {
                kotlinx.serialization.json.Json.Default
                    .parseToString<List<String>>(text)
            }.getOrDefault(emptyList())
        }
        // 逗号 / 空格分隔
        return text.split(Regex("[\\s,，、]+")).filter { it.isNotBlank() }
    }

    private inline fun <reified T> kotlinx.serialization.json.Json.parseToString(raw: String): T =
        decodeFromString(kotlinx.serialization.serializer(), raw)
}
