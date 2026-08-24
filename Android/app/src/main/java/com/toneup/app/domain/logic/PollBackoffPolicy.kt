package com.toneup.app.domain.logic

import kotlin.math.min

/**
 * AI 判分/拍照诊断轮询退避（需求 10.1 步骤5）：
 * 间隔 2s 起、指数退避至上限 5s，总时长上限 60s。
 */
object PollBackoffPolicy {
    const val INITIAL_DELAY_MS = 2000L
    const val MAX_DELAY_MS = 5000L
    const val TOTAL_DEADLINE_MS = 60000L

    fun delayForAttempt(attemptIndex: Int): Long =
        min(INITIAL_DELAY_MS shl attemptIndex.coerceAtLeast(0), MAX_DELAY_MS)

    fun isDeadlineExceeded(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_DEADLINE_MS
}
