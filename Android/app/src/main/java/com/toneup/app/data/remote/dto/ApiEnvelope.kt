package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    val success: Boolean = false,
    val data: T? = null,
    val message: String? = null,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class PageData<T>(
    val items: List<T> = emptyList(),
    val total: Int = 0,
    @SerialName("has_more") val hasMore: Boolean = false
)
