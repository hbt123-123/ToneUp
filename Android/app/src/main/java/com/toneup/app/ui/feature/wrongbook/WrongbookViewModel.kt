package com.toneup.app.ui.feature.wrongbook

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class WrongbookUiState(
    val items: Load<List<WrongbookItemDto>> = Load.Loading,
    val catalog: CatalogDto? = null,
    val subjectId: String? = null,
    val typeCode: String? = null
)

@HiltViewModel
class WrongbookViewModel @Inject constructor(
    private val wrongbookRepository: WrongbookRepository,
    private val catalogRepository: CatalogRepository,
    private val sessionRegistry: PracticeSessionRegistry
) : ViewModel() {

    private val _state = MutableStateFlow(WrongbookUiState())
    val state: StateFlow<WrongbookUiState> = _state

    init {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                catalog = runCatching { catalogRepository.catalog() }.getOrNull()
            )
        }
        refresh()
    }

    /** FR-WB-01 汇总答错题目，按学科/题型筛选 */
    fun refresh(subjectId: String? = _state.value.subjectId, typeCode: String? = _state.value.typeCode) {
        viewModelScope.launch {
            _state.value = _state.value.copy(items = Load.Loading, subjectId = subjectId, typeCode = typeCode)
            try {
                val page = wrongbookRepository.wrongbook(
                    subjectId = subjectId?.takeIf { it.isNotBlank() },
                    typeCode = typeCode,
                    page = 1,
                    pageSize = 50
                )
                _state.value = _state.value.copy(items = Load.Ready(page.items))
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    items = Load.Failed((e as? com.toneup.app.data.repository.AppException)?.userMessage ?: "加载失败")
                )
            }
        }
    }

    /** FR-WB-03 重做此题：发起单题练习会话 */
    fun redo(item: WrongbookItemDto, onReady: (String) -> Unit) {
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
        onReady(sessionId)
    }
}
