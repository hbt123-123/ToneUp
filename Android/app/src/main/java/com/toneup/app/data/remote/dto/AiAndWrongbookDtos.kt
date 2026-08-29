package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiFeedbackCreatedDto(
    @SerialName("feedback_id") val feedbackId: String,
    val status: String = AttemptResultDto.GRADING_QUEUED,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    @SerialName("tag_ids") val tagIds: List<Long> = emptyList()
)

@Serializable
data class AiFeedbackDetailDto(
    @SerialName("feedback_id") val feedbackId: String? = null,
    val status: String,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("tag_ids") val tagIds: List<Long> = emptyList()
)

/** 错题本条目：GET /api/wrong-questions */
@Serializable
data class WrongbookItemDto(
    val id: Long = 0,
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("attempt_count") val attemptCount: Int = 1,
    @SerialName("last_wrong_at") val lastWrongAt: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null
)
