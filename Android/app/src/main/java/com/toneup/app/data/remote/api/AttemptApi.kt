package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.AttemptResultDto
import com.toneup.app.data.remote.dto.SubmitAttemptRequest
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface AttemptApi {
    /** 202 受理（主观题异步判分）与 200 同步结果均可能返回，用 Response 保留状态码 */
    @POST("api/attempts")
    suspend fun submit(@Body body: SubmitAttemptRequest): Response<ApiEnvelope<AttemptResultDto>>

    @GET("api/attempts/{attempt_id}")
    suspend fun attempt(@Path("attempt_id") attemptId: Long): ApiEnvelope<AttemptResultDto>
}

interface AiFeedbackApi {
    @Multipart
    @POST("api/ai/feedback")
    suspend fun upload(
        @Part file: MultipartBody.Part,
        @Part("bank_id") bankId: okhttp3.RequestBody,
        @Part("question_id") questionId: okhttp3.RequestBody,
        @Part("attempt_id") attemptId: okhttp3.RequestBody?
    ): Response<ApiEnvelope<com.toneup.app.data.remote.dto.AiFeedbackCreatedDto>>

    @GET("api/ai/feedback/{feedback_id}")
    suspend fun feedback(
        @Path("feedback_id") feedbackId: String
    ): ApiEnvelope<com.toneup.app.data.remote.dto.AiFeedbackDetailDto>
}
