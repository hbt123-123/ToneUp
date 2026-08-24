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
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("collection_id") val collectionId: Long = 0,
    val year: Int = 0,
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

    val isSubjectivePending: Boolean
        get() = gradingStatus == GRADING_QUEUED || gradingStatus == GRADING_PROCESSING
}

@Serializable
data class SelfJudgeRequestPayload(
    @SerialName("self_correct") val selfCorrect: Boolean,
    val reason: String? = null
)
