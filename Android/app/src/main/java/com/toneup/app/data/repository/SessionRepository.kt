package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.SessionApi
import com.toneup.app.data.remote.dto.CreateSessionRequest
import com.toneup.app.data.remote.dto.CreateSessionResponseDto
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
}
