package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.CatalogDto
import com.toneup.app.data.remote.dto.BankDetailDto
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.QuestionDto
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface CatalogApi {
    @GET("api/catalog")
    suspend fun catalog(): ApiEnvelope<CatalogDto>

    @GET("api/question-banks/{bank_id}")
    suspend fun bankDetail(@Path("bank_id") bankId: String): ApiEnvelope<BankDetailDto>
}

interface QuestionApi {
    @GET("api/question-banks/{bank_id}/questions")
    suspend fun questions(
        @Path("bank_id") bankId: String,
        @Query("year") year: Int? = null,
        @Query("type_code") typeCode: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<QuestionDto>>

    @GET("api/question-banks/{bank_id}/questions/{question_id}")
    suspend fun questionDetail(
        @Path("bank_id") bankId: String,
        @Path("question_id") questionId: Long
    ): ApiEnvelope<QuestionDto>
}
