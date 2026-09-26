package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NoteDto(
    @SerialName("note_text") val noteText: String,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class NotePutRequest(
    @SerialName("bank_id") val bankId: String,
    @SerialName("note_text") val noteText: String
)

/** 临时聚合端点（自拟，待后端对齐）：GET /api/notes */
@Serializable
data class NoteListItemDto(
    // M-42：自拟聚合端点契约未经后端验证，标识字段加保守默认（""/0）防载荷缺字段时崩溃
    @SerialName("bank_id") val bankId: String = "",
    @SerialName("question_id") val questionId: Long = 0,
    // M-43：note_text 默认 "" 会掩盖后端改名/删字段的契约漂移，后端对齐后应移除默认值
    @SerialName("note_text") val noteText: String = "",
    @SerialName("question_summary") val questionSummary: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
