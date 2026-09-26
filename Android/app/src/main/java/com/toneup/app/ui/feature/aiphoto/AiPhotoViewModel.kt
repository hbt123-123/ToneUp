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
import java.io.IOException
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
        // M-129：覆盖引用前删除上一张临时拍摄文件，避免 cacheDir 泄漏
        lastCapturedFile?.let { old ->
            if (old.exists() && old.absolutePath != file.absolutePath) old.delete()
        }
        lastCapturedFile = file
        _state.value = _state.value.copy(step = AiFlowStep.ConfirmPreview(file))
    }

    fun retake() {
        pollJob?.cancel()
        // M-129：重拍即丢弃当前拍摄文件，同步删除对应临时文件
        lastCapturedFile?.let { if (it.exists()) it.delete() }
        lastCapturedFile = null
        _state.value = _state.value.copy(step = AiFlowStep.Camera, errorHint = null)
    }

    /** FR-AI-02 压缩后 multipart 上传；小图可能同步返回结果 */
    fun confirmAndUpload(compressedFileProvider: suspend (File) -> File) {
        val raw = (_state.value.step as? AiFlowStep.ConfirmPreview)?.file ?: return
        // M-130：与 submitSelfJudge 一致，上传前校验路由参数合法性，避免脏参进入上传链路
        if (bankIdArg.isBlank() || questionIdArg <= 0) return
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, step = AiFlowStep.Uploading)
        viewModelScope.launch {
            try {
                // M-127：压缩新文件前清理 cacheDir 中本功能（toneup_ai_ 前缀）的历史临时文件，
                // 并同步失效旧重试引用，避免会话内多轮确认造成累积泄漏
                cleanupStaleAiTempFiles(except = raw)
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
            } catch (e: CancellationException) {
                throw e
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
                val elapsedMs = System.currentTimeMillis() - startedAt
                if (PollBackoffPolicy.isDeadlineExceeded(elapsedMs)) {
                    fail("等待超时（60 秒）", canRetryUpload = true)
                    return@launch
                }
                // M-128：轮询中经 StateFlow 真实更新已耗时秒数，供 UI 展示进度
                _state.value = _state.value.copy(pollElapsedSeconds = (elapsedMs / 1000L).toInt())
                // M-131：delay 按已耗时长截断本次 sleep，确保睡醒后不越过 60s 总上限
                //（原先 deadline 仅在循环顶部检查，最后一轮仍会整睡最多 5s）
                delay(PollBackoffPolicy.delayForAttempt(index, elapsedMs))
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
                } catch (e: AppException.Network) {
                    // 网络抖动继续重试直至超时
                } catch (e: AppException.Server) {
                    // 5xx 瞬态：同样保留重试
                } catch (e: AppException.RateLimited) {
                    // 限流瞬态：保留重试
                } catch (e: IOException) {
                    // IO 抖动：保留重试
                } catch (e: Exception) {
                    // H-38：非瞬态失败（401/403、4xx 校验、反序列化）立即如实上报，
                    // 不得伪装成“等待超时（60 秒）”
                    fail((e as? AppException)?.userMessage ?: "AI 诊断请求失败", canRetryUpload = true)
                    return@launch
                }
            }
        }
    }

    /** FR-AI-06 失败或超时：重试上传生成新诊断任务 */
    fun retryUpload() {
        val file = lastUploadFile
        if (file == null) {
            // M-132：无可重试文件（如压缩失败时 lastUploadFile 尚未赋值）不得静默返回，
            // 回到确认预览步骤让用户重走压缩上传流程
            lastCapturedFile?.let { raw ->
                _state.value = _state.value.copy(step = AiFlowStep.ConfirmPreview(raw), errorHint = null)
            }
            return
        }
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
            } catch (e: CancellationException) {
                throw e
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
        // M-132：仅当确有可重试的上传文件时才展示重试入口，
        // 避免"压缩失败等 lastUploadFile 尚未赋值"的失败路径出现点击无效的重试按钮
        _state.value = _state.value.copy(
            busy = false,
            step = AiFlowStep.Failure(message, canRetryUpload && lastUploadFile != null)
        )
    }

    /** M-127：清理 cacheDir 中本功能（toneup_ai_ 前缀）的历史临时文件，保留 [except] 指向的当前源图 */
    private fun cleanupStaleAiTempFiles(except: File) {
        lastUploadFile?.let { if (it.exists() && it.absolutePath != except.absolutePath) it.delete() }
        lastUploadFile = null
        except.parentFile
            ?.listFiles { f -> f.name.startsWith("toneup_ai_") && f.absolutePath != except.absolutePath }
            ?.forEach { it.delete() }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
        // 清理临时文件
        lastUploadFile?.let { if (it.exists()) it.delete() }
        lastCapturedFile?.let { if (it.exists()) it.delete() }
    }
}
