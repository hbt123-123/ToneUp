package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AiFeedbackCreatedDto(
    @SerialName("feedback_id") val feedbackId: String,
    // M-34：后端两个 AI 端点均恒返回 status（queued/succeeded/failed），去掉 GRADING_QUEUED 默认值，
    // 字段缺失/改名时快速失败，避免误把未评分当排队中
    val status: String,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    @SerialName("tag_ids") val tagIds: List<Long> = emptyList()
)

@Serializable
data class AiFeedbackDetailDto(
    // M-35：与 AiFeedbackCreatedDto 统一契约——后端 _feedback_payload 恒含 feedback_id/status，均改必填
    @SerialName("feedback_id") val feedbackId: String,
    val status: String,
    @SerialName("is_correct") val isCorrect: Boolean? = null,
    val score: Double? = null,
    @SerialName("error_reason") val errorReason: String? = null,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("tag_ids") val tagIds: List<Long> = emptyList()
)

/** 错题本条目：GET /api/wrong-questions */
@Serializable
data class WrongbookItemDto(
    // M-36：后端 _row_to_item 恒返回 id，0 哨兵默认会掩盖字段缺失且多条目 id 冲突，改为必填快速失败
    val id: Long,
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("attempt_count") val attemptCount: Int = 1,
    @SerialName("last_wrong_at") val lastWrongAt: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: String? = null
)
