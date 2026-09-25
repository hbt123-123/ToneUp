package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.serialization.Serializable
import retrofit2.http.DELETE
import retrofit2.http.Path

/** H-13：后端 DELETE /wrong-questions/{id} 的 data 载荷是 {"id": ..., "deleted": true} */
@Serializable
data class WrongQuestionDeleteDto(
    val id: Long? = null,
    val deleted: Boolean = false
)

interface WrongQuestionApi {
    // H-13：原 ApiEnvelope<Unit> 与 data 对象载荷不匹配，kotlinx 解析非空对象到 Unit 会失败
    @DELETE("api/wrong-questions/{id}")
    suspend fun removeWrongQuestion(@Path("id") id: Long): ApiEnvelope<WrongQuestionDeleteDto>
}
