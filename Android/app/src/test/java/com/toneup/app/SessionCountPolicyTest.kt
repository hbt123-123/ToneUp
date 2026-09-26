package com.toneup.app

import com.toneup.app.domain.logic.SessionCountPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** EC-01 选题数量钳制纯函数（任务 8 QA happy） */
class SessionCountPolicyTest {

    @Test
    fun `input 999 clamps to 50`() {
        assertEquals(50, SessionCountPolicy.resolve("999", available = Int.MAX_VALUE))
    }

    @Test
    fun `empty input defaults to 20`() {
        assertEquals(20, SessionCountPolicy.resolve("", available = 100))
        assertEquals(20, SessionCountPolicy.resolve("   ", available = 100))
    }

    @Test
    fun `non-numeric input defaults to 20`() {
        assertEquals(20, SessionCountPolicy.resolve("abc", available = 100))
    }

    @Test
    fun `input clamped by available count`() {
        // 分组仅剩 12 题 → 999 钳制为 12
        assertEquals(12, SessionCountPolicy.resolve("999", available = 12))
        assertEquals(8, SessionCountPolicy.resolve("8", available = 12))
    }

    @Test
    fun `default 20 clamped when available below 20`() {
        assertEquals(5, SessionCountPolicy.resolve("", available = 5))
    }

    @Test
    fun `zero available returns 0 per contract`() {
        // M-93：available<=0 不得再强抬到 1（违反「不得超过 available」契约），返回 0
        assertEquals(0, SessionCountPolicy.resolve("3", available = 0))
    }

    @Test
    fun `zero and negative input clamp to 1`() {
        assertEquals(1, SessionCountPolicy.resolve("0", available = 30))
        assertEquals(1, SessionCountPolicy.resolve("-5", available = 30))
    }

    @Test
    fun `normal input passes through`() {
        assertEquals(30, SessionCountPolicy.resolve("30", available = 40))
    }
}
