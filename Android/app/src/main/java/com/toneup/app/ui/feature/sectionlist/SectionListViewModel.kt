package com.toneup.app.ui.feature.sectionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.remote.dto.SectionItem
import com.toneup.app.data.remote.dto.SectionsResponse
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.SectionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
            // M-230：单次遍历同时应用 tab 与 filter 两个维度，替代原先最多三遍全量 filter 及中间列表分配
            return sections.filter { s ->
                val tabMatch = when (selectedTab) {
                    SectionTab.REAL_EXAM -> s.year != null
                    SectionTab.TOPIC -> s.year == null
                    SectionTab.ALL -> true
                }
                val filterMatch = when (filterTab) {
                    FilterTab.ALL -> true
                    FilterTab.UNDONE -> s.done == 0
                    FilterTab.WRONG -> s.wrong > 0
                    FilterTab.FAVORITE -> s.favorited > 0
                }
                tabMatch && filterMatch
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
    private var loadJob: Job? = null

    init {
        loadSections()
    }

    private fun loadSections() {
        // M-231：bankId 缺失/为空时直接进入错误态，不携带空串发起注定无效的请求（init 与 retry 均经此拦截）
        if (bankId.isBlank()) {
            _state.value = _state.value.copy(isLoading = false, error = "题库参数缺失，请返回重进")
            return
        }
        // H-69：快速重试时先取消在途请求，避免旧协程晚到的响应覆盖新一次加载的状态
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
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
                // M-232：cachedResponse 仅进程内、成功拉取后才有值，冷启动断网必未命中——
                // 未命中时明确提示「加载失败」；命中时说明数据为上次加载的旧数据，均避免「有离线缓存」的误导
                val cached = cachedResponse
                if (cached != null) {
                    _state.value = _state.value.copy(
                        sections = cached.sections,
                        category = cached.category,
                        isLoading = false,
                        error = "网络不可用，显示的是上次加载的数据"
                    )
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = "加载失败，请检查网络后重试"
                    )
                }
            } catch (e: AppException) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = e.userMessage
                )
            } catch (e: CancellationException) {
                // 结构化并发：ViewModel 清理/任务被顶替时必须放行取消（C-7）
                throw e
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
}
