package com.toneup.app

import com.toneup.app.domain.logic.ReciteMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 背题模式（EC-02）纯决策函数验证：
 * - 背题中提交入口直接 return（零 attempts 上报）
 * - buildContext 派生：全题型只读亮答案
 * - QA failure：背题不封禁收藏（防过度封禁回归）
 */
class ReciteModeTest {

    @Test
    fun `recite mode blocks submit`() {
        // submitCurrent 守卫消费：背题中直接 return
        assertFalse(ReciteMode.canSubmit(recite = true))
    }

    @Test
    fun `normal mode allows submit`() {
        assertTrue(ReciteMode.canSubmit(recite = false))
    }

    @Test
    fun `recite derive makes every type readonly with answer visible`() {
        // 未提交 + 非客观题（如主观题）也必须只读亮答案 + 解析可见
        val derived = ReciteMode.deriveContext(
            submitted = false, objectiveType = false, submitting = false, recite = true
        )
        assertTrue(derived.readonly)
        assertTrue(derived.disabled)
        assertTrue(derived.showAnswer)
        assertTrue(derived.showAnalysis)
    }

    @Test
    fun `recite derive keeps readonly in submitting state too`() {
        val derived = ReciteMode.deriveContext(
            submitted = false, objectiveType = true, submitting = true, recite = true
        )
        assertTrue(derived.readonly)
        assertTrue(derived.disabled)
    }

    @Test
    fun `normal idle mode stays fully interactive`() {
        // 普通模式（未提交、未背题）零变化
        val derived = ReciteMode.deriveContext(
            submitted = false, objectiveType = true, submitting = false, recite = false
        )
        assertFalse(derived.readonly)
        assertFalse(derived.disabled)
        assertFalse(derived.showAnswer)
        assertFalse(derived.showAnalysis)
    }

    @Test
    fun `normal submitted objective keeps legacy answer highlight`() {
        // 现状保持：提交态客观题亮正确答案
        val derived = ReciteMode.deriveContext(
            submitted = true, objectiveType = true, submitting = false, recite = false
        )
        assertTrue(derived.showAnswer)
        assertTrue(derived.showAnalysis)
        assertTrue(derived.readonly)
    }

    @Test
    fun `normal submitted subjective does not flash objective answer`() {
        // 现状保持：提交态主观题不亮客观式答案
        val derived = ReciteMode.deriveContext(
            submitted = true, objectiveType = false, submitting = false, recite = false
        )
        assertFalse(derived.showAnswer)
        assertTrue(derived.showAnalysis)
    }

    @Test
    fun `recite mode does not introduce favorite blocking`() {
        // QA failure（防过度封禁回归）：背题只封提交/上报副作用，收藏保持可用。
        // Derived 仅四个渲染字段，无任何收藏/翻题/答题卡控制位（编译期即约束 buildContext 不得封禁收藏）。
        val fieldNames = ReciteMode.Derived::class.java.declaredFields
            .map { it.name }
            .filter { it != "serialVersionUID" && it != "INSTANCE" && !it.startsWith("\$") }
            .sorted()
        assertEquals(listOf("disabled", "readonly", "showAnalysis", "showAnswer"), fieldNames)
    }
}
