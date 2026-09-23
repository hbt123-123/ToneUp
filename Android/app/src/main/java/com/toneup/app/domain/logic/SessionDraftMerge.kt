package com.toneup.app.domain.logic

import kotlinx.serialization.json.JsonObject

/**
 * EC-01 服务端会话草稿合并策略（纯函数，可 JVM 单测）：
 *
 * 服务端 draft 结构（PUT /{sid}/draft 的 body.draft）：
 * ```
 * { "<questionId>": <answerJson>, ... }
 * ```
 *
 * 恢复装载时「服务端优先、本地兜底」：
 * - 服务端草稿含该题 → 采用服务端版本（last-write-wins 由服务端时间戳背书）
 * - 服务端缺失该题   → 采用本地 DataStore 草稿（离线期间的最新作答）
 */
object SessionDraftMerge {

    /**
     * 解析服务端 draft JsonObject 为 questionId → answer 映射。
     * 非法 key（非数字）或 value 非 JsonObject 的条目忽略。
     */
    fun parseServerDraft(serverDraft: JsonObject?): Map<Long, JsonObject> {
        if (serverDraft == null) return emptyMap()
        val result = LinkedHashMap<Long, JsonObject>()
        serverDraft.forEach { (key, value) ->
            val qid = key.toLongOrNull() ?: return@forEach
            val answer = value as? JsonObject ?: return@forEach
            result[qid] = answer
        }
        return result
    }

    /**
     * 解析单题草稿答案：服务端优先、本地兜底；两边都缺返回 null。
     *
     * @param serverDraft 服务端 draft（可空）
     * @param localEntries 本地草稿 (questionId → answerJson) 列表
     * @param questionId 目标题目
     */
    fun resolve(
        serverDraft: JsonObject?,
        localEntries: List<Pair<Long, JsonObject>>,
        questionId: Long
    ): JsonObject? {
        parseServerDraft(serverDraft)[questionId]?.let { return it }
        return localEntries.firstOrNull { it.first == questionId }?.second
    }

    /**
     * 将本地草稿列表编码为服务端 draft JsonObject（questionId 十进制字符串为 key）。
     * 同题多条时后者覆盖前者。
     */
    fun toServerDraft(localEntries: List<Pair<Long, JsonObject>>): JsonObject =
        JsonObject(localEntries.associate { (qid, answer) -> qid.toString() to answer })
}
