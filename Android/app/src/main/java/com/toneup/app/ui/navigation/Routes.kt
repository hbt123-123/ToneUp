package com.toneup.app.ui.navigation

/** 路由表（§2.7） */
object Routes {
    const val LOGIN = "login"
    const val REGISTER = "register"
    const val MAIN = "main"

    const val TAB_BANK = "tab_bank"
    const val TAB_REVIEW = "tab_review"
    const val TAB_STATS = "tab_stats"
    const val TAB_MINE = "tab_mine"

    /** 刷题页：practice/{sessionId}?mode={practice|review}&index={index} */
    const val PRACTICE_PATTERN = "practice/{sessionId}?mode={mode}&index={index}"
    fun practice(sessionId: String, mode: String = "practice", index: Int = -1) =
        "practice/$sessionId?mode=$mode&index=$index"

    /** 交卷检查页：practice/{sessionId}/review */
    const val REVIEW_CHECK_PATTERN = "practice/{sessionId}/review"
    fun reviewCheck(sessionId: String) = "practice/$sessionId/review"

    /** 解析视图 */
    const val ANALYSIS_PATTERN = "analysis/{attemptId}"
    fun analysis(attemptId: Long) = "analysis/$attemptId"

    /** 错题本（二级页） */
    const val WRONGBOOK = "wrongbook"

    /** 笔记编辑 */
    const val NOTE_EDITOR_PATTERN = "noteEditor/{questionId}?bankId={bankId}"
    fun noteEditor(questionId: Long, bankId: String) =
        "noteEditor/$questionId?bankId=$bankId"

    /** AI 拍照纠错流程页 */
    const val AI_PHOTO_PATTERN = "aiPhoto?bankId={bankId}&questionId={questionId}&attemptId={attemptId}"
    fun aiPhoto(bankId: String, questionId: Long, attemptId: Long?) =
        buildString {
            append("aiPhoto?bankId=").append(bankId)
            append("&questionId=").append(questionId)
            append("&attemptId=").append(attemptId ?: -1L)
        }

    const val FORMULA_POC = "formula_poc" // debug-only PoC 页
}
