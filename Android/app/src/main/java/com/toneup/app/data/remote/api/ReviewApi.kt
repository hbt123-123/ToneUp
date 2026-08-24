package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.ReviewItemDto
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.GET
import retrofit2.http.Query

interface ReviewApi {
    @GET("api/reviews/today")
    suspend fun today(
        @Query("limit") limit: Int = 20,
        @Query("subject_id") subjectId: String? = null
    ): ApiEnvelope<PageData<ReviewItemDto>>

    @POST("api/reviews/{question_id}/skip")
    suspend fun skip(
        @Path("question_id") questionId: Long,
        @Query("bank_id") bankId: String,
        @Query("next_review_at") nextReviewAt: String? = null
    ): ApiEnvelope<Unit>
}
