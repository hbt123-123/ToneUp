package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import com.toneup.app.data.remote.dto.CreateSessionRequest
import com.toneup.app.data.remote.dto.CreateSessionResponseDto
import com.toneup.app.data.remote.dto.DraftRequest
import com.toneup.app.data.remote.dto.DraftResponseDto
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.SessionDetailDto
import com.toneup.app.data.remote.dto.SessionListItemDto
import com.toneup.app.data.remote.dto.SessionResultDto
import com.toneup.app.data.remote.dto.SubmitSessionRequest
import com.toneup.app.data.remote.dto.SubmitSessionResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * EC-01 练习会话七端点客户端（后端 §6.10）。
 *
 * 路由前缀 `/api/practice-sessions`；统一信封由 [com.toneup.app.data.repository.EnvelopeUnwrapper]
 * 在 repository 层解包，本接口返回原始 [ApiEnvelope]。
 */
interface SessionApi {

    /** 创建会话（两步法选题，限流 60/min/用户）。`count` 服务端钳制为 50。 */
    @POST("api/practice-sessions")
    suspend fun create(
        @Body body: CreateSessionRequest
    ): ApiEnvelope<CreateSessionResponseDto>

    /** 分页列出本人会话（created_at DESC），附每会话已答数。 */
    @GET("api/practice-sessions")
    suspend fun list(
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<PageData<SessionListItemDto>>

    /** 会话详情：session 元信息 + 题目序列 + 实时进度。非本人 404。 */
    @GET("api/practice-sessions/{session_id}")
    suspend fun detail(
        @Path("session_id") sessionId: Long
    ): ApiEnvelope<SessionDetailDto>

    /**
     * 草稿更新（last-write-wins，无冲突合并）。会话已提交返回 409；
     * 节流由客户端负责（PC 10s / Android 切题时），服务端不限流。
     */
    @PUT("api/practice-sessions/{session_id}/draft")
    suspend fun updateDraft(
        @Path("session_id") sessionId: Long,
        @Body body: DraftRequest
    ): ApiEnvelope<DraftResponseDto>

    /** 交卷（条件更新幂等）。重放返回 `replayed=true` 且 summary 不变。 */
    @POST("api/practice-sessions/{session_id}/submit")
    suspend fun submit(
        @Path("session_id") sessionId: Long,
        @Body body: SubmitSessionRequest
    ): ApiEnvelope<SubmitSessionResponseDto>

    /** 逐题结果（is_correct/time_spent 取每题最新一条记录）+ 摘要。非本人 404。 */
    @GET("api/practice-sessions/{session_id}/result")
    suspend fun result(
        @Path("session_id") sessionId: Long
    ): ApiEnvelope<SessionResultDto>

    /** 删除会话及其题目序列。非本人/不存在 404。data 为空，走 [EnvelopeUnwrapper.unwrapUnit]。 */
    @DELETE("api/practice-sessions/{session_id}")
    suspend fun delete(
        @Path("session_id") sessionId: Long
    ): ApiEnvelope<Unit>
}
