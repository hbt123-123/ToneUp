package com.toneup.app.ui.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.remote.dto.DailyTrendDataDto
import com.toneup.app.data.remote.dto.StatsOverviewDto
import com.toneup.app.data.remote.dto.WeaknessItemDto
import com.toneup.app.data.repository.CatalogRepository
import com.toneup.app.data.repository.StatsRepository
import com.toneup.app.ui.common.Load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StatsUiState(
    val overview: Load<StatsOverviewDto> = Load.Loading,
    val weaknesses: Load<List<WeaknessItemDto>> = Load.Loading,
    // H-73：FR-ST-05 刷题趋势接入真实 daily-trend 数据
    val dailyTrend: Load<DailyTrendDataDto> = Load.Loading,
    val rangeDays: Int? = 7,
    val subjectId: String? = null,
    val subjects: List<Pair<String, String>> = emptyList()
)

@HiltViewModel
class StatsViewModel @Inject constructor(
    private val statsRepository: StatsRepository,
    private val catalogRepository: CatalogRepository
) : ViewModel() {

    private val _state = MutableStateFlow(StatsUiState())
    val state: StateFlow<StatsUiState> = _state

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            runCatching { catalogRepository.catalog() }.onSuccess { dto ->
                _state.value = _state.value.copy(
                    subjects = dto.subjects.map { it.id to it.name }
                )
            }
        }
        load()
    }

    /** FR-ST-01 总览 + FR-ST-02 薄弱项；FR-ST-03 时间范围与学科筛选 */
    fun load(rangeDays: Int? = _state.value.rangeDays, subjectId: String? = _state.value.subjectId) {
        _state.value = _state.value.copy(rangeDays = rangeDays, subjectId = subjectId)
        // 取消上一次加载，避免快速切换筛选时旧响应后到覆盖新数据
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                overview = Load.Loading,
                weaknesses = Load.Loading,
                dailyTrend = Load.Loading
            )
            try {
                val overview = statsRepository.overview(rangeDays, subjectId?.takeIf { it.isNotBlank() })
                _state.value = _state.value.copy(overview = Load.Ready(overview))
            } catch (e: CancellationException) {
                // loadJob 切换时旧协程被取消：放行取消，避免旧任务把
                // 新任务刚写入的 Loading 状态覆盖为 Failed（C-8）
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(overview = Load.Failed(e.toMsg()))
            }
            try {
                val weaknesses = statsRepository.weaknesses(subjectId?.takeIf { it.isNotBlank() })
                _state.value = _state.value.copy(weaknesses = Load.Ready(weaknesses))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(weaknesses = Load.Failed(e.toMsg()))
            }
            try {
                // H-73：拉取每日趋势（后端 §6.11 daily-trend 契约）
                val trend = statsRepository.dailyTrend()
                _state.value = _state.value.copy(dailyTrend = Load.Ready(trend))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(dailyTrend = Load.Failed(e.toMsg()))
            }
        }
    }

    private fun Exception.toMsg(): String =
        (this as? com.toneup.app.data.repository.AppException)?.userMessage ?: "加载失败"
}
