package com.toneup.app.data.repository

import android.util.Log
import com.toneup.app.data.local.PendingSubmission
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.api.AttemptApi
import com.toneup.app.data.remote.dto.AttemptResultDto
import com.toneup.app.data.remote.dto.SubmitAttemptRequest
import com.toneup.app.domain.logic.IdempotencyKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 答题提交仓库：
 * - client_request_id 幂等复用（§8.2）
 * - 网络失败入未同步队列；联网自动重放（§8.3）
 * - 待同步数量横幅 StateFlow
 */
@Singleton
class PracticeRepository @Inject constructor(
    private val attemptApi: AttemptApi,
    private val jsonProvider: JsonProvider,
    private val sessionManager: SessionManager,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val idempotencyKeyStore: IdempotencyKeyStore,
    private val resultCache: AttemptResultCache
) {
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    private val replaying = AtomicBoolean(false)

    suspend fun submit(
        bankId: String,
        questionId: Long,
        answerJson: kotlinx.serialization.json.JsonObject,
        timeSpentSeconds: Int,
        mode: String
    ): AttemptResultDto {
        val clientRequestId = idempotencyKeyStore.keyFor(bankId, questionId)
        val body = SubmitAttemptRequest(
            bankId = bankId,
            questionId = questionId,
            answer = answerJson,
            timeSpentSeconds = timeSpentSeconds,
            mode = mode,
            clientRequestId = clientRequestId
        )
        return try {
            val (code, result) = EnvelopeUnwrapper.unwrapWithStatus(jsonProvider.json) {
                attemptApi.submit(body)
            }
            // 明确成功（含 202 受理）：幂等键使命结束，缓存结果
            idempotencyKeyStore.confirm(bankId, questionId)
            resultCache.put(result, bankId, questionId)
            Log.d(TAG, "submit ok code=$code attempt=${result.attemptId}")
            result
        } catch (e: AppException.Network) {
            // 网络失败：保留幂等键 + 入未同步队列
            enqueuePending(bankId, questionId, answerJson, timeSpentSeconds, mode, clientRequestId)
            throw e
        } catch (e: AppException.Unauthorized) {
            throw e
        } catch (e: AppException) {
            // 明确业务拒绝：幂等键使命结束
            idempotencyKeyStore.confirm(bankId, questionId)
            throw e
        }
    }

    /** 自评兜底：mode=self_judge 回填原待判分 attempt（后端契约 §8.6） */
    suspend fun submitSelfJudge(
        bankId: String,
        questionId: Long,
        selfCorrect: Boolean,
        originalAttemptId: Long?
    ): AttemptResultDto {
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("self_correct", kotlinx.serialization.json.JsonPrimitive(selfCorrect))
            if (originalAttemptId != null) {
                put("original_attempt_id", kotlinx.serialization.json.JsonPrimitive(originalAttemptId))
            }
        }
        return submit(
            bankId = bankId,
            questionId = questionId,
            answerJson = payload,
            timeSpentSeconds = 0,
            mode = PracticeSession.MODE_SELF_JUDGE
        )
    }

    suspend fun fetchAttempt(attemptId: Long): AttemptResultDto =
        EnvelopeUnwrapper.unwrap(jsonProvider.json) { attemptApi.attempt(attemptId) }
            .also { resultCache.update(it) }

    /** 登录/启动时从持久化队列恢复在途幂等键并刷新计数 */
    suspend fun restorePendingState(userId: Long) {
        val data = sessionDataStoreManager.storeFor(userId).data.first()
        data.pendingSubmissions.forEach { pending ->
            idempotencyKeyStore.seed(pending.bankId, pending.questionId, pending.clientRequestId)
        }
        _pendingCount.value = data.pendingSubmissions.size
    }

    fun resetLocalState() {
        idempotencyKeyStore.clearAll()
        _pendingCount.value = 0
    }

    /**
     * 联网自动重放：逐条重放未同步提交（沿用原 client_request_id）。
     * 成功 → 移除并以服务端结果为准刷新缓存；失败 → 保留等待下次。
     */
    suspend fun replayPendingQueue(): Int {
        // compareAndSet 保证互斥：连接恢复回调与手动重试并发时仅一轮进入
        if (!replaying.compareAndSet(false, true)) return 0
        var replayed = 0
        try {
            val userId = sessionManager.currentUserId() ?: return 0
            val store = sessionDataStoreManager.storeFor(userId)
            while (true) {
                val pending = store.data.first().pendingSubmissions.firstOrNull()
                    ?: break
                try {
                    val (_, result) = EnvelopeUnwrapper.unwrapWithStatus(jsonProvider.json) {
                        attemptApi.submit(
                            SubmitAttemptRequest(
                                bankId = pending.bankId,
                                questionId = pending.questionId,
                                answer = pending.answer,
                                timeSpentSeconds = pending.timeSpentSeconds,
                                mode = pending.mode,
                                clientRequestId = pending.clientRequestId
                            )
                        )
                    }
                    resultCache.put(result, pending.bankId, pending.questionId)
                    removePending(userId, pending)
                    replayed++
                } catch (e: AppException.Network) {
                    break // 网络仍不可用：停止本轮，剩余项保留
                } catch (e: IOException) {
                    break
                } catch (e: AppException.Server) {
                    Log.w(TAG, "replay deferred for q=${pending.questionId}: ${e.userMessage}")
                    break // 5xx 瞬态：保留条目等待下一轮重放
                } catch (e: AppException.RateLimited) {
                    Log.w(TAG, "replay deferred for q=${pending.questionId}: ${e.userMessage}")
                    break // 429 限流：保留条目等待下一轮重放
                } catch (e: AppException) {
                    // 明确业务拒绝（400/401/403/404/success=false）：移除条目
                    Log.w(TAG, "replay rejected for q=${pending.questionId}: ${e.userMessage}")
                    removePending(userId, pending)
                } catch (e: Exception) {
                    Log.e(TAG, "replay failed for q=${pending.questionId}", e)
                    break // 未知异常：保留条目等待下一轮重放，避免误删未确认作答
                }
            }
        } finally {
            replaying.set(false)
            refreshPendingCount()
        }
        return replayed
    }

    private suspend fun enqueuePending(
        bankId: String,
        questionId: Long,
        answerJson: kotlinx.serialization.json.JsonObject,
        timeSpentSeconds: Int,
        mode: String,
        clientRequestId: String
    ) {
        val userId = sessionManager.currentUserId() ?: return
        val store = sessionDataStoreManager.storeFor(userId)
        store.updateData { data ->
            data.copy(
                pendingSubmissions = data.pendingSubmissions
                    .filterNot { it.bankId == bankId && it.questionId == questionId } +
                    PendingSubmission(
                        userId = userId,
                        bankId = bankId,
                        questionId = questionId,
                        clientRequestId = clientRequestId,
                        answer = answerJson,
                        timeSpentSeconds = timeSpentSeconds,
                        mode = mode,
                        createdAtMillis = System.currentTimeMillis()
                    )
            )
        }
        refreshPendingCount()
    }

    private suspend fun removePending(userId: Long, pending: PendingSubmission) {
        val store = sessionDataStoreManager.storeFor(userId)
        store.updateData { data ->
            data.copy(
                pendingSubmissions = data.pendingSubmissions.filterNot {
                    it.clientRequestId == pending.clientRequestId &&
                        it.questionId == pending.questionId
                }
            )
        }
        refreshPendingCount()
    }

    suspend fun refreshPendingCount(userId: Long? = null) {
        val uid = userId ?: sessionManager.currentUserId() ?: run {
            _pendingCount.value = 0
            return
        }
        val count = sessionDataStoreManager.storeFor(uid).data.first().pendingSubmissions.size
        _pendingCount.value = count
    }

    private companion object {
        const val TAG = "PracticeRepository"
    }
}
