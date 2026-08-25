package com.toneup.app.ui.feature.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.data.repository.ReviewRepository
import com.toneup.app.data.remote.dto.ReviewItemDto
import com.toneup.app.ui.common.Load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ReviewUiState(
    val items: Load<List<ReviewItemDto>> = Load.Loading,
    val skippingIds: Set<Long> = emptySet(),
    /** 最近一次暂缓成功（仅用于短暂提示；服务端已顺延且无撤销端点，不可撤销） */
    val lastSkipped: ReviewItemDto? = null,
    val errorHint: String? = null
)

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val reviewRepository: ReviewRepository,
    private val sessionRegistry: PracticeSessionRegistry
) : ViewModel() {

    private val _state = MutableStateFlow(ReviewUiState())
    val state: StateFlow<ReviewUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(items = Load.Loading)
            try {
                val page = reviewRepository.today(limit = 50)
                _state.value = _state.value.copy(items = Load.Ready(page.items))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    items = Load.Failed((e as? com.toneup.app.data.repository.AppException)?.userMessage ?: "加载失败")
                )
            }
        }
    }

    /** FR-RV-03 暂缓单题：服务端顺延 1 天；无撤销端点，暂缓后不可撤销 */
    fun skip(item: ReviewItemDto) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                skippingIds = _state.value.skippingIds + item.questionId,
                errorHint = null
            )
            runCatching { reviewRepository.skip(item.questionId, item.bankId) }
                .onSuccess {
                    val current = (_state.value.items as? Load.Ready)?.value ?: emptyList()
                    _state.value = _state.value.copy(
                        items = Load.Ready(current.filterNot { it.questionId == item.questionId }),
                        skippingIds = _state.value.skippingIds - item.questionId,
                        lastSkipped = item
                    )
                }
                .onFailure {
                    _state.value = _state.value.copy(
                        skippingIds = _state.value.skippingIds - item.questionId,
                        errorHint = "暂缓失败，请检查网络后重试"
                    )
                }
        }
    }

    /** FR-RV-02 以复习模式进入刷题页 */
    fun startReview(onReady: (String) -> Unit) {
        val items = (_state.value.items as? Load.Ready)?.value ?: return
        val refs = items.map { QuestionRef(it.bankId, it.questionId) }
        val sessionId = "rv_" + UUID.randomUUID().toString().take(8)
        sessionRegistry.register(
            PracticeSession(
                sessionId = sessionId,
                bankId = refs.firstOrNull()?.bankId ?: "",
                title = "今日复习",
                mode = PracticeSession.MODE_REVIEW,
                fixedRefs = refs
            )
        )
        onReady(sessionId)
    }
}
