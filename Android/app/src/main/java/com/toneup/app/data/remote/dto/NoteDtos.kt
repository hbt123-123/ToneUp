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
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long,
    @SerialName("note_text") val noteText: String = "",
    @SerialName("question_summary") val questionSummary: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
