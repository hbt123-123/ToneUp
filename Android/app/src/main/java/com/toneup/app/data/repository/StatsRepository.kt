package com.toneup.app.data.repository

import com.toneup.app.data.remote.api.StatsApi
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
        val from = rangeDays?.let { LocalDate.now().minusDays(it.toLong() - 1).toString() }
        val to = rangeDays?.let { LocalDate.now().toString() }
        return EnvelopeUnwrapper.unwrap(jsonProvider.json) {
            statsApi.overview(from, to, subjectId)
        }
    }

    suspend fun weaknesses(subjectId: String? = null, limit: Int = 10): List<WeaknessItemDto> =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { statsApi.weaknesses(subjectId, limit) }
}
