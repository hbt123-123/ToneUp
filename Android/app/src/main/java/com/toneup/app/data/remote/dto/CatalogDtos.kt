package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CatalogDto(
    val subjects: List<SubjectDto> = emptyList(),
    val banks: List<BankSummaryDto> = emptyList()
) {
    fun banksOf(subjectId: String, typeId: String): List<BankSummaryDto> =
        banks.filter { it.subjectId == subjectId && it.typeId == typeId }
}

@Serializable
data class SubjectDto(
    val id: String,
    val name: String,
    val icon: String? = null,
    val types: List<SubjectTypeDto> = emptyList()
)

/** 题库分类（如“真题”），非 question type_code */
@Serializable
data class SubjectTypeDto(
    val id: String,
    val name: String
)

@Serializable
data class BankSummaryDto(
    val id: String,
    @SerialName("subject_id") val subjectId: String,
    @SerialName("type_id") val typeId: String,
    val name: String,
    // M-41：后端 /api/catalog 恒返回 enabled；去掉 true 默认，禁用库漏发字段时快速失败而非误展示可练
    val enabled: Boolean
)

@Serializable
data class BankDetailDto(
    val id: String,
    val name: String,
    // M-40：后端 bank_detail 恒返回 subject_id（registry 保证非空），与 BankSummaryDto 统一为必填非空
    @SerialName("subject_id") val subjectId: String,
    @SerialName("year_min") val yearMin: Int? = null,
    @SerialName("year_max") val yearMax: Int? = null,
    val years: List<Int> = emptyList(),
    @SerialName("question_count") val questionCount: Int? = null,
    @SerialName("type_codes") val typeCodes: List<String> = emptyList()
)
