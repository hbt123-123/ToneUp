package com.toneup.app.ui.feature.mine

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MineUiState(
    val user: Load<UserDto> = Load.Loading,
    val notes: Load<List<com.toneup.app.data.remote.dto.NoteListItemDto>> = Load.Loading,
    val preferences: UserPreferences = UserPreferences(),
    val logoutBusy: Boolean = false,
    val loggedOut: Boolean = false,
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

    init {
        loadUser()
        loadNotes()
        viewModelScope.launch {
            prefsStore.preferences.collect { prefs ->
                _state.value = _state.value.copy(preferences = prefs)
            }
        }
    }

    /** FR-ME-01 用户信息（用户名、注册时间） */
    fun loadUser() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(user = Load.Ready(authRepository.me()))
            } catch (e: AppException) {
                _state.value = _state.value.copy(user = Load.Failed(e.userMessage))
            } catch (_: Exception) {
                _state.value = _state.value.copy(user = Load.Failed("加载失败"))
            }
        }
    }

    /** FR-ME-02 我的笔记聚合列表（临时端点，待后端对齐） */
    fun loadNotes() {
        viewModelScope.launch {
            try {
                val page = notesRepository.myNotes(page = 1, pageSize = 50)
                _state.value = _state.value.copy(notes = Load.Ready(page.items))
            } catch (e: AppException) {
                _state.value = _state.value.copy(notes = Load.Failed(e.userMessage))
            } catch (e: Exception) {
                if (_state.value.notes is Load.Ready) return@launch
                _state.value = _state.value.copy(notes = Load.Failed("加载失败"))
            }
        }
    }

    fun setAnimationsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setAnimationsEnabled(enabled) }
    }

    fun setHapticsEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setHapticsEnabled(enabled) }
    }

    fun setDarkModePolicy(policy: com.toneup.app.ui.theme.DarkModePolicy) {
        viewModelScope.launch { prefsStore.setDarkModePolicy(policy) }
    }

    /** FR-ME-04 退出登录：二次确认后清令牌/缓存/草稿/队列 */
    fun logout() {
        if (_state.value.logoutBusy) return
        _state.value = _state.value.copy(logoutBusy = true)
        viewModelScope.launch {
            runCatching { authRepository.logout() }
            sessionManager.clearSession()
            _state.value = _state.value.copy(logoutBusy = false, loggedOut = true)
        }
    }
}
