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
        // M-93：available<=0 时直接返回 0——不再强抬到 1 而违反「不得超过 available」契约
        if (available <= 0) return 0
        val upper = MAX_COUNT.coerceAtMost(available)
        val trimmed = raw.trim()
        val parsed = trimmed.toIntOrNull()
            // M-94：超出 Int 范围的数字输入（如粘贴超长数字串）钳到上限，而非误判非法回退默认值
            ?: trimmed.toLongOrNull()?.let { upper }
            ?: return DEFAULT_COUNT.coerceAtMost(upper)
        return parsed.coerceIn(1, upper)
    }
}
