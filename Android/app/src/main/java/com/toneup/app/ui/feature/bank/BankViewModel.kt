package com.toneup.app.ui.feature.bank

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.LastPracticeContext
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.repository.CatalogRepository
import com.toneup.app.data.remote.dto.CatalogDto
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.StatsRepository
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.QuestionRepository
import com.toneup.app.data.repository.JsonProvider
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.common.toLoadMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HomeUiState(
    val streakDays: Int = 0,
    val checkedToday: Boolean = false,
    val lastContext: LastPracticeContext? = null,
    val catalog: Load<CatalogDto> = Load.Loading,
    val refreshing: Boolean = false,
    val errorHint: String? = null
)

/** 选题 Sheet 三级联动状态：学科 → 题型(题库分类) → 年份 */
data class PickerUiState(
    val visible: Boolean = false,
    val presetSubjectId: String? = null,
    val subjectId: String? = null,
    val typeId: String? = null,
    val bankId: String? = null,
    val year: Int? = null,
    val typeCodeFilter: String? = null,
    val years: List<Int> = emptyList(),
    val yearsLoading: Boolean = false,
    val yearsError: String? = null,
    val creating: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BankViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val statsRepository: StatsRepository,
    private val questionRepository: QuestionRepository,
    private val sessionRegistry: PracticeSessionRegistry,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val sessionManager: SessionManager,
    private val jsonProvider: JsonProvider
) : ViewModel() {

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home

    private val _picker = MutableStateFlow(PickerUiState())
    val picker: StateFlow<PickerUiState> = _picker

    init {
        refreshHome(forceRefreshCatalog = false)
    }

    /** FR-HM-05 下拉刷新期间保留旧内容 */
    fun refreshHome(forceRefreshCatalog: Boolean) {
        viewModelScope.launch {
            _home.value = _home.value.copy(refreshing = true)
            try {
                val userId = sessionManager.currentUserId()
                val lastCtx = userId?.let {
                    runCatching {
                        sessionDataStoreManager.storeFor(it).data.first().lastContext
                    }.getOrNull()
                }
                coroutineScope {
                    val overview = async {
                        runCatching { statsRepository.overview() }
                    }
                    val catalog = async {
                        runCatching { catalogRepository.catalog(forceRefreshCatalog) }
                    }
                    overview.await().onSuccess { stats ->
                        _home.value = _home.value.copy(
                            streakDays = stats.streakDays,
                            checkedToday = stats.checkedToday
                        )
                    }
                    catalog.await().onSuccess { dto ->
                        _home.value = _home.value.copy(catalog = Load.Ready(dto))
                    }.onFailure { e ->
                        if (_home.value.catalog !is Load.Ready) {
                            _home.value = _home.value.copy(catalog = Load.Failed(e.toLoadMessage()))
                        }
                    }
                }
                _home.value = _home.value.copy(lastContext = lastCtx, refreshing = false)
            } catch (e: Exception) {
                _home.value = _home.value.copy(refreshing = false)
            }
        }
    }

    // ---------- 选题 Sheet ----------

    fun openPicker(presetSubjectId: String?) {
        _picker.value = PickerUiState(visible = true, presetSubjectId = presetSubjectId)
        if (_home.value.catalog is Load.Failed || _home.value.catalog is Load.Loading) {
            refreshHome(false)
        }
        presetSubjectId?.let { selectSubject(it) }
    }

    fun closePicker() {
        // FR-BS-03 关闭不丢失已选路径（保留在内存，下次打开恢复）
        _picker.value = _picker.value.copy(visible = false)
    }

    /** 传 null 表示回退到根节点（FR-BS-03 面包屑任意一级回退） */
    fun selectSubject(subjectId: String?) {
        _picker.value = _picker.value.copy(subjectId = subjectId, typeId = null, bankId = null, year = null)
    }

    /** 传 null 表示回退到学科层 */
    fun selectType(typeId: String?) {
        _picker.value = _picker.value.copy(typeId = typeId, bankId = null, year = null)
    }

    /** 传 null 表示回退到题库层（不重新拉取年份） */
    fun selectBank(bankId: String?) {
        if (bankId == null) {
            _picker.value = _picker.value.copy(
                bankId = null, year = null, years = emptyList(),
                yearsLoading = false, yearsError = null
            )
            return
        }
        _picker.value = _picker.value.copy(bankId = bankId, year = null, yearsLoading = true, yearsError = null)
        viewModelScope.launch {
            try {
                val detail = catalogRepository.bankDetail(bankId)
                val years = detail.years.sortedDescending().ifEmpty {
                    val (minY, maxY) = detail.yearMin to detail.yearMax
                    if (minY != null && maxY != null) (minY..maxY).toList() else emptyList()
                }
                _picker.value = _picker.value.copy(years = years, yearsLoading = false)
            } catch (e: AppException) {
                _picker.value = _picker.value.copy(yearsLoading = false, yearsError = e.userMessage)
            } catch (e: Exception) {
                _picker.value = _picker.value.copy(yearsLoading = false, yearsError = "年份加载失败")
            }
        }
    }

    fun selectYear(year: Int) {
        _picker.value = _picker.value.copy(year = year)
    }

    fun setTypeCodeFilter(code: String?) {
        _picker.value = _picker.value.copy(typeCodeFilter = code)
    }

    /** FR-BS-04：创建练习会话并返回 sessionId */
    fun startPractice(onReady: (String) -> Unit) {
        val p = _picker.value
        val bankId = p.bankId ?: return
        if (p.creating) return
        _picker.value = p.copy(creating = true, error = null)
        viewModelScope.launch {
            try {
                val bankName = catalogRepository.bankDetail(bankId).name
                val sessionId = "s_" + UUID.randomUUID().toString().take(8)
                val session = PracticeSession(
                    sessionId = sessionId,
                    bankId = bankId,
                    title = buildString {
                        append(bankName)
                        p.year?.let { append(" $it") }
                        p.typeCodeFilter?.let { append(" · $it") }
                    },
                    mode = PracticeSession.MODE_PRACTICE,
                    year = p.year,
                    typeCodeFilter = p.typeCodeFilter
                )
                sessionRegistry.register(session)
                // 首页上下文更新：继续上次刷题入口
                saveLastContext(session, index = 0)
                _picker.value = _picker.value.copy(creating = false, visible = false)
                onReady(sessionId)
            } catch (e: AppException) {
                _picker.value = _picker.value.copy(creating = false, error = e.userMessage)
            } catch (e: Exception) {
                _picker.value = _picker.value.copy(creating = false, error = "会话创建失败")
            }
        }
    }

    suspend fun saveLastContext(session: PracticeSession, index: Int) {
        val userId = sessionManager.currentUserId() ?: return
        val store = sessionDataStoreManager.storeFor(userId)
        store.updateData { data ->
            data.copy(
                lastContext = LastPracticeContext(
                    userId = userId,
                    bankId = session.bankId,
                    sessionId = session.sessionId,
                    questionIndex = index,
                    title = session.title,
                    year = session.year,
                    typeCode = session.typeCodeFilter,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        _home.value = _home.value.copy(lastContext = store.data.first().lastContext)
    }

    /** FR-HM-02 继续上次刷题 */
    fun continueLastPractice(onReady: (String) -> Unit) {
        val ctx = _home.value.lastContext ?: return
        val existing = sessionRegistry.get(ctx.sessionId)
        if (existing != null) {
            onReady(ctx.sessionId)
        } else {
            viewModelScope.launch {
                try {
                    val session = PracticeSession(
                        sessionId = ctx.sessionId,
                        bankId = ctx.bankId,
                        title = ctx.title ?: "继续刷题",
                        mode = PracticeSession.MODE_PRACTICE,
                        year = ctx.year,
                        typeCodeFilter = ctx.typeCode
                    )
                    sessionRegistry.register(session)
                    _home.value = _home.value.copy(errorHint = null)
                    onReady(ctx.sessionId)
                } catch (_: Exception) {
                    _home.value = _home.value.copy(errorHint = "继续刷题失败，请重新选题")
                }
            }
        }
    }

    companion object {
        fun encodeDraftAnswer(answer: com.toneup.app.domain.model.AnswerValue, typeCode: String) =
            AnswerCodec.encode(answer, typeCode)
    }
}
