package com.toneup.app.domain.logic

/**
 * EC-01 服务端会话选题数量策略（与后端 §6.10 契约一致）：
 * - 空输入 → 默认 [DEFAULT_COUNT]（20）
 * - 上限 [MAX_COUNT]（50，后端对 >50 钳制为 50，客户端先行钳制）
 * - 不得超过当前分组可用题量 available
 */
object SessionCountPolicy {
    const val DEFAULT_COUNT = 20
    const val MAX_COUNT = 50

    /** 弹窗输入解析为会话题目数：空/非法输入回退默认值，越界钳制 */
    fun resolve(raw: String, available: Int): Int {
        val upper = MAX_COUNT.coerceAtMost(available.coerceAtLeast(1))
        val parsed = raw.trim().toIntOrNull()
            ?: return DEFAULT_COUNT.coerceAtMost(upper)
        return parsed.coerceIn(1, upper)
    }
}
