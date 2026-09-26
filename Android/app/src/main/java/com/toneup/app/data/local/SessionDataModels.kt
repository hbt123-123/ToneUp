package com.toneup.app.data.local

import kotlinx.serialization.Serializable

/**
 * 练习草稿。
 * M-15：持久化模型字段全部带默认值——磁盘数据缺字段（版本演进/部分损坏）时
 * 降级解析而不是抛 CorruptionException 丢掉整个 SessionData；
 * M-16：原 `key = "$bankId:$questionId"` 计算属性不含 userId 且全库零使用
 * （草稿数据本身已按 userId 分文件隔离，匹配逻辑直接比较字段），已删除。
 */
@Serializable
data class DraftEntry(
    val userId: Long = 0L,
    val bankId: String = "",
    val questionId: Long = 0L,
    val answer: kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(emptyMap()),
    val updatedAtMillis: Long = 0L
)

/** 已点提交但未获得服务端确认的记录，联网后自动重放（M-15：字段带默认值以兼容旧磁盘数据） */
@Serializable
data class PendingSubmission(
    val userId: Long = 0L,
    val bankId: String = "",
    val questionId: Long = 0L,
    val clientRequestId: String = "",
    val answer: kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.JsonObject(emptyMap()),
    val timeSpentSeconds: Int = 0,
    val mode: String = "",
    val createdAtMillis: Long = 0L
)

/** 最近练习上下文：继续上次刷题（M-15：字段带默认值以兼容旧磁盘数据） */
@Serializable
data class LastPracticeContext(
    val userId: Long = 0L,
    val bankId: String = "",
    val sessionId: String = "",
    val questionIndex: Int = 0,
    val title: String? = null,
    val year: Int? = null,
    val typeCode: String? = null,
    /** EC-01：非空表示服务端会话 id，恢复时据此走 GET detail 拉题目+草稿 */
    val serverSessionId: Long? = null,
    val updatedAtMillis: Long = 0L
)

@Serializable
data class SessionData(
    val drafts: List<DraftEntry> = emptyList(),
    val pendingSubmissions: List<PendingSubmission> = emptyList(),
    val lastContext: LastPracticeContext? = null,
    /** 本地收藏标记，与账号隔离 */
    val markedKeys: List<String> = emptyList(),
    /** EC-01 交卷幂等键：服务端确认（submit 成功）前复用同一 id */
    val sessionSubmitRequestId: String? = null
)
