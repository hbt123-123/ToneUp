package com.toneup.app.ui.components.question

import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.model.AnswerValue

/**
 * 统一题目渲染上下文（§6.2）：
 * 题型组件为纯 UI——不发网络请求、不读全局状态、不写 DataStore。
 */
data class QuestionContext(
    val question: QuestionDto,
    val answer: AnswerValue?,
    val readonly: Boolean,
    val disabled: Boolean,
    val showAnswer: Boolean,
    val showAnalysis: Boolean,
    val onAnswerChange: (AnswerValue) -> Unit,
    val onSubmitRequest: () -> Unit,
    val onToggleMark: () -> Unit,
    /** 降级卡重试（重新拉取题目详情，§6.4） */
    val onRetryLoad: (() -> Unit)? = null,
    /** 降级卡跳过本题（§6.4） */
    val onSkipQuestion: (() -> Unit)? = null
)
