package com.toneup.app.data.remote.api

import com.toneup.app.data.remote.dto.ApiEnvelope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PUT

interface FavoritesApi {
    @PUT("api/favorites")
    suspend fun addFavorite(@Body body: FavoriteRequest): ApiEnvelope<FavoriteResponse>

    // M-28：后端 favorites.py 的 DELETE /api/favorites 要求 Body(...)（必填），无法改为 query；
    // DELETE 携带 body 属非标准 HTTP，个别代理/网关可能丢弃 body，跨网关部署时需实测验证
    @DELETE("api/favorites")
    suspend fun removeFavorite(@Body body: FavoriteRequest): ApiEnvelope<FavoriteResponse>

    @GET("api/favorites/banks")
    suspend fun listFavoriteBanks(): ApiEnvelope<FavoriteBanksResponse>
}

@Serializable
data class FavoriteRequest(
    @SerialName("bank_id") val bankId: String,
    @SerialName("question_id") val questionId: Long
)

@Serializable
data class FavoriteResponse(
    // M-29：加保守默认 false（未收藏），后端缺字段/返回 null 时不致反序列化崩溃
    val favorited: Boolean = false
)

@Serializable
data class FavoriteBanksResponse(
    // M-30：加保守默认空列表，后端缺 banks 字段或空载荷时回退为空页而非崩溃
    val banks: List<FavoriteBankItem> = emptyList()
)

@Serializable
data class FavoriteBankItem(
    @SerialName("bank_id") val bankId: String,
    @SerialName("favorite_count") val count: Int
)
