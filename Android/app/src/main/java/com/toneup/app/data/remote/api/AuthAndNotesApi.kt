package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.NoteDto
import com.toneup.app.data.remote.dto.NoteListItemDto
import com.toneup.app.data.remote.dto.NotePutRequest
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface AuthApi {
    @POST("api/auth/register")
    suspend fun register(@Body body: com.toneup.app.data.remote.dto.RegisterRequest): ApiEnvelope<UserDto>

    @POST("api/auth/login")
    suspend fun login(@Body body: com.toneup.app.data.remote.dto.LoginRequest): ApiEnvelope<com.toneup.app.data.remote.dto.TokenResponse>

    @GET("api/auth/me")
    suspend fun me(): ApiEnvelope<UserDto>
}

interface NotesApi {
    @GET("api/questions/{question_id}/notes")
    suspend fun note(
        @Path("question_id") questionId: Long,
        @Query("bank_id") bankId: String
    ): ApiEnvelope<NoteDto>

    @PUT("api/questions/{question_id}/notes")
    suspend fun putNote(
        @Path("question_id") questionId: Long,
        @Body body: NotePutRequest
    ): ApiEnvelope<NoteDto>

    /** 临时聚合端点（自拟，待后端对齐） */
    @GET("api/notes")
    suspend fun myNotes(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<NoteListItemDto>>
}

/** 错题本端点 */
interface WrongbookApi {
    @GET("api/wrong-questions")
    suspend fun wrongbook(
        @Query("bank_id") bankId: String? = null,
        @Query("subject_id") subjectId: String? = null,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<com.toneup.app.data.remote.dto.WrongbookItemDto>>
}
