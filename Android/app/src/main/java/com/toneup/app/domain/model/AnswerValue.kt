package com.toneup.app.domain.model

/**
 * 多态作答值：
 * - Choice(label)：单选 / 判断（选项标签）
 * - MultiChoice(labels)：多选
 * - Text(text)：解答 / 翻译 / 作文长文本
 * - Blanks(map 空序->内容)：填空
 * - BlankLabels(map 空序->标签)：完形填空
 * - Order(ids 有序列表)：排序
 */
sealed class AnswerValue {
    data class Choice(val label: String) : AnswerValue()
    data class MultiChoice(val labels: List<String>) : AnswerValue()
    data class Text(val text: String) : AnswerValue()
    data class Blanks(val values: Map<Int, String>) : AnswerValue()
    data class BlankLabels(val values: Map<Int, String>) : AnswerValue()
    data class Order(val ids: List<String>) : AnswerValue()

    val isEmpty: Boolean
        get() = when (this) {
            is Choice -> label.isBlank()
            is MultiChoice -> labels.isEmpty()
            is Text -> text.isBlank()
            is Blanks -> values.values.all { it.isBlank() }
            is BlankLabels -> values.isEmpty()
            is Order -> ids.isEmpty()
        }

    /** 是否存在部分填写（部分留空需二次确认） */
    fun hasPartialBlanks(totalBlanks: Int): Boolean = when (this) {
        is Blanks -> values.count { it.value.isNotBlank() } in 1 until totalBlanks
        is BlankLabels -> values.isNotEmpty() && values.size < totalBlanks
        else -> false
    }
}
