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

/** 错题本临时端点（自拟，待后端对齐）：GET /api/wrongbook */
@Serializable
data class WrongbookItemDto(
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("type_code") val typeCode: String,
    val year: Int = 0,
    val content: String = "",
    @SerialName("wrong_count") val wrongCount: Int = 1,
    @SerialName("last_wrong_at") val lastWrongAt: String? = null,
    @SerialName("mastery_level") val masteryLevel: Int? = null
)
