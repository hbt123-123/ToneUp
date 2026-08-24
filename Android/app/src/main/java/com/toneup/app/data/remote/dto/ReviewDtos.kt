package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ReviewItemDto(
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("type_code") val typeCode: String,
    val content: String = "",
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("next_review_at") val nextReviewAt: String? = null,
    @SerialName("estimated_seconds") val estimatedSeconds: Int? = null
)
