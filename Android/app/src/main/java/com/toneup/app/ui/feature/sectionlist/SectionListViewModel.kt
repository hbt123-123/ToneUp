package com.toneup.app.ui.feature.sectionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.remote.dto.SectionItem
import com.toneup.app.data.remote.dto.SectionsResponse
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.SectionRepository
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 真题 tab（年份分组） */
enum class SectionTab(val label: String) {
    REAL_EXAM("真题"),
    TOPIC("专题"),
    ALL("全部")
}

/** 底部筛选 tab */
enum class FilterTab(val label: String) {
    ALL("全部"),
    UNDONE("未做"),
    WRONG("错题"),
    FAVORITE("收藏")
}

data class SectionListUiState(
    val bankId: String = "",
    val category: String = "",
    val sections: List<SectionItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val selectedTab: SectionTab = SectionTab.ALL,
    val filterTab: FilterTab = FilterTab.ALL
) {
    /** 按 tab + filter 过滤后的分组 */
    val filteredSections: List<SectionItem>
        get() {
            val byTab = when (selectedTab) {
                SectionTab.REAL_EXAM -> sections.filter { it.year != null }
                SectionTab.TOPIC -> sections.filter { it.year == null }
                SectionTab.ALL -> sections
            }
            return when (filterTab) {
                FilterTab.ALL -> byTab
                FilterTab.UNDONE -> byTab.filter { it.done == 0 }
                FilterTab.WRONG -> byTab.filter { it.wrong > 0 }
                FilterTab.FAVORITE -> byTab.filter { it.favorited > 0 }
            }
        }
}

@HiltViewModel
class SectionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sectionRepository: SectionRepository,
) : ViewModel() {

    val bankId: String = savedStateHandle.get<String>("bankId") ?: ""

    private val _state = MutableStateFlow(SectionListUiState(bankId = bankId))
    val state: StateFlow<SectionListUiState> = _state

    private var cachedResponse: SectionsResponse? = null

    init {
        loadSections()
    }

    private fun loadSections() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val response = sectionRepository.sections(bankId)
                cachedResponse = response
                _state.value = _state.value.copy(
                    sections = response.sections,
                    category = response.category,
                    isLoading = false,
                    error = null
                )
            } catch (e: AppException.Network) {
                // 离线时展示缓存
                val cached = cachedResponse
                if (cached != null) {
                    _state.value = _state.value.copy(
                        sections = cached.sections,
                        category = cached.category,
                        isLoading = false,
                        error = "当前无网络，显示缓存数据"
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "网络不可用，请检查网络后重试"
                    )
                }
            } catch (e: AppException) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.userMessage
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "加载失败"
                )
            }
        }
    }

    fun selectTab(tab: SectionTab) {
        _state.value = _state.value.copy(selectedTab = tab)
    }

    fun selectFilter(filter: FilterTab) {
        _state.value = _state.value.copy(filterTab = filter)
    }

    fun retry() {
        loadSections()
    }

    @VisibleForTesting
    internal constructor(
        savedStateHandle: SavedStateHandle,
        sectionRepository: SectionRepository
    ) : this(
        savedStateHandle = savedStateHandle,
        sectionRepository = sectionRepository,
        sessionManager = null,
        connectivityMonitor = null,
        sessionDataStoreManager = null
    )
}
