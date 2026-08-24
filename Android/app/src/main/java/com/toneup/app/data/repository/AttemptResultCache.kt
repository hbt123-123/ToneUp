package com.toneup.app.data.repository

import com.toneup.app.data.remote.dto.AttemptResultDto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 答题结果内存缓存：POST 返回与 GET 补取共用；解析视图优先命中缓存 */
@Singleton
class AttemptResultCache @Inject constructor() {
    private val byAttemptId = ConcurrentHashMap<Long, AttemptResultDto>()
    private val latestByQuestion = ConcurrentHashMap<String, Long>()

    fun put(result: AttemptResultDto, bankId: String?, questionId: Long?) {
        byAttemptId[result.attemptId] = result
        if (bankId != null && questionId != null) {
            latestByQuestion["$bankId:$questionId"] = result.attemptId
        }
    }

    fun byAttemptId(attemptId: Long): AttemptResultDto? = byAttemptId[attemptId]

    fun latestFor(bankId: String, questionId: Long): AttemptResultDto? =
        latestByQuestion["$bankId:$questionId"]?.let { byAttemptId[it] }

    fun update(result: AttemptResultDto) {
        byAttemptId[result.attemptId] = result
    }

    fun clear() {
        byAttemptId.clear()
        latestByQuestion.clear()
    }
}
