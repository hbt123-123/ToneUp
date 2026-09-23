package com.toneup.app.ui.feature.practice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.ConnectivityMonitor
import com.toneup.app.data.local.DraftEntry
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.api.FavoriteRequest
import com.toneup.app.data.remote.api.FavoritesApi
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.PracticeRepository
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.data.repository.QuestionRepository
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.domain.logic.CorrectAnswerParser
import com.toneup.app.domain.logic.PracticeEvent
import com.toneup.app.domain.logic.PracticeStateMachine
import com.toneup.app.domain.logic.PracticeStatus
import com.toneup.app.domain.logic.ReciteMode
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.domain.model.QuestionType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PaperStats(
    val totalCount: Int,
    val answeredCount: Int,
    val unansweredCount: Int,
    val markedCount: Int,
    val wrongCount: Int = 0
)

/** 每题的 UI 快照 */
data class QuestionSlot(
    val question: QuestionDto? = null,
    val status: PracticeStatus = PracticeStatus.Loading,
    val answer: AnswerValue? = null,
    val marked: Boolean = false,
    /** 主观题提交后的判分子状态 */
    val gradingStatus: String? = null,
    val errorHint: String? = null
)

data class PracticeUiState(
    val sessionId: String = "",
    val title: String = "",
    val mode: String = PracticeSession.MODE_PRACTICE,
    val slots: List<QuestionSlot> = emptyList(),
    val currentIndex: Int = 0,
    val knownTotal: Int = -1,
    val hasMore: Boolean = false,
    val pendingSyncCount: Int = 0,
    val favoritedIds: Set<Long> = emptySet()
) {
    val answeredCount: Int
        get() = slots.count {
            it.status is PracticeStatus.Submitted || it.answer?.isEmpty == false
        }
}

