package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class OptionDto(
    val label: String,
    val text: String
)

@Serializable
data class QuestionDto(
    // M-44：bank_id/question_id/type_code 是后端 _build_dto 固定输出的核心键，有意不加默认值，
    // 字段缺失时抛 MissingFieldException 快速失败，防止契约漂移被静默吞掉
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("collection_id") val collectionId: Long = 0,
    val year: Int = 0,
    // M-45：type_code 可能为 "" 或未登记码（后端 mapping.get 回退 UNKNOWN），
    // 消费方依赖 RendererRegistry/FallbackRenderer 兜底，新增题型时须同步 TYPE_* 常量
    @SerialName("type_code") val typeCode: String,
    val number: Int = 0,
    val content: String = "",
    val passage: String? = null,
    val options: List<OptionDto>? = null,
    val subQuestions: List<JsonObject>? = null,
    @SerialName("display_order") val displayOrder: Int = 0,
    @SerialName("answer_text") val answerText: String? = null,
    val solution: String? = null
) {
    companion object {
        const val TYPE_SINGLE = "SINGLE"
        const val TYPE_MULTI = "MULTI"
        const val TYPE_JUDGE = "JUDGE"
        const val TYPE_FILL_BLANK = "FILL_BLANK"
        const val TYPE_SOLUTION = "SOLUTION"
        const val TYPE_CLOZE = "CLOZE"
        const val TYPE_READING = "READING"
        const val TYPE_ORDERING = "ORDERING"
        const val TYPE_TRANSLATION = "TRANSLATION"
        const val TYPE_ESSAY = "ESSAY"
    }
}

@Serializable
data class SubmitAttemptRequest(
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    val answer: JsonObject,
    @SerialName("time_spent") val timeSpentSeconds: Int,
    val mode: String,
    @SerialName("client_request_id") val clientRequestId: String
)

@Serializable
data class AiFeedbackDto(
    val status: String? = null,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    @SerialName("tag_ids") val tagIds: List<Long> = emptyList(),
    @SerialName("error_message") val errorMessage: String? = null
)

@Serializable
data class AttemptResultDto(
    @SerialName("attempt_id") val attemptId: Long,
    @SerialName("bank_id") val bankId: String? = null,
    @SerialName("question_id") val questionId: Long? = null,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("grading_status") val gradingStatus: String? = null,
    val feedback: AiFeedbackDto? = null,
    @SerialName("answer_text") val answerText: String? = null,
    val solution: String? = null
) {
    companion object {
        const val GRADING_QUEUED = "queued"
        const val GRADING_PROCESSING = "processing"
        const val GRADING_SUCCEEDED = "succeeded"
        const val GRADING_FAILED = "failed"
    }

    // M-46：判分状态比较忽略大小写，避免后端状态码大小写变化（如 QUEUED）导致主观题等待态误判
    val isSubjectivePending: Boolean
        get() = gradingStatus.equals(GRADING_QUEUED, ignoreCase = true) ||
            gradingStatus.equals(GRADING_PROCESSING, ignoreCase = true)
}

@Serializable
data class SelfJudgeRequestPayload(
    @SerialName("self_correct") val selfCorrect: Boolean,
    val reason: String? = null
)
