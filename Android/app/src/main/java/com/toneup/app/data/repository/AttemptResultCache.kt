package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.remote.dto.AttemptResultDto
import javax.inject.Inject
import javax.inject.Singleton

/** 答题结果内存缓存：POST 返回与 GET 补取共用；解析视图优先命中缓存 */
@Singleton
class AttemptResultCache @Inject constructor() {
    // M-57：@Singleton 常驻进程，原 ConcurrentHashMap 无界增长；
    // 改用 LinkedHashMap（插入序）+ 容量上限，超限淘汰最旧条目，所有访问经 synchronized
    private val byAttemptId = LinkedHashMap<Long, AttemptResultDto>()
    private val latestByQuestion = LinkedHashMap<String, Long>()

    fun put(result: AttemptResultDto, bankId: String?, questionId: Long?) {
        synchronized(this) {
            putAttempt(result)
            if (bankId != null && questionId != null) {
                putLatest("$bankId:$questionId", result.attemptId)
            } else {
                // M-58：缺 bank/question 无法建立 latestByQuestion 索引（后续 latestFor 必然 miss），打点可观测
                Log.w(TAG, "put without index: attempt=${result.attemptId} bank=$bankId question=$questionId")
            }
        }
    }

    fun byAttemptId(attemptId: Long): AttemptResultDto? = synchronized(this) { byAttemptId[attemptId] }

    fun latestFor(bankId: String, questionId: Long): AttemptResultDto? = synchronized(this) {
        latestByQuestion["$bankId:$questionId"]?.let { byAttemptId[it] }
    }

    fun update(result: AttemptResultDto) {
        synchronized(this) { putAttempt(result) }
    }

    fun clear() {
        synchronized(this) {
            byAttemptId.clear()
            latestByQuestion.clear()
        }
    }

    /** M-57：按插入序淘汰最旧条目，并同步清理 latestByQuestion 中的悬空索引 */
    private fun putAttempt(result: AttemptResultDto) {
        byAttemptId[result.attemptId] = result
        while (byAttemptId.size > MAX_ENTRIES) {
            val eldest = byAttemptId.keys.iterator().next()
            byAttemptId.remove(eldest)
            latestByQuestion.values.removeAll { it == eldest }
        }
    }

    /** M-57：question 索引图同样限容 */
    private fun putLatest(key: String, attemptId: Long) {
        latestByQuestion[key] = attemptId
        while (latestByQuestion.size > MAX_ENTRIES) {
            val eldest = latestByQuestion.keys.iterator().next()
            latestByQuestion.remove(eldest)
        }
    }

    private companion object {
        const val TAG = "AttemptResultCache"

        // M-57：每个 map 的容量上限，超限移除最旧插入条目
        const val MAX_ENTRIES = 100
    }
}
