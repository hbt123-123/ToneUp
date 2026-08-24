package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StatsOverviewDto(
    @SerialName("accuracy_rate") val accuracyRate: Double = 0.0,
    @SerialName("total_attempts") val totalAttempts: Int = 0,
    @SerialName("correct_attempts") val correctAttempts: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("checked_today") val checkedToday: Boolean = false
)

@Serializable
data class WeaknessItemDto(
    val dimension: String = "type",
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("subject_name") val subjectName: String? = null,
    @SerialName("type_code") val typeCode: String? = null,
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("attempt_count") val attemptCount: Int = 0,
    @SerialName("accuracy_rate") val accuracyRate: Double = 0.0
)
