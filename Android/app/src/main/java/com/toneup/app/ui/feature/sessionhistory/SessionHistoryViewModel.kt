package com.toneup.app.ui.feature.sessionhistory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.LastPracticeContext
import com.toneup.app.data.local.SessionDataStoreManager
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.remote.dto.SessionListItemDto
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * EC-01 练习会话历史：分页列表（标题/进度/时间）+ 继续（active）/删除。
 */
data class SessionHistoryUiState(
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val items: List<SessionListItemDto> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val error: String? = null,
    /** 非空 = 该会话重建中（点击"继续"） */
    val resumingId: Long? = null,
    val resumeError: String? = null
)

@HiltViewModel
class SessionHistoryViewModel @Inject constructor(
    private val sessionRepository: SessionRepository,
    private val sessionRegistry: PracticeSessionRegistry,
    private val sessionDataStoreManager: SessionDataStoreManager,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(SessionHistoryUiState())
    val state: StateFlow<SessionHistoryUiState> = _state

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val page = sessionRepository.listSessions(page = 1)
                _state.value = _state.value.copy(
                    loading = false,
                    items = page.items,
                    page = 1,
                    hasMore = page.hasMore
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = (e as? com.toneup.app.data.repository.AppException)?.userMessage
                        ?: "会话列表加载失败"
                )
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore) return
        viewModelScope.launch {
            _state.value = s.copy(loadingMore = true)
            try {
                val page = sessionRepository.listSessions(page = s.page + 1)
                _state.value = _state.value.copy(
                    loadingMore = false,
                    items = _state.value.items + page.items,
                    page = s.page + 1,
                    hasMore = page.hasMore
                )
            } catch (_: Exception) {
                _state.value = _state.value.copy(loadingMore = false)
            }
        }
    }

    /** 删除会话（服务端 + registry 内副本） */
    fun delete(sessionId: Long) {
        viewModelScope.launch {
            try {
                sessionRepository.deleteSession(sessionId)
                _state.value = _state.value.copy(
                    items = _state.value.items.filterNot { it.id == sessionId }
                )
                sessionRegistry.remove("srv_$sessionId")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    resumeError = (e as? com.toneup.app.data.repository.AppException)?.userMessage
                        ?: "删除失败"
                )
            }
        }
    }

    fun clearResumeError() {
        _state.value = _state.value.copy(resumeError = null)
    }

    /**
     * 继续会话：active 会话重建题目序列（服务端 detail 优先，离线回退本地全量），
     * 同时写入 lastContext 供"继续上次刷题"复用。
     */
    fun resumeSession(item: SessionListItemDto, onReady: (String, Int) -> Unit) {
        if (_state.value.resumingId != null) return
        _state.value = _state.value.copy(resumingId = item.id, resumeError = null)
        viewModelScope.launch {
            try {
                val (session, startIndex) = rebuild(item)
                sessionRegistry.register(session)
                saveLastContext(session, startIndex)
                _state.value = _state.value.copy(resumingId = null)
                onReady(session.sessionId, startIndex)
            } catch (_: Exception) {
                _state.value = _state.value.copy(
                    resumingId = null,
                    resumeError = "会话恢复失败，请重试"
                )
            }
        }
    }

    /**
     * 重建会话：服务端 detail 成功 → 题目预填从进度处续刷。
     *
     * 失败一律上抛（由 resumeSession 提示"会话恢复失败，请重试"）：
     * 静默降级为无 serverSessionId 的本地会话会让后续交卷走 LocalOnly、
     * 服务端会话永久停在 active；且该兜底在真正离线时同样不可用
     * （分页装载题目也要网络），故不再保留降级分支。
     */
    private suspend fun rebuild(item: SessionListItemDto): Pair<PracticeSession, Int> {
        val d = sessionRepository.sessionDetail(item.id)
        val session = PracticeSession(
            sessionId = "srv_${d.session.id}",
            bankId = d.session.bankId,
            title = d.session.title,
            mode = PracticeSession.MODE_PRACTICE,
            fixedRefs = d.questions.map { QuestionRef(d.session.bankId, it.questionId) },
            serverSessionId = d.session.id,
            restoredDraft = d.session.draft
        )
        synchronized(session) { session.questions.addAll(d.questions) }
        return session to d.session.currentIndex.coerceIn(0, (d.questions.size - 1).coerceAtLeast(0))
    }

    private suspend fun saveLastContext(session: PracticeSession, index: Int) {
        val userId = sessionManager.currentUserId() ?: return
        sessionDataStoreManager.storeFor(userId).updateData { data ->
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
    }
}
