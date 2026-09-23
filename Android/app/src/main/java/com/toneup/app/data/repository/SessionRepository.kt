package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.SessionApi
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EC-01 练习会话仓库：包装 [SessionApi] 七端点并统一解包信封。
 * open 仅为测试 Fake 继承（照 [SectionRepository] 范式）。
 */
@Singleton
open class SessionRepository @Inject constructor(
    private val sessionApi: SessionApi,
    private val jsonProvider: JsonProvider
) {
    /** 创建服务端会话（两步法选题在后端完成；count 服务端钳制为 50）。 */
    open suspend fun createSession(
        bankId: String,
        collectionIds: List<Long>? = null,
        typeCodes: List<String>? = null,
        count: Int = 20
    ): CreateSessionResponseDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.create(
                CreateSessionRequest(
                    bankId = bankId,
                    collectionIds = collectionIds,
                    typeCodes = typeCodes,
                    count = count
                )
            )
        }

    /** 分页列出本人会话（created_at DESC）。 */
    open suspend fun listSessions(
        page: Int = 1,
        pageSize: Int = 20
    ): PageData<SessionListItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.list(page, pageSize)
        }

    /** 会话详情：session 元信息 + 题目序列 + 实时进度 + 草稿。 */
    open suspend fun sessionDetail(sessionId: Long): SessionDetailDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.detail(sessionId)
        }

    /** 草稿更新（last-write-wins）。失败时调用方应静默处理。 */
    open suspend fun updateDraft(
        sessionId: Long,
        currentIndex: Int,
        draft: kotlinx.serialization.json.JsonObject,
        elapsedSeconds: Int
    ): DraftResponseDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.updateDraft(
                sessionId,
                DraftRequest(currentIndex, draft, elapsedSeconds)
            )
        }

    /** 交卷（条件更新幂等）。重放返回 replayed=true 且 summary 不变。 */
    open suspend fun submitSession(
        sessionId: Long,
        clientRequestId: String
    ): SubmitSessionResponseDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.submit(sessionId, SubmitSessionRequest(clientRequestId))
        }

    /** 逐题结果 + 摘要。 */
    open suspend fun sessionResult(sessionId: Long): SessionResultDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            sessionApi.result(sessionId)
        }

    /** 删除会话。data 为空，走 unwrapUnit。 */
    open suspend fun deleteSession(sessionId: Long) {
        EnvelopeUnwrapper.unwrapUnit(jsonProvider.json) {
            sessionApi.delete(sessionId)
        }
    }
}
