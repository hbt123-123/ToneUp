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
    val favorited: Boolean
)

@Serializable
data class FavoriteBanksResponse(
    val banks: List<FavoriteBankItem>
)

@Serializable
data class FavoriteBankItem(
    @SerialName("bank_id") val bankId: String,
    @SerialName("count") val count: Int
)
