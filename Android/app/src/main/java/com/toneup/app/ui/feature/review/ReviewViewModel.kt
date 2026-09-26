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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
            } catch (e: CancellationException) {
                // M-222：取消必须显式重抛，避免吞掉 ViewModel 清理/刷新被顶替时的结构化取消
                throw e
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
            // M-223：改用 update 原子读改写，避免快速连续暂缓或与刷新交错时状态互相覆盖
            _state.update {
                it.copy(
                    skippingIds = it.skippingIds + item.questionId,
                    errorHint = null
                )
            }
            try {
                reviewRepository.skip(item.questionId, item.bankId)
                _state.update { prev ->
                    // H-67：refresh 进行中（items 为 Loading/Failed）时不得用空列表覆盖现有状态，
                    // 仅在 Ready 分支摘除本题；lastSkipped/skippingIds 无论如何都要更新
                    val current = prev.items
                    if (current is Load.Ready) {
                        prev.copy(
                            items = Load.Ready(current.value.filterNot { it.questionId == item.questionId }),
                            skippingIds = prev.skippingIds - item.questionId,
                            lastSkipped = item
                        )
                    } else {
                        prev.copy(
                            skippingIds = prev.skippingIds - item.questionId,
                            lastSkipped = item
                        )
                    }
                }
            } catch (e: CancellationException) {
                // M-224：runCatching 会捕获 CancellationException，改为显式重抛保证取消传播
                throw e
            } catch (_: Exception) {
                _state.update {
                    it.copy(
                        skippingIds = it.skippingIds - item.questionId,
                        errorHint = "暂缓失败，请检查网络后重试"
                    )
                }
            }
        }
    }

    /** 提示消费完毕后清除，避免重进 Tab 时 LaunchedEffect 重放旧的暂缓提示 */
    fun consumeSkipNotice() {
        _state.value = _state.value.copy(lastSkipped = null)
    }

    /** FR-RV-02 以复习模式进入刷题页 */
    fun startReview(onReady: (String) -> Unit) {
        val items = (_state.value.items as? Load.Ready)?.value ?: return
        val refs = items.map { QuestionRef(it.bankId, it.questionId) }
        // M-225：refs 为空时不注册会话也不导航，避免产生空 bankId、零题目的无效练习会话
        if (refs.isEmpty()) return
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
