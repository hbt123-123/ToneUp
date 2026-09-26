package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.WrongbookApi
import com.toneup.app.data.remote.api.WrongQuestionApi
import com.toneup.app.data.remote.api.NotesApi
import com.toneup.app.data.remote.dto.NoteDto
import com.toneup.app.data.remote.dto.NoteListItemDto
import com.toneup.app.data.remote.dto.NotePutRequest
import com.toneup.app.data.remote.dto.PageData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotesRepository @Inject constructor(
    private val notesApi: NotesApi,
    private val jsonProvider: JsonProvider
) {
    companion object {
        // M-67：默认页大小集中定义，消除与 WrongbookRepository 重复的魔法数字 20
        const val DEFAULT_PAGE_SIZE = 20
    }

    suspend fun note(bankId: String, questionId: Long): NoteDto? =
        try {
            EnvelopeUnwrapper.unwrap(jsonProvider.json) {
                notesApi.note(questionId, bankId)
            }
        } catch (e: AppException.NotFound) {
            null // 尚无笔记
        }

    suspend fun saveNote(bankId: String, questionId: Long, noteText: String): NoteDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            notesApi.putNote(questionId, NotePutRequest(bankId, noteText))
        }

    /** 我的笔记聚合列表（临时端点，待后端对齐） */
    suspend fun myNotes(page: Int, pageSize: Int = DEFAULT_PAGE_SIZE): PageData<NoteListItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { notesApi.myNotes(page, pageSize) }
}

/** 错题本 */
@Singleton
class WrongbookRepository @Inject constructor(
    private val wrongbookApi: com.toneup.app.data.remote.api.WrongbookApi,
    private val wrongQuestionApi: com.toneup.app.data.remote.api.WrongQuestionApi,
    private val jsonProvider: JsonProvider
) {
    companion object {
        // M-67：默认页大小集中定义（与 NotesRepository.DEFAULT_PAGE_SIZE 同值，分属两个 API 域）
        const val DEFAULT_PAGE_SIZE = 20
    }

    suspend fun wrongbook(
        bankId: String?,
        subjectId: String?,
        page: Int,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): PageData<com.toneup.app.data.remote.dto.WrongbookItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            wrongbookApi.wrongbook(bankId, subjectId, page, pageSize)
        }

    suspend fun removeWrongQuestion(id: Long) {
        EnvelopeUnwrapper.unwrapUnit(jsonProvider.json) {
            wrongQuestionApi.removeWrongQuestion(id)
        }
    }
}
