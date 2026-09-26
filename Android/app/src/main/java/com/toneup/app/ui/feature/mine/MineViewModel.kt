package com.toneup.app.ui.feature.mine

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.local.UserPreferences
import com.toneup.app.data.local.UserPreferencesStore
import com.toneup.app.data.remote.dto.UserDto
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.AuthRepository
import com.toneup.app.data.repository.NotesRepository
import com.toneup.app.ui.common.Load
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MineUiState(
    val user: Load<UserDto> = Load.Loading,
    val notes: Load<List<com.toneup.app.data.remote.dto.NoteListItemDto>> = Load.Loading,
    val preferences: UserPreferences = UserPreferences(),
    val logoutBusy: Boolean = false,
    val errorHint: String? = null
)

@HiltViewModel
class MineViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val notesRepository: NotesRepository,
    private val prefsStore: UserPreferencesStore,
    private val sessionManager: SessionManager
) : ViewModel() {

    private val _state = MutableStateFlow(MineUiState())
    val state: StateFlow<MineUiState> = _state

    // H-50：登出导航信号改为一次性事件，loggedOut sticky 标志已移除
    private val _logoutEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val logoutEvent: SharedFlow<Unit> = _logoutEvent

    init {
        loadUser()
        loadNotes()
        viewModelScope.launch {
            prefsStore.preferences.collect { prefs ->
                // M-168：非原子读改写改为 update{} 原子更新（多协程并发写 _state）
                _state.update { it.copy(preferences = prefs) }
            }
        }
    }

    /** FR-ME-01 用户信息（用户名、注册时间） */
    fun loadUser() {
        viewModelScope.launch {
            try {
                val me = authRepository.me()
                _state.update { it.copy(user = Load.Ready(me)) }
            } catch (e: AppException) {
                _state.update { it.copy(user = Load.Failed(e.userMessage)) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(user = Load.Failed("加载失败")) }
            }
        }
    }

    /** FR-ME-02 我的笔记聚合列表（临时端点，待后端对齐） */
    fun loadNotes() {
        viewModelScope.launch {
            try {
                val page = notesRepository.myNotes(page = 1, pageSize = 50)
                _state.update { it.copy(notes = Load.Ready(page.items)) }
            } catch (e: AppException) {
                // M-169：与通用异常分支统一口径——已有 Ready 列表时不覆盖为失败态
                _state.update { s ->
                    if (s.notes is Load.Ready) s else s.copy(notes = Load.Failed(e.userMessage))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { s ->
                    if (s.notes is Load.Ready) s else s.copy(notes = Load.Failed("加载失败"))
                }
            }
        }
    }

    // M-170：偏好写入包异常处理——取消异常必须重抛，IO 等失败留痕不再静默
    private fun launchPrefsWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "preferences write failed", e)
            }
        }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        launchPrefsWrite { prefsStore.setAnimationsEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        launchPrefsWrite { prefsStore.setHapticsEnabled(enabled) }
    }

    fun setDarkModePolicy(policy: com.toneup.app.ui.theme.DarkModePolicy) {
        launchPrefsWrite { prefsStore.setDarkModePolicy(policy) }
    }

    /** FR-ME-04 退出登录：二次确认后清令牌/缓存/草稿/队列 */
    fun logout() {
        if (_state.value.logoutBusy) return
        // M-168：logoutBusy 的置位/复位同样走 update{} 原子更新
        _state.update { it.copy(logoutBusy = true) }
        viewModelScope.launch {
            // H-51：Repository 内部已容错，本地清理若仍失败必须打点可见，
            // 不得静默丢弃后照常宣告登出成功
            runCatching { authRepository.logout() }
                .onFailure { Log.e(TAG, "logout local cleanup failed", it) }
            sessionManager.clearSession()
            _state.update { it.copy(logoutBusy = false) }
            // H-50：一次性事件替代 sticky 的 loggedOut 标志，避免组合重建后重复导航
            _logoutEvent.tryEmit(Unit)
        }
    }

    companion object {
        private const val TAG = "MineViewModel"
    }
}
