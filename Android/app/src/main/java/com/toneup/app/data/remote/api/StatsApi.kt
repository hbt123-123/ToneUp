package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.StatsOverviewDto
import com.toneup.app.data.remote.dto.WeaknessItemDto
import retrofit2.http.GET
import retrofit2.http.Query

interface StatsApi {
    @GET("api/stats/overview")
    suspend fun overview(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("subject_id") subjectId: String? = null
    ): ApiEnvelope<StatsOverviewDto>

    @GET("api/stats/weaknesses")
    suspend fun weaknesses(
        @Query("subject_id") subjectId: String? = null,
        @Query("limit") limit: Int = 10
    ): ApiEnvelope<List<WeaknessItemDto>>
}
