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
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.data.repository.SectionRepository
import com.toneup.app.data.repository.SessionRepository
import com.toneup.app.data.repository.StatsRepository
import com.toneup.app.data.repository.AppException
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.common.toLoadMessage
import com.toneup.app.ui.components.charts.DailyTrendPoint
import com.toneup.app.ui.components.charts.TopicProgressItem
import com.toneup.app.ui.components.charts.toChartPoint
import com.toneup.app.ui.components.charts.toProgressItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    // H-15：后端 overview 无 checked_today 字段，移除假数据
    val lastContext: LastPracticeContext? = null,
    val catalog: Load<CatalogDto> = Load.Loading,
    /** EC-03 首页仪表盘：近 14 天趋势（失败静默 → 空 = 占位） */
    val trend: List<DailyTrendPoint> = emptyList(),
    /** 最近练习 bank 的专题进度前 5（无最近 bank / 失败静默 → 空 = 占位） */
    val topicProgress: List<TopicProgressItem> = emptyList(),
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
    val typeDistribution: List<TypeDistributionItem> = emptyList(),
    val creating: Boolean = false,
    val error: String? = null
)

data class TypeDistributionItem(
    val typeCode: String,
    val label: String,
    val count: Int
)

@HiltViewModel
class BankViewModel @Inject constructor(
    private val catalogRepository: CatalogRepository,
    private val statsRepository: StatsRepository,
    private val sectionRepository: SectionRepository,
    // M-161：移除未使用的 questionRepository/jsonProvider 注入
    private val sessionRegistry: PracticeSessionRegistry,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val sessionManager: SessionManager,
    private val sessionRepository: SessionRepository
) : ViewModel() {

    private val _home = MutableStateFlow(HomeUiState())
    val home: StateFlow<HomeUiState> = _home

    private val _picker = MutableStateFlow(PickerUiState())
    val picker: StateFlow<PickerUiState> = _picker

    init {
        refreshHome(forceRefreshCatalog = false)
    }

    private var homeRefreshJob: Job? = null

    /** FR-HM-05 下拉刷新期间保留旧内容 */
    fun refreshHome(forceRefreshCatalog: Boolean) {
        // H-48：重入守卫——init/openPicker/下拉刷新并发触发只保留一轮在飞，
        // 避免多协程交错写 _home；强制刷新时以新一轮为准
        val existing = homeRefreshJob
        if (existing?.isActive == true) {
            if (!forceRefreshCatalog) return
            existing.cancel()
        }
        homeRefreshJob = viewModelScope.launch {
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
                    // EC-03 首页仪表盘：趋势不带 subject_id；sections 取最近练习 bank，失败静默占位
                    val trend = async {
                        runCatching { statsRepository.dailyTrend(14) }
                    }
                    val sections = async {
                        val bankId = lastCtx?.bankId
                        when {
                            bankId == null -> null
                            else -> runCatching { sectionRepository.sections(bankId) }.getOrNull()
                        }
                    }
                    overview.await().onSuccess { stats ->
                        _home.value = _home.value.copy(
                            streakDays = stats.streakDays
                        )
                    }
                    catalog.await().onSuccess { dto ->
                        _home.value = _home.value.copy(catalog = Load.Ready(dto))
                    }.onFailure { e ->
                        if (_home.value.catalog !is Load.Ready) {
                            _home.value = _home.value.copy(catalog = Load.Failed(e.toLoadMessage()))
                        }
                    }
                    trend.await().onSuccess { data ->
                        _home.value = _home.value.copy(trend = data.points.map { it.toChartPoint() })
                    }.onFailure { /* 静默：图表以占位呈现 */ }
                    sections.await()?.let { resp ->
                        _home.value = _home.value.copy(
                            topicProgress = resp.sections.take(5).map { it.toProgressItem() }
                        )
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
                yearsLoading = false, yearsError = null, typeDistribution = emptyList()
            )
            return
        }
        _picker.value = _picker.value.copy(bankId = bankId, year = null, yearsLoading = true, yearsError = null, typeDistribution = emptyList())
        viewModelScope.launch {
            try {
                val detail = catalogRepository.bankDetail(bankId)
                // H-49：过期响应丢弃——用户已切换题库或回退到根时不得覆盖当前选择
                if (_picker.value.bankId != bankId) return@launch
                // M-158：distinct() 去重，避免重复年份导致 LazyColumn key 冲突
                val years = detail.years.sortedDescending().distinct().ifEmpty {
                    val (minY, maxY) = detail.yearMin to detail.yearMax
                    if (minY != null && maxY != null) (minY..maxY).toList() else emptyList()
                }
                val typeDist = detail.typeDistribution.map { item ->
                    TypeDistributionItem(
                        typeCode = item.typeCode,
                        label = item.label ?: item.typeCode,
                        count = item.count ?: 0
                    )
                }
                _picker.value = _picker.value.copy(years = years, yearsLoading = false, typeDistribution = typeDist)
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
        // M-162：直接用 updateData 的返回值更新 _home，避免二次读盘与读改写竞态
        val updated = store.updateData { data ->
            data.copy(
                lastContext = LastPracticeContext(
                    userId = userId,
                    bankId = session.bankId,
                    sessionId = session.sessionId,
                    questionIndex = index,
                    title = session.title,
                    year = session.year,
                    typeCode = session.typeCodeFilter,
                    serverSessionId = session.serverSessionId,
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
        _home.value = _home.value.copy(lastContext = updated.lastContext)
    }

    /** FR-HM-02 继续上次刷题（EC-01：恢复到上次题号；服务端会话走 GET detail 重建） */
    fun continueLastPractice(onReady: (sessionId: String, index: Int) -> Unit) {
        val ctx = _home.value.lastContext ?: return
        val existing = sessionRegistry.get(ctx.sessionId)
        if (existing != null) {
            onReady(ctx.sessionId, ctx.questionIndex)
        } else {
            viewModelScope.launch {
                try {
                    // M-163：rebuildSession 返回 degraded 标记，服务端失败降级本地时给用户可见提示
                    val (session, degraded) = rebuildSession(ctx)
                    sessionRegistry.register(session)
                    _home.value = _home.value.copy(
                        errorHint = if (degraded) "服务端会话恢复失败，已切换为本地模式继续" else null
                    )
                    onReady(session.sessionId, ctx.questionIndex)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _home.value = _home.value.copy(errorHint = "继续刷题失败，请重新选题")
                }
            }
        }
    }

    /**
     * EC-01 会话重建：服务端会话（serverSessionId 非空）优先走 GET detail 拉题目+草稿，
     * 失败（离线/服务端异常）回退本地分页装载路径。
     * M-163：返回 (会话, 是否发生服务端→本地的降级)。
     */
    private suspend fun rebuildSession(ctx: LastPracticeContext): Pair<PracticeSession, Boolean> {
        val sid = ctx.serverSessionId
        if (sid != null) {
            try {
                val d = sessionRepository.sessionDetail(sid)
                val session = PracticeSession(
                    sessionId = ctx.sessionId,
                    bankId = d.session.bankId,
                    title = d.session.title.ifBlank { ctx.title ?: "继续刷题" },
                    mode = PracticeSession.MODE_PRACTICE,
                    fixedRefs = d.questions.map { QuestionRef(d.session.bankId, it.questionId) },
                    serverSessionId = d.session.id,
                    restoredDraft = d.session.draft
                )
                session.appendAll(d.questions)
                return session to false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // M-163：落入本地重建路径，degraded = true 由下方返回值标记
            }
        }
        return PracticeSession(
            sessionId = ctx.sessionId,
            bankId = ctx.bankId,
            title = ctx.title ?: "继续刷题",
            mode = PracticeSession.MODE_PRACTICE,
            year = ctx.year,
            typeCodeFilter = ctx.typeCode
        ) to (sid != null)
    }

    companion object {
        fun encodeDraftAnswer(answer: com.toneup.app.domain.model.AnswerValue, typeCode: String) =
            AnswerCodec.encode(answer, typeCode)
    }
}
