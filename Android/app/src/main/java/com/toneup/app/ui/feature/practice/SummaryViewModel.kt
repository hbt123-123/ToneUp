package com.toneup.app.ui.feature.practice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

data class SummaryUiState(
    val totalCount: Int = 0,
    val answeredCount: Int = 0,
    val wrongCount: Int = 0,
    val correctRate: Int = 0,
    val formattedTime: String = "00:00:00"
)

@HiltViewModel
class SummaryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {

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

    private fun formatTime(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
