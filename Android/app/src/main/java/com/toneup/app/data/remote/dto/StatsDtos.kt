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

/** §6.11 每日趋势点（UTC 日历日分桶，去重作答数 + 正确率） */
@Serializable
data class DailyTrendPointDto(
    val date: String = "",
    val attempts: Int = 0,
    @SerialName("correct_rate") val correctRate: Double = 0.0
)

@Serializable
data class DailyTrendDataDto(
    val days: Int = 0,
    val points: List<DailyTrendPointDto> = emptyList()
)
