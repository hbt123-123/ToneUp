package com.toneup.app.ui.feature.practice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SummaryUiState(
    val totalCount: Int = 0,
    val answeredCount: Int = 0,
    val wrongCount: Int = 0,
    val correctRate: Int = 0,
    val formattedTime: String = "00:00:00",
    /** EC-01：非空表示数据来自服务端会话 result */
    val serverBacked: Boolean = false
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    /** 服务端会话 summary 覆盖本地估算（服务端会话交卷后拉取） */
    private val _serverSummary = MutableStateFlow<SummaryUiState?>(null)
    val serverSummary: StateFlow<SummaryUiState?> = _serverSummary

    fun buildState(stats: PaperStats, elapsedSeconds: Int): SummaryUiState {
        val rate = if (stats.answeredCount > 0) {
            ((stats.answeredCount - stats.wrongCount) * 100) / stats.answeredCount
        } else 0
        return SummaryUiState(
            totalCount = stats.totalCount,
            answeredCount = stats.answeredCount,
            wrongCount = stats.wrongCount,
            correctRate = rate,
            formattedTime = formatTime(elapsedSeconds)
        )
    }

    /**
     * EC-01：服务端会话（serverSessionId 非空）拉取 GET /{sid}/result，
     * 用服务端 summary（总/答/对/正确率/用时）覆盖本地估算；失败静默保留本地数据。
     */
    fun loadServerResult(serverSessionId: Long) {
        viewModelScope.launch {
            try {
                val result = sessionRepository.sessionResult(serverSessionId)
                val s = result.summary
                _serverSummary.value = SummaryUiState(
                    totalCount = s.total,
                    answeredCount = s.answered,
                    wrongCount = (s.answered - s.correct).coerceAtLeast(0),
                    // accuracyRate 是 0..1 的分数（契约 §6.10），先乘 100 再取整，
                    // 直接 toInt() 会把 0.83 截断为 0%（C-6）
                    correctRate = (s.accuracyRate * 100).roundToInt().coerceIn(0, 100),
                    formattedTime = formatTime(s.elapsedSeconds),
                    serverBacked = true
                )
            } catch (_: AppException) {
                // 静默：保留本地估算
            } catch (_: Exception) {
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
