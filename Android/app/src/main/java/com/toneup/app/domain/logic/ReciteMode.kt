package com.toneup.app.domain.logic

/**
 * 背题模式（EC-02）纯决策函数：JVM 可测，供 [com.toneup.app.ui.feature.practice.PracticeViewModel]
 * 的 submitCurrent 守卫与 [com.toneup.app.ui.feature.practice] buildContext 消费。
 *
 * 契约：背题只封「提交 / attempts 上报 / 错题本 / 掌握度」副作用；
 * 不封收藏、答题卡跳题、翻题（Derived 刻意不含这些字段，见 ReciteModeTest）。
 */
object ReciteMode {

    /** 背题中禁止提交（零 POST /api/attempts，含离线队列不入队） */
    fun canSubmit(recite: Boolean): Boolean = !recite

    /** 题目渲染上下文的背题派生结果（对应 QuestionContext 四个只读布尔） */
    data class Derived(
        val readonly: Boolean,
        val disabled: Boolean,
        val showAnswer: Boolean,
        val showAnalysis: Boolean
    )

    fun deriveContext(
        submitted: Boolean,
        objectiveType: Boolean,
        submitting: Boolean = false,
        recite: Boolean
    ): Derived = Derived(
        readonly = submitted || recite,
        disabled = submitting || recite,
        // 背题时全部题型亮答案；普通模式仅提交态的客观题亮答案（现状保持）
        showAnswer = (submitted && objectiveType) || recite,
        showAnalysis = submitted || recite
    )
}
