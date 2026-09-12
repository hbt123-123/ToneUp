package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.SectionsResponse
import retrofit2.http.GET
import retrofit2.http.Path

interface SectionsApi {
    @GET("api/question-banks/{bankId}/sections")
    suspend fun sections(@Path("bankId") bankId: String): ApiEnvelope<SectionsResponse>
}
