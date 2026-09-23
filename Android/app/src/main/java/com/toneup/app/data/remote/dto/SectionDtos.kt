package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 题库分组响应（§3.2 / §4.3） */
@Serializable
data class SectionsResponse(
    @SerialName("bank_id") val bankId: String = "",
    val category: String = "",
    val sections: List<SectionItem> = emptyList()
)

/**
 * 分组条目：真题（year + types）或 专题（title + 统计）
 *
 * 序列化时由后端决定返回哪组字段；此处用默认值兼容两种形态。
 */
@Serializable
data class SectionItem(
    val year: Int? = null,
    val title: String? = null,
    val total: Int = 0,
    val done: Int = 0,
    val wrong: Int = 0,
    val favorited: Int = 0,
    /** EC-01 选题：该分组对应的 collections.id（真题=同年集合、专题=同名集合），服务端会话创建用 */
    @SerialName("collection_ids") val collectionIds: List<Long> = emptyList(),
    val types: List<TypeStat> = emptyList()
)

@Serializable
data class TypeStat(
    @SerialName("type_code") val typeCode: String = "",
    @SerialName("type_name") val typeName: String = "",
    val total: Int = 0,
    val done: Int = 0,
    val wrong: Int = 0,
    val favorited: Int = 0
)
