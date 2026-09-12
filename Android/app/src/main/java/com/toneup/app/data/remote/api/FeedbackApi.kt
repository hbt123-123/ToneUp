package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

interface FeedbackApi {
    @POST("api/question-feedback")
    suspend fun submitFeedback(@Body body: FeedbackRequest): ApiEnvelope<FeedbackResponse>
}

@Serializable
data class FeedbackRequest(
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    val category: String,
    val content: String,
    @SerialName("image_id") val imageId: String? = null
)

@Serializable
data class FeedbackResponse(
    @SerialName("feedback_id") val feedbackId: String,
    val status: String
)
