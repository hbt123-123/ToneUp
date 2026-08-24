package com.toneup.app.data.local

import kotlinx.serialization.Serializable

/** 练习草稿 */
@Serializable
data class DraftEntry(
    val userId: Long,
    val bankId: String,
    val questionId: Long,
    val answer: kotlinx.serialization.json.JsonObject,
    val updatedAtMillis: Long
) {
    val key: String get() = "$bankId:$questionId"
}

/** 已点提交但未获得服务端确认的记录，联网后自动重放 */
@Serializable
data class PendingSubmission(
    val userId: Long,
    val bankId: String,
    val questionId: Long,
    val clientRequestId: String,
    val answer: kotlinx.serialization.json.JsonObject,
    val timeSpentSeconds: Int,
    val mode: String,
    val createdAtMillis: Long
)

/** 最近练习上下文：继续上次刷题 */
@Serializable
data class LastPracticeContext(
    val userId: Long,
    val bankId: String,
    val sessionId: String,
    val questionIndex: Int,
    val title: String? = null,
    val year: Int? = null,
    val typeCode: String? = null,
    val updatedAtMillis: Long
)

@Serializable
data class SessionData(
    val drafts: List<DraftEntry> = emptyList(),
    val pendingSubmissions: List<PendingSubmission> = emptyList(),
    val lastContext: LastPracticeContext? = null,
    /** 本地收藏标记，与账号隔离 */
    val markedKeys: List<String> = emptyList()
)
