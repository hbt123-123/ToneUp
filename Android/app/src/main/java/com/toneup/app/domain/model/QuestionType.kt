package com.toneup.app.domain.model

/** 题型码密封类：键名与服务端 type_code 字符串完全一致 */
sealed class QuestionType(val typeCode: String) {
    object Single : QuestionType("SINGLE")
    object Multi : QuestionType("MULTI")
    object Judge : QuestionType("JUDGE")
    object FillBlank : QuestionType("FILL_BLANK")
    object Solution : QuestionType("SOLUTION")
    object Cloze : QuestionType("CLOZE")
    object Reading : QuestionType("READING")
    object Ordering : QuestionType("ORDERING")
    object Translation : QuestionType("TRANSLATION")
    object Essay : QuestionType("ESSAY")

    companion object {
        // M-105：all 手工重复了全部密封子类型，新增/改名子类型时需同步更新本列表；
        // 可改用 sealedSubclasses 派生，但无 kotlin-reflect 时该 API 的运行时行为无法保证，
        // 且本列表在启动关键路径被消费，故暂保留手写列表（deferred）
        val all: List<QuestionType> by lazy {
            listOf(Single, Multi, Judge, FillBlank, Solution, Cloze, Reading, Ordering, Translation, Essay)
        }

        fun fromCode(code: String): QuestionType? {
            // M-106：type_code 来自服务端字段，比较前先去空白并忽略大小写
            val normalized = code.trim().uppercase()
            return all.firstOrNull { it.typeCode == normalized }
        }
    }
}
