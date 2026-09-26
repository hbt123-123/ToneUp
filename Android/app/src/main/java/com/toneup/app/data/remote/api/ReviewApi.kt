package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.ReviewItemDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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

    // M-33：后端 skip 实际返回 {question_id, bank_id, next_review_at} 载荷，不能用 ApiEnvelope<Unit>；
    // 注意后端只从 body 读取 next_review_at 覆盖值（query 传参不生效），当前调用方未传、走默认顺延 1 天
    @POST("api/reviews/{question_id}/skip")
    suspend fun skip(
        @Path("question_id") questionId: Long,
        @Query("bank_id") bankId: String,
        @Query("next_review_at") nextReviewAt: String? = null
    ): ApiEnvelope<SkipReviewResponse>
}

/** M-33：对齐后端 reviews.py skip 的实际响应形状 */
@Serializable
data class SkipReviewResponse(
    @SerialName("question_id") val questionId: Long,
    @SerialName("bank_id") val bankId: String,
    @SerialName("next_review_at") val nextReviewAt: String
)
