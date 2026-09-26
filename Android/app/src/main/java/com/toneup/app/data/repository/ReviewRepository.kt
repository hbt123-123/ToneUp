package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.ReviewApi
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.ReviewItemDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReviewRepository @Inject constructor(
    private val reviewApi: ReviewApi,
    private val jsonProvider: JsonProvider
) {
    suspend fun today(limit: Int = 20, subjectId: String? = null): PageData<ReviewItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { reviewApi.today(limit, subjectId) }

    /** 暂缓单题：默认顺延 1 天，跳过不改掌握度 */
    suspend fun skip(questionId: Long, bankId: String, nextReviewAt: String? = null) {
        // M-33：后端返回顺延后的 {question_id, bank_id, next_review_at} 非空载荷，
        // 由 unwrapUnit 改为 unwrap（顺带校验 data 非空）
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            reviewApi.skip(questionId, bankId, nextReviewAt)
        }
    }
}
