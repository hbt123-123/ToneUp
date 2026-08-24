package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.QuestionApi
import com.toneup.app.data.remote.dto.PageData
import com.toneup.app.data.remote.dto.QuestionDto
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QuestionRepository @Inject constructor(
    private val questionApi: QuestionApi,
    private val jsonProvider: JsonProvider
) {
    suspend fun questions(
        bankId: String,
        year: Int?,
        typeCode: String?,
        page: Int,
        pageSize: Int = PAGE_SIZE
    ): PageData<QuestionDto> = EnvelopeUnwrapper.unwrap(jsonProvider.json) {
        questionApi.questions(bankId, year, typeCode, page, pageSize)
    }

    suspend fun questionDetail(bankId: String, questionId: Long): QuestionDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            questionApi.questionDetail(bankId, questionId)
        }

    companion object {
        const val PAGE_SIZE = 20
    }
}
