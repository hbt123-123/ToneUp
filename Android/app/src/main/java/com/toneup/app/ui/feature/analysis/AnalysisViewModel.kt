package com.toneup.app.ui.feature.analysis

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.api.AttemptApi
import com.toneup.app.data.remote.dto.AttemptResultDto
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.AttemptResultCache
import com.toneup.app.data.repository.NotesRepository
import com.toneup.app.data.repository.PracticeRepository
import com.toneup.app.data.repository.QuestionRepository
import com.toneup.app.data.remote.dto.NoteDto
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.logic.AnswerCodec
import com.toneup.app.domain.logic.PollBackoffPolicy
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.common.Load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalysisUiState(
    val attempt: Load<AttemptResultDto> = Load.Loading,
    val question: QuestionDto? = null,
    /** H-41：本地草稿格式化后的“我的答案”，null 表示无本地作答记录 */
    val myAnswerText: String? = null,
    /** 主观题判分子状态 */
    val gradingStatus: String? = null,
    val pollTimedOut: Boolean = false,
    val noteText: String = "",
    val noteDirty: Boolean = false,
    val noteSavedAtHint: String? = null,
    val selfJudgeBusy: Boolean = false,
    val errorHint: String? = null
)

@HiltViewModel
class AnalysisViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val attemptApi: AttemptApi,
    private val jsonProvider: com.toneup.app.data.repository.JsonProvider,
    private val resultCache: AttemptResultCache,
    private val questionRepository: QuestionRepository,
    private val notesRepository: NotesRepository,
    private val practiceRepository: PracticeRepository,
    private val sessionManager: SessionManager,
    private val sessionDataStoreManager: SessionDataStoreManager
) : ViewModel() {

    val attemptId: Long = savedStateHandle.get<String>("attemptId")?.toLongOrNull() ?: -1L

    private val _state = MutableStateFlow(AnalysisUiState())
    val state: StateFlow<AnalysisUiState> = _state

    private var pollJob: Job? = null

    init {
        load()
    }

    fun load() {
        // M-143：参数无效（缺失/不可解析回退 -1）时不发起加载，直接展示错误态
        if (attemptId <= 0L) {
            _state.value = _state.value.copy(attempt = Load.Failed("无效的练习记录"))
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(attempt = Load.Loading)
            try {
                // FR-AN-03 解析数据来源：优先缓存，否则 GET 补取；不信任本地判定
                val cached = resultCache.byAttemptId(attemptId)
                val result = cached ?: practiceRepository.fetchAttempt(attemptId)
                applyResult(result)
                loadQuestion(result)
                startPollingIfPending()
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    attempt = Load.Failed((e as? AppException)?.userMessage ?: "加载失败")
                )
            }
        }
    }

    private suspend fun loadQuestion(result: AttemptResultDto) {
        val bankId = result.bankId
        val questionId = result.questionId
        if (bankId != null && questionId != null) {
            runCatching { questionRepository.questionDetail(bankId, questionId) }
                .onSuccess { q -> _state.value = _state.value.copy(question = q) }
            // H-41：FR-AN-01/02 答案对比需要用户作答——服务端不回显，从本地草稿取
            runCatching { loadMyAnswer(bankId, questionId) }
        }
        loadNote(bankId, questionId)
    }

    private suspend fun loadMyAnswer(bankId: String, questionId: Long) {
        val userId = sessionManager.currentUserId() ?: return
        val draft = sessionDataStoreManager.storeFor(userId).data.first().drafts
            .firstOrNull { it.bankId == bankId && it.questionId == questionId } ?: return
        val decoded = AnswerCodec.decode(draft.answer) ?: return
        _state.value = _state.value.copy(myAnswerText = formatAnswer(decoded))
    }

    private fun formatAnswer(answer: AnswerValue): String = when (answer) {
        is AnswerValue.Choice -> answer.label
        is AnswerValue.MultiChoice -> answer.labels.joinToString("、")
        is AnswerValue.Text -> answer.text
        is AnswerValue.Blanks -> answer.values.entries.sortedBy { it.key }
            .joinToString("；") { it.value.ifBlank { "（空）" } }
        is AnswerValue.BlankLabels -> answer.values.entries.sortedBy { it.key }
            .joinToString("；") { it.value.ifBlank { "（空）" } }
        is AnswerValue.Order -> answer.ids.joinToString(" → ")
    }

    /** FR-AN-04 判分状态卡：queued/processing 轮询，指数退避 2s→5s 上限 60s */
    private fun startPollingIfPending() {
        val current = (_state.value.attempt as? Load.Ready)?.value ?: return
        // M-144：仅"主观题待判分"时自动轮询；判分失败不自动重试，
        // 由状态卡上的"重试查询/自评"入口显式触发（原 else 分支为死代码）
        if (current.isSubjectivePending) startPolling()
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var attemptIndex = 0
            val startedAt = System.currentTimeMillis()
            try {
                while (true) {
                    if (PollBackoffPolicy.isDeadlineExceeded(System.currentTimeMillis() - startedAt)) {
                        _state.value = _state.value.copy(pollTimedOut = true)
                        return@launch
                    }
                    // M-88 联动：带截止感知的延迟，睡醒后不越过总 deadline
                    delay(
                        PollBackoffPolicy.delayForAttempt(
                            attemptIndex,
                            System.currentTimeMillis() - startedAt
                        )
                    )
                    attemptIndex++
                    val latest = practiceRepository.fetchAttempt(attemptId)
                    applyResult(latest)
                    if (!latest.isSubjectivePending) return@launch
                }
            } catch (e: CancellationException) {
                throw e // H-42：取消不得吞掉，保持结构化并发
            } catch (_: Exception) {
                // M-145：先确认 job 未被取消，避免被 retryPolling 取消的旧 job
                // 在收尾时把刚重置的 pollTimedOut 又改回 true
                ensureActive()
                _state.value = _state.value.copy(pollTimedOut = true)
            }
        }
    }

    fun retryPolling() {
        _state.value = _state.value.copy(pollTimedOut = false)
        startPolling()
    }

    /** FR-AI-06 / §10.4 自评兜底：mode=self_judge 提交 */
    fun submitSelfJudge(selfCorrect: Boolean) {
        val ready = (_state.value.attempt as? Load.Ready)?.value ?: return
        val bankId = ready.bankId ?: return
        val questionId = ready.questionId ?: return
        if (_state.value.selfJudgeBusy) return
        _state.value = _state.value.copy(selfJudgeBusy = true)
        viewModelScope.launch {
            try {
                val result = practiceRepository.submitSelfJudge(
                    bankId = bankId,
                    questionId = questionId,
                    selfCorrect = selfCorrect,
                    originalAttemptId = attemptId.takeIf { it > 0 }
                )
                resultCache.update(result)
                applyResult(result)
                _state.value = _state.value.copy(selfJudgeBusy = false)
            } catch (e: AppException) {
                _state.value = _state.value.copy(selfJudgeBusy = false, errorHint = e.userMessage)
            } catch (e: Exception) {
                _state.value = _state.value.copy(selfJudgeBusy = false, errorHint = "提交失败")
            }
        }
    }

    private fun applyResult(result: AttemptResultDto) {
        _state.value = _state.value.copy(
            attempt = Load.Ready(result),
            gradingStatus = result.gradingStatus ?: result.feedback?.status
        )
    }

    // ---------- 笔记（FR-AN-05） ----------

    private suspend fun loadNote(bankId: String?, questionId: Long?) {
        bankId ?: return
        questionId ?: return
        runCatching { notesRepository.note(bankId, questionId) }.onSuccess { note ->
            _state.value = _state.value.copy(noteText = note?.noteText ?: "", noteDirty = false)
        }
    }

    fun onNoteChange(text: String) {
        _state.value = _state.value.copy(noteText = text, noteDirty = true)
    }

    fun saveNote(onSaved: () -> Unit = {}) {
        val ready = (_state.value.attempt as? Load.Ready)?.value ?: return
        val bankId = ready.bankId ?: return
        val questionId = ready.questionId ?: return
        // M-146：协程启动前捕获文本快照，保存期间用户继续编辑不影响本次提交内容
        val textSnapshot = _state.value.noteText
        viewModelScope.launch {
            try {
                notesRepository.saveNote(bankId, questionId, textSnapshot)
                _state.value = _state.value.copy(noteDirty = false, noteSavedAtHint = "已保存")
                onSaved()
            } catch (e: AppException) {
                _state.value = _state.value.copy(errorHint = e.userMessage)
            } catch (_: Exception) {
                _state.value = _state.value.copy(errorHint = "笔记保存失败")
            }
        }
    }

    /** 返回拦截：未保存离开提示（§9.2） */
    fun hasUnsavedNote(): Boolean = _state.value.noteDirty

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }
}
