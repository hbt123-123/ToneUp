package com.toneup.app.ui.feature.wrongbook

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.remote.dto.CatalogDto
import com.toneup.app.data.remote.dto.WrongbookItemDto
import com.toneup.app.data.repository.CatalogRepository
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.WrongbookRepository
import com.toneup.app.ui.common.Load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class WrongbookUiState(
    val items: Load<List<WrongbookItemDto>> = Load.Loading,
    val catalog: CatalogDto? = null,
    val subjectId: String? = null,
    val bankId: String? = null
)

@HiltViewModel
class WrongbookViewModel @Inject constructor(
    private val wrongbookRepository: WrongbookRepository,
    private val catalogRepository: CatalogRepository,
    private val sessionRegistry: PracticeSessionRegistry
) : ViewModel() {

    private val _state = MutableStateFlow(WrongbookUiState())
    val state: StateFlow<WrongbookUiState> = _state

    private var refreshJob: Job? = null

    // M-242：上次"重做此题"点击时间戳（防抖基准）
    private var lastRedoAtMillis = 0L

    init {
        viewModelScope.launch {
            // M-243：catalog 写入与 refresh() 并发读改写同一 StateFlow，改用原子 update；
            // 同时替换原 runCatching（会吞 CancellationException）：失败留痕（UI 无错误槽位，
            // catalog 保持 null，学科筛选条仅显示"全部学科"），取消显式重抛
            try {
                val dto = catalogRepository.catalog()
                _state.update { it.copy(catalog = dto) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "catalog load failed", e)
            }
        }
        refresh()
    }

    /** FR-WB-01 汇总答错题目，按学科/题库筛选 */
    fun refresh(subjectId: String? = _state.value.subjectId, bankId: String? = _state.value.bankId) {
        // H-74：先取消在途刷新——用户快速切换筛选时，慢的旧请求晚到会把新筛选结果覆盖回去
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // M-243：与 init 的 catalog 协程并发写 _state，改用原子 update 防止读改写交错丢更新
            _state.update { it.copy(items = Load.Loading, subjectId = subjectId, bankId = bankId) }
            try {
                // M-244：循环拉取全部分页（后端 page_size 上限 100），总量 500 条保护，
                // 不再只取第一页 50 条而静默丢弃其余错题
                val collected = mutableListOf<WrongbookItemDto>()
                var page = 1
                var hasMore = true
                while (hasMore && collected.size < MAX_TOTAL_ITEMS) {
                    val pageData = wrongbookRepository.wrongbook(
                        bankId = bankId?.takeIf { it.isNotBlank() },
                        subjectId = subjectId?.takeIf { it.isNotBlank() },
                        page = page,
                        pageSize = PAGE_SIZE
                    )
                    collected += pageData.items
                    // 防御：空页但 hasMore 异常为 true 时跳出，避免死循环
                    if (pageData.items.isEmpty()) break
                    hasMore = pageData.hasMore
                    page++
                }
                _state.update { it.copy(items = Load.Ready(collected)) }
            } catch (e: CancellationException) {
                // H-75：取消必须传播，吞掉会把正常取消变成"加载失败"
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        items = Load.Failed(
                            (e as? com.toneup.app.data.repository.AppException)?.userMessage ?: "加载失败"
                        )
                    )
                }
            }
        }
    }

    /** FR-WB-03 重做此题：发起单题练习会话 */
    fun redo(item: WrongbookItemDto, onReady: (String) -> Unit) {
        // M-242：防抖守卫——快速双击只注册一次会话并导航一次，
        // 避免重复注册随机 sessionId 的会话并压入两层练习页
        val now = System.currentTimeMillis()
        if (now - lastRedoAtMillis < REDO_DEBOUNCE_MILLIS) return
        lastRedoAtMillis = now
        val sessionId = "wb_" + UUID.randomUUID().toString().take(8)
        sessionRegistry.register(
            PracticeSession(
                sessionId = sessionId,
                bankId = item.bankId,
                title = "重做错题",
                mode = PracticeSession.MODE_PRACTICE,
                fixedRefs = listOf(com.toneup.app.data.repository.QuestionRef(item.bankId, item.questionId))
            )
        )
        // M-245：注册后不清理——与全局会话生命周期语义保持一致：Review/Bank/
        // SessionHistory.resume 等入口注册后同样不 remove（唯一 remove 是删除服务端
        // 会话时的连带清理，见 SessionHistoryViewModel.delete），且练习页
        // PracticeViewModel 在构造时按 sessionId 从 registry 取会话，过早清理
        // 会导致练习页拿不到会话；会话生命周期统一治理见 registry 内 M-72 备注
        onReady(sessionId)
    }

    companion object {
        private const val TAG = "WrongbookViewModel"
        // M-244：与后端 page_size 上限（100）对齐；500 条为防御性总量上限
        private const val PAGE_SIZE = 100
        private const val MAX_TOTAL_ITEMS = 500
        // M-242：防抖窗口
        private const val REDO_DEBOUNCE_MILLIS = 500L
    }
}
