package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ApiEnvelope<T>(
    // M-37：后端 envelope() 恒输出 success（含错误信封）；去掉 false 默认值，
    // 字段缺失时抛 MissingFieldException 快速失败，而非静默把成功响应当失败
    val success: Boolean,
    val data: T? = null,
    val message: String? = null,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class PageData<T>(
    // M-38：后端 page() 恒输出 items/total/has_more 三键；去掉静默回退，
    // 缺失/被 coerceInputValues 抹掉的异常载荷会显式失败而非呈现"空页"假象
    val items: List<T>,
    val total: Int,
    @SerialName("has_more") val hasMore: Boolean
)
