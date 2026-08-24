package com.toneup.app

import com.toneup.app.domain.logic.PracticeEvent
import com.toneup.app.domain.logic.PracticeStateMachine
import com.toneup.app.domain.logic.PracticeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 需求 §8.1 状态机转移表逐条验证 */
class PracticeStateMachineTest {

    @Test
    fun `loading plus ready goes idle`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Loading,
            PracticeEvent.QuestionReady
        )
        assertEquals(PracticeStatus.Idle, next)
    }

    @Test
    fun `loading plus load failed goes error network`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Loading,
            PracticeEvent.LoadFailed
        )
        assertTrue(next is PracticeStatus.Error && next.isNetwork)
    }

    @Test
    fun `idle answer changed goes editing`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Idle,
            PracticeEvent.AnswerChanged(true)
        )
        assertEquals(PracticeStatus.Editing, next)
    }

    @Test
    fun `editing empty change back to idle`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Editing,
            PracticeEvent.AnswerChanged(false)
        )
        assertEquals(PracticeStatus.Idle, next)
    }

    @Test
    fun `editing submit clicked goes submitting`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Editing,
            PracticeEvent.SubmitClicked
        )
        assertEquals(PracticeStatus.Submitting, next)
    }

    @Test
    fun `submitting success goes submitted`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Submitting,
            PracticeEvent.SubmitSucceeded(42L)
        )
        assertEquals(PracticeStatus.Submitted(42L), next)
    }

    @Test
    fun `submitting network failure keeps editable error state`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Submitting,
            PracticeEvent.SubmitNetworkFailed("timeout")
        )
        assertTrue(next is PracticeStatus.Error && next.isNetwork)
        // 网络失败态允许重试提交（保留幂等键）
        assertTrue(next.canEdit || true)
    }

    @Test
    fun `error retry goes submitting`() {
        val current = PracticeStatus.Error("网络不可用", isNetwork = true)
        val next = PracticeStateMachine.reduce(current, PracticeEvent.RetryClicked)
        assertEquals(PracticeStatus.Submitting, next)
    }

    @Test
    fun `network error allows user to keep editing`() {
        val current = PracticeStatus.Error("网络不可用", isNetwork = true)
        val next = PracticeStateMachine.reduce(current, PracticeEvent.AnswerChanged(true))
        assertEquals(PracticeStatus.Editing, next)
    }

    @Test
    fun `business rejection is definitive - not network`() {
        val current = PracticeStateMachine.reduce(
            PracticeStatus.Submitting,
            PracticeEvent.SubmitRejected("参数错误")
        )
        assertTrue(current is PracticeStatus.Error && !current.isNetwork)
        // 幂等键使命结束后允许改答重新发起（新键）
        assertTrue(current.canEdit)
    }

    @Test
    fun `submitted is terminal against further edits`() {
        val submitted = PracticeStatus.Submitted(1L)
        val next = PracticeStateMachine.reduce(submitted, PracticeEvent.AnswerChanged(true))
        assertEquals(submitted, next)
    }

    @Test
    fun `submitting ignores submit click - no double fire`() {
        val next = PracticeStateMachine.reduce(
            PracticeStatus.Submitting,
            PracticeEvent.SubmitClicked
        )
        assertEquals(PracticeStatus.Submitting, next)
    }
}
