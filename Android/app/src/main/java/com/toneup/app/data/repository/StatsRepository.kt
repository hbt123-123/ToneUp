package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.StatsApi
import com.toneup.app.data.remote.dto.DailyTrendDataDto
import com.toneup.app.data.remote.dto.StatsOverviewDto
import com.toneup.app.data.remote.dto.WeaknessItemDto
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatsRepository @Inject constructor(
    private val statsApi: StatsApi,
    private val jsonProvider: JsonProvider
) {
    suspend fun overview(
        rangeDays: Int? = null,
        subjectId: String? = null
    ): StatsOverviewDto {
        // M-73：now() 只取一次，避免两次求值跨午夜导致 from/to 区间撕裂；
        // rangeDays 非正数视为未传，回退服务端默认全量区间
        val today = LocalDate.now()
        val validDays = rangeDays?.takeIf { it > 0 }
        val from = validDays?.let { today.minusDays(it.toLong() - 1).toString() }
        val to = validDays?.let { today.toString() }
        return EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            statsApi.overview(from, to, subjectId)
        }
    }

    suspend fun weaknesses(subjectId: String? = null, limit: Int = 10): List<WeaknessItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { statsApi.weaknesses(subjectId, limit) }

    suspend fun dailyTrend(days: Int = 14): DailyTrendDataDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { statsApi.dailyTrend(days) }
}
