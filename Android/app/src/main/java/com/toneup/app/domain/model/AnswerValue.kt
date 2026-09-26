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

    // M-102：构造时防御性拷贝（保留结构 equals/hashCode 语义），外部对原列表的
    // 后续修改不再渗入已存答案
    class MultiChoice(labels: List<String>) : AnswerValue() {
        val labels: List<String> = labels.toList()

        override fun equals(other: Any?): Boolean = other is MultiChoice && other.labels == labels
        override fun hashCode(): Int = labels.hashCode()
        override fun toString(): String = "MultiChoice(labels=$labels)"
    }

    data class Text(val text: String) : AnswerValue()
    data class Blanks(val values: Map<Int, String>) : AnswerValue()
    data class BlankLabels(val values: Map<Int, String>) : AnswerValue()

    // M-103：构造时防御性拷贝（保留结构 equals/hashCode 语义），外部对原列表的
    // 后续修改不再渗入已存答案
    class Order(ids: List<String>) : AnswerValue() {
        val ids: List<String> = ids.toList()

        override fun equals(other: Any?): Boolean = other is Order && other.ids == ids
        override fun hashCode(): Int = ids.hashCode()
        override fun toString(): String = "Order(ids=$ids)"
    }

    val isEmpty: Boolean
        get() = when (this) {
            is Choice -> label.isBlank()
            is MultiChoice -> labels.isEmpty()
            is Text -> text.isBlank()
            // H-29：BlankLabels 与 Blanks 语义对齐——全空值视为未作答
            is Blanks -> values.values.all { it.isBlank() }
            is BlankLabels -> values.values.all { it.isBlank() }
            is Order -> ids.isEmpty()
        }

    /** 是否存在部分填写（部分留空需二次确认） */
    fun hasPartialBlanks(totalBlanks: Int): Boolean = when (this) {
        // H-30：两种填空类型统一按“非空条目数”计数，空白标签不算已填
        is Blanks -> values.count { it.value.isNotBlank() } in 1 until totalBlanks
        is BlankLabels -> values.count { it.value.isNotBlank() } in 1 until totalBlanks
        // M-104：else 收窄为显式类型枚举——新增 AnswerValue 子类型时编译器将强制补充分支
        is Choice, is MultiChoice, is Text, is Order -> false
    }
}
