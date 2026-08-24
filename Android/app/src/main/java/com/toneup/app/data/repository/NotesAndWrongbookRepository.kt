package com.toneup.app.data.repository

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
    suspend fun myNotes(page: Int, pageSize: Int = 20): PageData<NoteListItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { notesApi.myNotes(page, pageSize) }
}

/** 错题本（临时端点，待后端对齐） */
@Singleton
class WrongbookRepository @Inject constructor(
    private val wrongbookApi: com.toneup.app.data.remote.api.WrongbookApi,
    private val jsonProvider: JsonProvider
) {
    suspend fun wrongbook(
        subjectId: String?,
        typeCode: String?,
        page: Int,
        pageSize: Int = 20
    ): PageData<com.toneup.app.data.remote.dto.WrongbookItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            wrongbookApi.wrongbook(subjectId, typeCode, page, pageSize)
        }
}
