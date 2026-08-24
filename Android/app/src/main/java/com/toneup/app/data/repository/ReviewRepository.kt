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
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            reviewApi.skip(questionId, bankId, nextReviewAt)
        }
    }
}
