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
 *
 * TODO(M-47)：单一可空字段模拟两种互斥形态、全部字段带默认值，属重型建模重构
 * （应拆 sealed class 并由后端下发形态标记），延后处理
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
    // M-48：后端真题/专题两形态均恒返回 collection_ids（EC-01 必需），理想是去掉默认值快速失败；
    // 但测试源集有 13 处 SectionItem(...) 构造依赖该默认值，为控制本批 diff 保留默认值：
    // 若后端漏发该字段，EC-01 会退化为按 count 随机抽题而非按分组选题，需留意
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
