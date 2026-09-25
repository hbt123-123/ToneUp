package com.toneup.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StatsOverviewDto(
    // H-14：后端 /api/stats/overview 的正确率键是 `accuracy`（见 backend/app/api/stats.py），
    // 原来的 accuracy_rate 永远落默认值 0.0
    val accuracy: Double = 0.0,
    @SerialName("total_attempts") val totalAttempts: Int = 0,
    @SerialName("correct_attempts") val correctAttempts: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0
)
// H-15：后端不返回 checked_today 字段，移除该假数据（UI 不再展示无法判定的"今日已打卡"）

@Serializable
data class WeaknessItemDto(
    // H-16：对齐 /api/stats/weaknesses 实际载荷 {dimension, subject_id, key, attempts, accuracy, wrong_rate}
    val dimension: String = "type",
    @SerialName("subject_id") val subjectId: String? = null,
    /** 展示键：dimension=type 时为题型码，dimension=tag 时为知识点名 */
    val key: String = "",
    val attempts: Int = 0,
    val accuracy: Double = 0.0,
    @SerialName("wrong_rate") val wrongRate: Double = 0.0
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
