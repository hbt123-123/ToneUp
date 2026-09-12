package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import retrofit2.http.DELETE
import retrofit2.http.Path

interface WrongQuestionApi {
    @DELETE("api/wrong-questions/{id}")
    suspend fun removeWrongQuestion(@Path("id") id: Long): ApiEnvelope<Unit>
}
