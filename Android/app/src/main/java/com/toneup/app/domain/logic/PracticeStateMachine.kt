package com.toneup.app.domain.logic

/**
 * 练习状态机（需求 §8.1）：每道题独立维护
 * 全集 loading / idle / editing / submitting / submitted / error
 */
sealed class PracticeStatus {
    data object Loading : PracticeStatus()
    data object Idle : PracticeStatus()
    data object Editing : PracticeStatus()
    data object Submitting : PracticeStatus()
    data class Submitted(val attemptId: Long) : PracticeStatus()
    data class Error(val message: String, val isNetwork: Boolean) : PracticeStatus()

    val canEdit: Boolean
        get() = this is Idle || this is Editing || this is Error
}

sealed class PracticeEvent {
    data object QuestionReady : PracticeEvent()
    data object LoadFailed : PracticeEvent()
    data class AnswerChanged(val changed: Boolean = true) : PracticeEvent()
    data object SubmitClicked : PracticeEvent()
    data class SubmitSucceeded(val attemptId: Long) : PracticeEvent()
    /** 网络失败：答案入未同步队列，可重试 */
    data class SubmitNetworkFailed(val message: String) : PracticeEvent()
    /** 业务拒绝（如参数错误）：幂等键使命结束 */
    data class SubmitRejected(val message: String) : PracticeEvent()
    data object RetryClicked : PracticeEvent()
}

/**
 * 纯函数状态转移表；主观题判分子状态由 UI 层叠加在 Submitted 之上。
 * 幂等约束：网络失败后重试沿用原 client_request_id（由调用方保证），
 * 业务拒绝后才允许生成新 ID。
 */
object PracticeStateMachine {

    fun reduce(current: PracticeStatus, event: PracticeEvent): PracticeStatus = when (current) {
        is PracticeStatus.Loading -> when (event) {
            is PracticeEvent.QuestionReady -> PracticeStatus.Idle
            is PracticeEvent.LoadFailed -> PracticeStatus.Error("题目加载失败", isNetwork = true)
            else -> current
        }
        is PracticeStatus.Idle -> when (event) {
            is PracticeEvent.AnswerChanged ->
                if (event.changed) PracticeStatus.Editing else current
            else -> current
        }
        is PracticeStatus.Editing -> when (event) {
            is PracticeEvent.AnswerChanged ->
                if (event.changed) current else PracticeStatus.Idle
            is PracticeEvent.SubmitClicked -> PracticeStatus.Submitting
            is PracticeEvent.SubmitSucceeded -> PracticeStatus.Submitted(event.attemptId)
            else -> current
        }
        is PracticeStatus.Submitting -> when (event) {
            is PracticeEvent.SubmitSucceeded -> PracticeStatus.Submitted(event.attemptId)
            is PracticeEvent.SubmitNetworkFailed -> PracticeStatus.Error(event.message, isNetwork = true)
            is PracticeEvent.SubmitRejected -> PracticeStatus.Error(event.message, isNetwork = false)
            else -> current
        }
        is PracticeStatus.Error -> when (event) {
            is PracticeEvent.RetryClicked -> PracticeStatus.Submitting
            is PracticeEvent.AnswerChanged ->
                // §8.1：error 态用户可继续修改答案（幂等键沿用直到服务端确认）
                if (event.changed) PracticeStatus.Editing else current
            is PracticeEvent.SubmitSucceeded -> PracticeStatus.Submitted(event.attemptId)
            is PracticeEvent.QuestionReady -> PracticeStatus.Idle
            else -> current
        }
        is PracticeStatus.Submitted -> when (event) {
            is PracticeEvent.SubmitSucceeded -> PracticeStatus.Submitted(event.attemptId)
            else -> current
        }
    }
}
