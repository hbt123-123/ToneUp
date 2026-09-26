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
        // M-26：后端 notes.py 此端点 bank_id 为必填 Query(...)，而 wrong_questions.py 的过滤参数可选，
        // 两者契约本就不同，此处必填是正确对齐，勿改为可选（可选会触发后端 422）
        @Query("bank_id") bankId: String
    ): ApiEnvelope<NoteDto>

    @PUT("api/questions/{question_id}/notes")
    suspend fun putNote(
        @Path("question_id") questionId: Long,
        @Body body: NotePutRequest
    ): ApiEnvelope<NoteDto>

    /**
     * 临时聚合端点（自拟，待后端对齐）
     * M-27：后端 notes.py 目前仅有 PUT/DELETE /api/notes/{note_id} 与点赞端点，
     * 并不存在 GET /api/notes 列表端点，本方法调用会 404；Retrofit 路径错误只在运行时暴露，
     * 后端补齐或下线本端点时必须同步修改此处路径
     */
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
