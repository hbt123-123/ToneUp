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
        val all: List<QuestionType> by lazy {
            listOf(Single, Multi, Judge, FillBlank, Solution, Cloze, Reading, Ordering, Translation, Essay)
        }

        fun fromCode(code: String): QuestionType? = all.firstOrNull { it.typeCode == code }
    }
}
