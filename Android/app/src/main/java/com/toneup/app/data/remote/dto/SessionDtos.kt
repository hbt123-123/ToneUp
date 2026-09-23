package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * EC-01 练习会话 DTO（后端 §6.10 契约，字段逐一对齐
 * `backend/app/api/practice_sessions.py`）。
 *
 * 端点契约：
 * - POST   /api/practice-sessions              CreateSessionRequest → CreateSessionResponseDto
 * - GET    /api/practice-sessions              PageData<SessionListItemDto>
 * - GET    /api/practice-sessions/{sid}         SessionDetailDto
 * - PUT    /api/practice-sessions/{sid}/draft   DraftRequest → DraftResponseDto
 * - POST   /api/practice-sessions/{sid}/submit  SubmitSessionRequest → SubmitSessionResponseDto
 * - GET    /api/practice-sessions/{sid}/result  SessionResultDto
 * - DELETE /api/practice-sessions/{sid}         data 为空，走 [EnvelopeUnwrapper.unwrapUnit]
 *
 * 蛇形 JSON 字段名经 @SerialName 映射；可空字段对应后端可能省略或为 null 的字段。
 */
@Serializable
data class CreateSessionRequest(
    @SerialName("bank_id") val bankId: String,
    @SerialName("collection_ids") val collectionIds: List<Long>? = null,
    @SerialName("type_codes") val typeCodes: List<String>? = null,
    val count: Int = 20
)

@Serializable
data class CreateSessionResponseDto(
    @SerialName("session_id") val sessionId: Long,
    @SerialName("bank_id") val bankId: String,
    val title: String,
    @SerialName("total_count") val totalCount: Int,
    val questions: List<QuestionDto> = emptyList()
)

@Serializable
data class SessionListItemDto(
    val id: Long,
    @SerialName("bank_id") val bankId: String,
    val title: String,
    val status: String,
    @SerialName("total_count") val totalCount: Int,
    val answered: Int,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class SessionDto(
    val id: Long,
    @SerialName("bank_id") val bankId: String,
    val title: String,
    val status: String,
    @SerialName("total_count") val totalCount: Int,
    @SerialName("current_index") val currentIndex: Int,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int,
    val draft: JsonObject = JsonObject(emptyMap())
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUBMITTED = "submitted"
    }
}

@Serializable
data class SessionProgressDto(
    val answered: Int,
    val total: Int
)

@Serializable
data class SessionDetailDto(
    val session: SessionDto,
    val questions: List<QuestionDto> = emptyList(),
    val progress: SessionProgressDto
)

@Serializable
data class DraftRequest(
    @SerialName("current_index") val currentIndex: Int,
    val draft: JsonObject,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int
)

@Serializable
data class DraftResponseDto(
    @SerialName("current_index") val currentIndex: Int,
    @SerialName("updated_at") val updatedAt: String
)

@Serializable
data class SubmitSessionRequest(
    @SerialName("client_request_id") val clientRequestId: String
)

@Serializable
data class SessionSummaryDto(
    val total: Int,
    val answered: Int,
    val correct: Int,
    @SerialName("accuracy_rate") val accuracyRate: Double,
    @SerialName("elapsed_seconds") val elapsedSeconds: Int
)

@Serializable
data class SubmitSessionResponseDto(
    val summary: SessionSummaryDto,
    val replayed: Boolean
)

@Serializable
data class SessionResultItemDto(
    @SerialName("question_id") val questionId: Long,
    val position: Int,
    val answered: Boolean,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    @SerialName("time_spent") val timeSpent: Int = 0
)

@Serializable
data class SessionResultDto(
    val items: List<SessionResultItemDto> = emptyList(),
    val summary: SessionSummaryDto
)
