package com.toneup.app.data.repository

import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.QuestionDto
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** 练习会话内的一题引用（复习模式可能跨库） */
data class QuestionRef(val bankId: String, val questionId: Long)

/**
 * 练习会话：刷题页全生命周期共享的题目装载上下文。
 * - practice 模式：按年份/题型分页拉取（[nextPage]/[hasMore] 推进）
 * - review 模式：固定 [fixedRefs]，逐题经详情接口补取
 */
class PracticeSession(
    val sessionId: String,
    val bankId: String,
    val title: String,
    val mode: String,
    val year: Int? = null,
    val typeCodeFilter: String? = null,
    val fixedRefs: List<QuestionRef>? = null
) {
    val questions = mutableListOf<QuestionDto>()
    var total: Int = if (fixedRefs != null) fixedRefs.size else Int.MAX_VALUE
        private set
    var hasMore: Boolean = true
        private set
    var nextPage: Int = 1
        private set

    fun append(page: PageData<QuestionDto>) {
        questions.addAll(page.items)
        hasMore = page.hasMore
        total = page.total
        nextPage++
    }

    fun appendOne(question: QuestionDto) {
        if (questions.none { it.questionId == question.questionId }) {
            questions.add(question)
        }
    }

    companion object {
        const val MODE_PRACTICE = "practice"
        const val MODE_REVIEW = "review"
        const val MODE_SELF_JUDGE = "self_judge"
    }
}

@Singleton
class PracticeSessionRegistry @Inject constructor() {
    private val sessions = ConcurrentHashMap<String, PracticeSession>()

    fun register(session: PracticeSession) {
        sessions[session.sessionId] = session
    }

    fun get(sessionId: String): PracticeSession? = sessions[sessionId]

    fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }
}
