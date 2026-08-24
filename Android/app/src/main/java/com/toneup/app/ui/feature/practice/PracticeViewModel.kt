package com.toneup.app.ui.feature.practice

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.ConnectivityMonitor
import com.toneup.app.data.local.DraftEntry
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.PracticeRepository
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.data.repository.QuestionRepository
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.domain.logic.PracticeEvent
import com.toneup.app.domain.logic.PracticeStateMachine
import com.toneup.app.domain.logic.PracticeStatus
import com.toneup.app.domain.model.AnswerValue
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val restoredDraftHint: Boolean = false
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
    private val connectivityMonitor: ConnectivityMonitor
) : ViewModel() {

    val sessionId: String = savedStateHandle.get<String>("sessionId") ?: ""
    val modeArg: String =
        savedStateHandle.get<String>("mode") ?: PracticeSession.MODE_PRACTICE

    private val session: PracticeSession? = sessionRegistry.get(sessionId)

    private val _state = MutableStateFlow(PracticeUiState(sessionId = sessionId))
    val state: StateFlow<PracticeUiState> = _state

    /** 提交耗时计时：题目成为当前题时启动 */
    private var questionShownAtMs: Long = System.currentTimeMillis()

    private var draftJob: Job? = null
    private val essayFlushJobs = mutableMapOf<Long, Job>()

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
        observeConnectivity()
        refreshPending()
        loadQuestion(0)
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

    // ---------- 题目装载与预取 ----------

    /** 进入第 N 题；同时静默预取 N±1（FR-PR-08，§8.5） */
    fun loadQuestion(index: Int, goTo: Boolean = true) {
        val s = session ?: return
        if (index < 0) return
        if (index != _state.value.currentIndex && goTo) {
            questionShownAtMs = System.currentTimeMillis()
        }
        if (goTo) {
            _state.value = _state.value.copy(currentIndex = index)
        }
        viewModelScope.launch { ensureSlot(index) }
        // 相邻预取：不越界、失败静默
        if (index + 1 < slotCount()) {
            viewModelScope.launch { runCatching { ensureSlot(index + 1) } }
        }
    }

    private fun slotCount(): Int = _state.value.slots.size

    private suspend fun ensureSlot(index: Int) {
        val s = session ?: return
        if (slotAt(index)?.question != null) return

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
        _state.value = _state.value.copy(restoredDraftHint = restored != null)
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
        draftJob?.cancel()
        draftJob = viewModelScope.launch {
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
        val slot = slotAt(index) ?: return
        val question = slot.question ?: return
        val answer = slot.answer ?: return
        dispatch(index, PracticeEvent.SubmitClicked)
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

    fun consumeDraftHint(): Boolean {
        val had = _state.value.restoredDraftHint
        _state.value = _state.value.copy(restoredDraftHint = false)
        return had
    }

    override fun onCleared() {
        super.onCleared()
        draftJob?.cancel()
        essayFlushJobs.values.forEach { it.cancel() }
    }

    companion object {
        const val DRAFT_DEBOUNCE_MS = 500L
        const val ESSAY_FLUSH_INTERVAL_MS = 3000L

        private fun Exception.toLoadMessage(): String =
            (this as? AppException)?.userMessage ?: "加载失败"
    }
}
