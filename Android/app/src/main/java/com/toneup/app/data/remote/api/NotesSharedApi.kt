package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface NotesSharedApi {
    @GET("api/questions/{questionId}/notes")
    suspend fun getNotes(
        @Path("questionId") questionId: Long,
        @Query("bank_id") bankId: String,
        @Query("scope") scope: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 20
    ): ApiEnvelope<NotesPage>

    @POST("api/notes/{noteId}/like")
    suspend fun likeNote(@Path("noteId") noteId: Long): ApiEnvelope<LikeResponse>

    @DELETE("api/notes/{noteId}/like")
    suspend fun unlikeNote(@Path("noteId") noteId: Long): ApiEnvelope<LikeResponse>
}

@Serializable
data class NotesPage(
    val items: List<SharedNoteDto> = emptyList(),
    val total: Int = 0
)

@Serializable
data class SharedNoteDto(
    // M-32：核心标识字段加保守默认值（空文本/0），载荷缺字段时不致反序列化崩溃
    @SerialName("note_id") val noteId: Long = 0,
    @SerialName("note_text") val noteText: String = "",
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("like_count") val likeCount: Int = 0,
    @SerialName("is_liked_by_me") val isLikedByMe: Boolean = false,
    val visibility: String = "public",
    @SerialName("created_at") val createdAt: String? = null
)

@Serializable
data class LikeResponse(
    val liked: Boolean,
    @SerialName("like_count") val likeCount: Int
)