@HiltViewModel
class PracticeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessionRegistry: PracticeSessionRegistry,
    private val questionRepository: QuestionRepository,
    private val practiceRepository: PracticeRepository,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val sessionManager: SessionManager,
    private val connectivityMonitor: ConnectivityMonitor,
    private val favoritesApi: FavoritesApi
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: ""
    val modeArg: String =
        savedStateHandle.get<String>("mode") ?: PracticeSession.MODE_PRACTICE

    private val session: PracticeSession? = sessionRegistry.get(sessionId)

    private val _state = MutableStateFlow(PracticeUiState(sessionId = sessionId))
    val state: StateFlow<PracticeUiState> = _state

    /** 提交耗时计时：题目成为当前题时启动 */
    private var questionShownAtMs: Long = System.currentTimeMillis()

    /** 按题维护防抖 Job：切题时上一题的待写草稿仍会独立落盘 */
    private val draftFlushJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()
    private val essayFlushJobs = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    private val _elapsedSeconds = MutableStateFlow(0)
    val elapsedSeconds: StateFlow<Int> = _elapsedSeconds
    private var timerJob: Job? = null

    private val _isFavorited = MutableStateFlow(false)
    val isFavorited: StateFlow<Boolean> = _isFavorited

    /** 会话级背题开关（EC-02）：开启后零 attempts 上报、零错题/掌握度写入；仅内存态，退出即失效，不持久化 */
    private val _showAnswer = MutableStateFlow(false)
    val showAnswer: StateFlow<Boolean> = _showAnswer

    init {
        val size = session?.let { s ->
            if (s.fixedRefs != null) s.fixedRefs.size.coerceAtLeast(1) else 1
        } ?: 1
        _state.value = _state.value.copy(
            title = session?.title ?: "",
            mode = session?.mode ?: modeArg,
            slots = List(size) { QuestionSlot() },
            knownTotal = session?.fixedRefs?.size ?: -1,
            hasMore = session?.hasMore ?: false
        )
        loadFavoritedIds()
        observeConnectivity()
        refreshPending()
        loadQuestion(0)
        timerJob = viewModelScope.launch {
            while (true) {
                delay(1000)
                _elapsedSeconds.value++
            }
        }
    }

    /** 槽位列表按需扩容 */
    private fun growSlots(minSize: Int) {
        val current = _state.value.slots
        if (current.size >= minSize) return
        val grown = current + List(minSize - current.size) { QuestionSlot() }
        _state.value = _state.value.copy(slots = grown)
    }

    fun sessionBankId(): String = session?.bankId ?: ""

    private fun observeConnectivity() {
        viewModelScope.launch {
            connectivityMonitor.onlineFlow.collect { online ->
                if (online) {
                    runCatching { practiceRepository.replayPendingQueue() }
                    refreshPending()
                }
            }
        }
    }

    private fun refreshPending() {
        viewModelScope.launch {
            runCatching { practiceRepository.refreshPendingCount() }
            _state.value = _state.value.copy(pendingSyncCount = practiceRepository.pendingCount.value)
        }
    }

    private fun loadFavoritedIds() {
        viewModelScope.launch {
            val userId = sessionManager.currentUserId() ?: return@launch
            runCatching {
                val data = sessionDataStoreManager.storeFor(userId).data.first()
                val ids = data.markedKeys.mapNotNull { key ->
                    key.substringAfterLast(":").toLongOrNull()
                }.toSet()
                _state.value = _state.value.copy(favoritedIds = ids)
            }
        }
    }

    // ---------- 题目装载与预取 ----------

    /** 进入第 N 题；同时静默预取 N±1（FR-PR-08，§8.5） */
    fun loadQuestion(index: Int, goTo: Boolean = true) {
        val s = session ?: return
        if (index < 0) return
        if (index != _state.value.currentIndex && goTo) {
            questionShownAtMs = System.currentTimeMillis()
        }
        if (goTo) {
            // 同步扩容，避免 currentIndex 越过已装载页时 UI 因取不到 slot 短暂空白
            growSlots(index + 1)
            _state.value = _state.value.copy(currentIndex = index)
        }
        viewModelScope.launch { ensureSlot(index) }
        viewModelScope.launch {
            val q = slotAt(index)?.question
            if (q != null) {
                _isFavorited.value = _state.value.favoritedIds.contains(q.questionId)
            }
        }
        // 相邻预取：不越界、失败静默
        if (index + 1 < slotCount()) {
            viewModelScope.launch { runCatching { ensureSlot(index + 1) } }
        }
    }

    private fun slotCount(): Int = _state.value.slots.size

    private suspend fun ensureSlot(index: Int) {
        val s = session ?: return
        if (slotAt(index)?.question != null) return

        // EC-01 服务端会话：题目序列创建时已返回并预填，直接取用
        if (s.serverSessionId != null) {
            val q = synchronized(s) { s.questions.getOrNull(index) } ?: run {
                setStatus(index, PracticeStatus.Error("没有更多题目", isNetwork = false))
                return
            }
            hydrateSlot(index, q)
            return
        }

        // 练习模式分页装载
        if (s.fixedRefs == null) {
            while (s.questions.size <= index && s.hasMore) {
                try {
                    val page = questionRepository.questions(
                        bankId = s.bankId,
                        year = s.year,
                        typeCode = s.typeCodeFilter,
                        page = s.nextPage
                    )
                    synchronized(s) { s.append(page) }
                } catch (_: Exception) {
                    setStatus(index, PracticeStatus.Error("题目加载失败", isNetwork = true))
                    return
                }
            }
            growSlots(s.questions.size.coerceAtLeast(index + 1))
            _state.value = _state.value.copy(knownTotal = s.total, hasMore = s.hasMore)
            val q = s.questions.getOrNull(index) ?: run {
                setStatus(index, PracticeStatus.Error("没有更多题目", isNetwork = false))
                return
            }
            hydrateSlot(index, q)
        } else {
            // 复习模式固定引用，逐题详情补取
            val ref: QuestionRef = s.fixedRefs.getOrNull(index) ?: return
            try {
                val q = questionRepository.questionDetail(ref.bankId, ref.questionId)
                synchronized(s) { s.appendOne(q) }
                hydrateSlot(slotIndexFor(q.questionId, index), q)
            } catch (e: Exception) {
                setStatus(index, PracticeStatus.Error(e.toLoadMessage(), isNetwork = e is AppException.Network))
            }
        }
    }

    private fun slotIndexFor(questionId: Long, fallback: Int): Int =
        session?.questions?.indexOfFirst { it.questionId == questionId }?.takeIf { it >= 0 }
            ?: fallback

    private fun slotAt(index: Int): QuestionSlot? = _state.value.slots.getOrNull(index)

    /** 当前题的题目（toggleFavorite 等副操作消费） */
    private fun currentQuestion(): QuestionDto? = slotAt(_state.value.currentIndex)?.question

    private suspend fun hydrateSlot(index: Int, question: QuestionDto) {
        val userId = sessionManager.currentUserId()
        val draftAnswer = userId?.let {
            runCatching {
                sessionDataStoreManager.storeFor(it).data.first().drafts
                    .firstOrNull { d -> d.bankId == question.bankId && d.questionId == question.questionId }
            }.getOrNull()?.answer
        }
        val restored = draftAnswer?.let { AnswerCodec.decode(it) }
        updateSlot(index) {
            it.copy(
                question = question,
                status = PracticeStatus.Idle,
                answer = restored,
                errorHint = null
            )
        }
        // 恢复在途幂等键（断网队列）
        userId?.let {
            runCatching { practiceRepository.restorePendingState(it) }
        }
    }

    private fun setStatus(index: Int, status: PracticeStatus) {
        updateSlot(index) { it.copy(status = status) }
    }

    private fun updateSlot(index: Int, transform: (QuestionSlot) -> QuestionSlot) {
        val slots = _state.value.slots.toMutableList()
        val current = slots.getOrNull(index) ?: return
        slots[index] = transform(current)
        _state.value = _state.value.copy(slots = slots)
    }

    fun retryLoad(index: Int) {
        updateSlot(index) { it.copy(status = PracticeStatus.Loading) }
        viewModelScope.launch { ensureSlot(index) }
    }

    fun retrySubmit(index: Int) {
        dispatch(index, PracticeEvent.RetryClicked)
        submitCurrent(index)
    }

    fun redoQuestion() {
        val index = _state.value.currentIndex
        val slot = slotAt(index) ?: return
        val question = slot.question ?: return
        updateSlot(index) {
            it.copy(
                answer = null,
                status = PracticeStatus.Idle,
                gradingStatus = null,
                errorHint = null,
                marked = false
            )
        }
        viewModelScope.launch {
            clearDraft(question.bankId, question.questionId)
        }
    }

    fun toggleFavorite() {
        val newFavorited = !_isFavorited.value
        val bankId = sessionBankId()
        val questionId = currentQuestion()?.questionId ?: return
        _state.value = _state.value.copy(
            favoritedIds = if (newFavorited) {
                _state.value.favoritedIds + questionId
            } else {
                _state.value.favoritedIds - questionId
            }
        )
        _isFavorited.value = newFavorited
        viewModelScope.launch {
            try {
                val body = FavoriteRequest(bankId = bankId, questionId = questionId)
                if (newFavorited) {
                    favoritesApi.addFavorite(body)
                } else {
                    favoritesApi.removeFavorite(body)
                }
            } catch (_: Exception) {
                _isFavorited.value = !newFavorited
                _state.value = _state.value.copy(
                    favoritedIds = if (newFavorited) {
                        _state.value.favoritedIds - questionId
                    } else {
                        _state.value.favoritedIds + questionId
                    }
                )
            }
        }
    }

    /** 切换会话级背题模式；背题中 submitCurrent 直接触发 return 守卫 */
    fun toggleAnswerMode() {
        _showAnswer.value = !_showAnswer.value
    }

    fun submitPaperStats(): PaperStats {
        val slots = _state.value.slots
        val total = _state.value.knownTotal.coerceAtLeast(slots.size)
        val answered = slots.count { slot ->
            slot.answer != null && !slot.answer.isEmpty
        }
        val wrong = slots.count { slot ->
            slot.question != null && slot.answer != null && !slot.answer.isEmpty &&
                !isSlotCorrect(slot)
        }
        return PaperStats(
            totalCount = total,
            answeredCount = answered,
            unansweredCount = total - answered,
            markedCount = slots.count { it.marked },
            wrongCount = wrong
        )
    }

    private fun isSlotCorrect(slot: QuestionSlot): Boolean {
        val question = slot.question ?: return false
        val answer = slot.answer ?: return false
        val myLabels = when (answer) {
            is AnswerValue.Choice -> listOf(answer.label)
            is AnswerValue.MultiChoice -> answer.labels
            else -> return false
        }
        if (myLabels.isEmpty()) return false
        val correctLabels = when (question.typeCode) {
            QuestionType.Multi.typeCode -> CorrectAnswerParser.multiLabels(question.answerText).toSet()
            else -> setOfNotNull(CorrectAnswerParser.singleLabel(question.answerText))
        }
        return myLabels.all { it in correctLabels } && correctLabels.all { it in myLabels }
    }

    // ---------- 作答与草稿 ----------

    fun onAnswerChange(index: Int, answer: AnswerValue) {
        val slot = slotAt(index) ?: return
        if (!slot.status.canEdit) return
        val changed = answer.isEmpty != (slot.answer?.isEmpty ?: true) ||
            answer != slot.answer
        dispatch(index, PracticeEvent.AnswerChanged(changed))
        updateSlot(index) { it.copy(answer = answer) }
        scheduleDraftWrite(index, answer)
    }

    private fun dispatch(index: Int, event: PracticeEvent) {
        val slots = _state.value.slots.toMutableList()
        val slot = slots.getOrNull(index) ?: return
        slots[index] = slot.copy(
            status = PracticeStateMachine.reduce(slot.status, event),
            errorHint = null
        )
        _state.value = _state.value.copy(slots = slots)
    }

    /**
     * FR-PR-06 草稿实时写入 Proto DataStore：
     * 常规防抖 500ms；ESSAY 更激进——变更防抖 500ms 且每 3 秒兜底落盘。
     */
    private fun scheduleDraftWrite(index: Int, answer: AnswerValue) {
        val question = slotAt(index)?.question ?: return
        draftFlushJobs.remove(question.questionId)?.cancel()
        draftFlushJobs[question.questionId] = viewModelScope.launch {
            delay(DRAFT_DEBOUNCE_MS)
            writeDraft(question, answer)
        }
        if (question.typeCode == QuestionDto.TYPE_ESSAY) {
            essayFlushJobs.remove(question.questionId)?.cancel()
            essayFlushJobs[question.questionId] = viewModelScope.launch {
                delay(ESSAY_FLUSH_INTERVAL_MS)
                writeDraft(question, answer)
            }
        }
    }

    private suspend fun writeDraft(question: QuestionDto, answer: AnswerValue) {
        val userId = sessionManager.currentUserId() ?: return
        val store = sessionDataStoreManager.storeFor(userId)
        store.updateData { data ->
            data.copy(
                drafts = data.drafts.filterNot {
                    it.bankId == question.bankId && it.questionId == question.questionId
                } + DraftEntry(
                    userId = userId,
                    bankId = question.bankId,
                    questionId = question.questionId,
                    answer = AnswerCodec.encode(answer, question.typeCode),
                    updatedAtMillis = System.currentTimeMillis()
                )
            )
        }
    }

    private suspend fun clearDraft(bankId: String, questionId: Long) {
        val userId = sessionManager.currentUserId() ?: return
        sessionDataStoreManager.storeFor(userId).updateData { data ->
            data.copy(drafts = data.drafts.filterNot {
                it.bankId == bankId && it.questionId == questionId
            })
        }
    }

    // ---------- 提交 ----------

    fun submitCurrent(index: Int) {
        if (!ReciteMode.canSubmit(recite = _showAnswer.value)) return
        val slot = slotAt(index) ?: return
        val question = slot.question ?: return
        val answer = slot.answer ?: return
        dispatch(index, PracticeEvent.SubmitClicked)
        // 恢复草稿的题停留在 Idle（状态机无 Idle→Submitting 转移），显式置位以禁用按钮并支持失败重试
        if (slotAt(index)?.status == PracticeStatus.Idle) {
            setStatus(index, PracticeStatus.Submitting)
        }
        viewModelScope.launch {
            try {
                val elapsedSeconds =
                    ((System.currentTimeMillis() - questionShownAtMs) / 1000).toInt().coerceAtLeast(1)
                val result = practiceRepository.submit(
                    bankId = question.bankId,
                    questionId = question.questionId,
                    answerJson = AnswerCodec.encode(answer, question.typeCode),
                    timeSpentSeconds = elapsedSeconds,
                    mode = _state.value.mode.ifBlank { PracticeSession.MODE_PRACTICE }
                )
                dispatch(index, PracticeEvent.SubmitSucceeded(result.attemptId))
                updateSlot(index) {
                    it.copy(
                        status = PracticeStatus.Submitted(result.attemptId),
                        gradingStatus = result.gradingStatus,
                        errorHint = null
                    )
                }
                // 先取消已排期的 ESSAY 兜底落盘，避免 clearDraft 后又被重新写入
                essayFlushJobs.remove(question.questionId)?.cancel()
                clearDraft(question.bankId, question.questionId)
            } catch (e: AppException.Network) {
                dispatch(index, PracticeEvent.SubmitNetworkFailed("网络不可用，答案已保存待同步"))
                updateSlot(index) { it.copy(errorHint = "已加入待同步队列") }
                refreshPending()
            } catch (e: AppException) {
                dispatch(index, PracticeEvent.SubmitRejected(e.userMessage))
                updateSlot(index) { it.copy(errorHint = e.userMessage) }
            } catch (e: Exception) {
                dispatch(index, PracticeEvent.SubmitRejected("提交失败"))
                updateSlot(index) { it.copy(errorHint = "提交失败，请重试") }
            }
        }
    }

    // ---------- 标记 ----------

    fun toggleMark(index: Int) {
        val slot = slotAt(index) ?: return
        val question = slot.question ?: return
        val next = !slot.marked
        updateSlot(index) { it.copy(marked = next) }
        viewModelScope.launch {
            val userId = sessionManager.currentUserId() ?: return@launch
            val key = "${question.bankId}:${question.questionId}"
            sessionDataStoreManager.storeFor(userId).updateData { data ->
                data.copy(
                    markedKeys =
                        if (next) (data.markedKeys + key).distinct()
                        else data.markedKeys - key
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        draftFlushJobs.values.forEach { it.cancel() }
        essayFlushJobs.values.forEach { it.cancel() }
    }

    companion object {
        const val DRAFT_DEBOUNCE_MS = 500L
        const val ESSAY_FLUSH_INTERVAL_MS = 3000L

        private fun Exception.toLoadMessage(): String =
            (this as? AppException)?.userMessage ?: "加载失败"
    }
}
