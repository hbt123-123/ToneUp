package com.toneup.app.ui.navigation

import android.net.Uri

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
        // M-248：动态段/查询参数经 Uri.encode，防 '/'、'?'、'&'、'%'、空格破坏路由匹配
        // （NavHost 占位符本身不编码，Navigation 解析参数时会自动 Uri.decode 还原）
        "practice/${Uri.encode(sessionId)}?mode=${Uri.encode(mode)}&index=$index"

    /** 交卷检查页：practice/{sessionId}/review */
    const val REVIEW_CHECK_PATTERN = "practice/{sessionId}/review"
    // M-248：sessionId 为路径段，同步编码
    fun reviewCheck(sessionId: String) = "practice/${Uri.encode(sessionId)}/review"

    /** 解析视图 */
    const val ANALYSIS_PATTERN = "analysis/{attemptId}"
    fun analysis(attemptId: Long) = "analysis/$attemptId"

    /** 错题本（二级页） */
    const val WRONGBOOK = "wrongbook"

    /** 笔记编辑 */
    const val NOTE_EDITOR_PATTERN = "noteEditor/{questionId}?bankId={bankId}"
    // M-249：bankId 为查询参数，编码防 '&'、'=' 破坏查询串
    fun noteEditor(questionId: Long, bankId: String) =
        "noteEditor/$questionId?bankId=${Uri.encode(bankId)}"

    /** AI 拍照纠错流程页 */
    const val AI_PHOTO_PATTERN = "aiPhoto?bankId={bankId}&questionId={questionId}&attemptId={attemptId}"
    fun aiPhoto(bankId: String, questionId: Long, attemptId: Long?) =
        buildString {
            // M-250：bankId 查询参数编码（同 noteEditor）
            append("aiPhoto?bankId=").append(Uri.encode(bankId))
            append("&questionId=").append(questionId)
            append("&attemptId=").append(attemptId ?: -1L)
        }

    /** 分组列表页：sectionList/{bankId} */
    const val SECTION_LIST_PATTERN = "sectionList/{bankId}"
    // M-251：bankId 为路径段，编码后含 '/' 也能正确匹配 SECTION_LIST_PATTERN
    fun sectionList(bankId: String) = "sectionList/${Uri.encode(bankId)}"

    /** 练习小结页 */
    const val SUMMARY = "summary"

    /** EC-01 练习会话历史列表页 */
    const val SESSION_HISTORY = "sessionHistory"

    const val FORMULA_POC = "formula_poc" // debug-only PoC 页
}
