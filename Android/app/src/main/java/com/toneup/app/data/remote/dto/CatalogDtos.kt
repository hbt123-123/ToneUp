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
    val enabled: Boolean = true
)

@Serializable
data class BankDetailDto(
    val id: String,
    val name: String,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("year_min") val yearMin: Int? = null,
    @SerialName("year_max") val yearMax: Int? = null,
    val years: List<Int> = emptyList(),
    @SerialName("question_count") val questionCount: Int? = null,
    @SerialName("type_codes") val typeCodes: List<String> = emptyList()
)
