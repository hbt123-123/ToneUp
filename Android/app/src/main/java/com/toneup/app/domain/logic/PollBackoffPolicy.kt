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

    fun delayForAttempt(attemptIndex: Int): Long {
        // H-28：shl 在 attemptIndex>=53 时按 63 位掩码回绕产生负值，改为逐次倍增并在上限截断
        var delay = INITIAL_DELAY_MS
        repeat(attemptIndex.coerceAtLeast(0)) {
            delay = min(delay * 2, MAX_DELAY_MS)
        }
        return delay
    }

    fun isDeadlineExceeded(elapsedMs: Long): Boolean = elapsedMs >= TOTAL_DEADLINE_MS
}
