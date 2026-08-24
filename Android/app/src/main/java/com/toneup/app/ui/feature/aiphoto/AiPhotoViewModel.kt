package com.toneup.app.ui.feature.aiphoto

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.AiFeedbackDetailResult
import com.toneup.app.data.repository.AiRepository
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.PracticeRepository
import com.toneup.app.domain.logic.PollBackoffPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

sealed interface AiFlowStep {
    data object Camera : AiFlowStep
    data class ConfirmPreview(val file: File) : AiFlowStep
    data object Uploading : AiFlowStep
    data object Polling : AiFlowStep
    data class Result(val outcome: AiFeedbackDetailResult) : AiFlowStep
    data class Failure(val message: String, val canRetryUpload: Boolean) : AiFlowStep
}

data class AiPhotoUiState(
    val step: AiFlowStep = AiFlowStep.Camera,
    val bankId: String,
    val questionId: Long,
    val attemptId: Long?,
    val pollElapsedSeconds: Int = 0,
    val busy: Boolean = false,
    val errorHint: String? = null
)

@HiltViewModel
class AiPhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val aiRepository: AiRepository,
    private val practiceRepository: PracticeRepository
) : ViewModel() {

    private val bankIdArg: String = savedStateHandle.get<String>("bankId") ?: ""
    private val questionIdArg: Long = savedStateHandle.get<String>("questionId")?.toLongOrNull() ?: -1L
    private val attemptIdArg: Long? =
        savedStateHandle.get<String>("attemptId")?.toLongOrNull()?.takeIf { it > 0 }

    private val _state = MutableStateFlow(
        AiPhotoUiState(bankId = bankIdArg, questionId = questionIdArg, attemptId = attemptIdArg)
    )
    val state: StateFlow<AiPhotoUiState> = _state

    private var pollJob: Job? = null
    private var lastUploadFile: File? = null
    private var lastCapturedFile: File? = null

    fun onCaptured(file: File) {
        lastCapturedFile = file
        _state.value = _state.value.copy(step = AiFlowStep.ConfirmPreview(file))
    }

    fun retake() {
        pollJob?.cancel()
        _state.value = _state.value.copy(step = AiFlowStep.Camera, errorHint = null)
    }

    /** FR-AI-02 压缩后 multipart 上传；小图可能同步返回结果 */
    fun confirmAndUpload(compressedFileProvider: suspend (File) -> File) {
        val raw = (_state.value.step as? AiFlowStep.ConfirmPreview)?.file ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, step = AiFlowStep.Uploading)
        viewModelScope.launch {
            try {
                val compressed = compressedFileProvider(raw)
                lastUploadFile = compressed
                when (val outcome = aiRepository.upload(
                    file = compressed,
                    bankId = bankIdArg,
                    questionId = questionIdArg,
                    attemptId = attemptIdArg
                )) {
                    is com.toneup.app.data.repository.AiUploadOutcome.Succeeded -> {
                        _state.value = _state.value.copy(
                            busy = false, step = AiFlowStep.Result(outcome.result)
                        )
                    }

                    is com.toneup.app.data.repository.AiUploadOutcome.Accepted -> {
                        _state.value = _state.value.copy(busy = false, step = AiFlowStep.Polling)
                        startPolling(outcome.feedbackId)
                    }
                }
            } catch (e: AppException.Network) {
                fail("网络不可用，上传失败", canRetryUpload = true)
            } catch (e: Exception) {
                fail((e as? AppException)?.userMessage ?: "上传失败", canRetryUpload = true)
            }
        }
    }

    /** FR-AI-03 轮询：2s 起指数退避至 5s，总上限 60s */
    private fun startPolling(feedbackId: String) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            var index = 0
            while (true) {
                if (PollBackoffPolicy.isDeadlineExceeded(System.currentTimeMillis() - startedAt)) {
                    fail("等待超时（60 秒）", canRetryUpload = true)
                    return@launch
                }
                delay(PollBackoffPolicy.delayForAttempt(index))
                index++
                try {
                    val detail = aiRepository.feedback(feedbackId)
                    when (detail.status) {
                        "succeeded" -> {
                            _state.value = _state.value.copy(
                                step = AiFlowStep.Result(
                                    AiFeedbackDetailResult(
                                        isCorrect = detail.isCorrect,
                                        score = detail.score,
                                        errorReason = detail.errorReason,
                                        tagIds = detail.tagIds
                                    )
                                )
                            )
                            return@launch
                        }

                        "failed" -> {
                            fail(detail.errorMessage ?: "AI 诊断失败", canRetryUpload = true)
                            return@launch
                        }
                        // queued / processing 继续轮询
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 网络抖动继续重试直至超时
                }
            }
        }
    }

    /** FR-AI-06 失败或超时：重试上传生成新诊断任务 */
    fun retryUpload() {
        val file = lastUploadFile ?: return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, step = AiFlowStep.Uploading, errorHint = null)
        viewModelScope.launch {
            try {
                when (val outcome = aiRepository.upload(
                    file = file, bankId = bankIdArg, questionId = questionIdArg, attemptId = attemptIdArg
                )) {
                    is com.toneup.app.data.repository.AiUploadOutcome.Succeeded ->
                        _state.value = _state.value.copy(busy = false, step = AiFlowStep.Result(outcome.result))

                    is com.toneup.app.data.repository.AiUploadOutcome.Accepted -> {
                        _state.value = _state.value.copy(busy = false, step = AiFlowStep.Polling)
                        startPolling(outcome.feedbackId)
                    }
                }
            } catch (e: Exception) {
                fail((e as? AppException)?.userMessage ?: "重试失败", canRetryUpload = true)
            }
        }
    }

    /** §10.4 自评兜底：走 POST /api/attempts（mode=self_judge），与 AI 数据互不覆盖 */
    fun submitSelfJudge(correct: Boolean) {
        if (bankIdArg.isBlank() || questionIdArg <= 0 || _state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            runCatching {
                practiceRepository.submitSelfJudge(bankIdArg, questionIdArg, correct, attemptIdArg)
            }.onSuccess {
                _state.value = _state.value.copy(busy = false, errorHint = null)
            }.onFailure { e ->
                _state.value = _state.value.copy(
                    busy = false,
                    errorHint = (e as? AppException)?.userMessage ?: "提交失败"
                )
            }
        }
    }

    private fun fail(message: String, canRetryUpload: Boolean) {
        _state.value = _state.value.copy(busy = false, step = AiFlowStep.Failure(message, canRetryUpload))
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        // 清理临时文件
        lastUploadFile?.let { if (it.exists()) it.delete() }
        lastCapturedFile?.let { if (it.exists()) it.delete() }
    }
}
