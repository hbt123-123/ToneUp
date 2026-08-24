package com.toneup.app.ui.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.AuthRepository
import com.toneup.app.data.local.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val sessionManager: SessionManager
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data object Loading : UiState
        data class Success(val username: String) : UiState
        data class Failure(val message: String) : UiState
    }

    private val _loginState = MutableStateFlow<UiState>(UiState.Idle)
    val loginState: StateFlow<UiState> = _loginState

    private val _registerState = MutableStateFlow<UiState>(UiState.Idle)
    val registerState: StateFlow<UiState> = _registerState

    /** 注册成功后的预填用户名，引导直接登录（FR-AU-02） */
    private val _registeredUsername = MutableStateFlow<String?>(null)
    val registeredUsername: StateFlow<String?> = _registeredUsername

    fun login(username: String, password: String) {
        if (_loginState.value is UiState.Loading) return // 防重复提交
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val user = authRepository.login(username, password)
                _loginState.value = UiState.Success(user.username)
            } catch (e: AppException) {
                _loginState.value = UiState.Failure(e.userMessage)
            } catch (e: Exception) {
                _loginState.value = UiState.Failure("登录失败，请稍后重试")
            }
        }
    }

    fun register(username: String, password: String) {
        if (_registerState.value is UiState.Loading) return
        _registerState.value = UiState.Loading
        viewModelScope.launch {
            try {
                authRepository.register(username, password)
                _registerState.value = UiState.Success(username)
                _registeredUsername.value = username
                // 注册成功引导直接登录
                login(username, password)
            } catch (e: AppException) {
                _registerState.value = UiState.Failure(e.userMessage)
            } catch (e: Exception) {
                _registerState.value = UiState.Failure("注册失败，请稍后重试")
            }
        }
    }

    /** 登录成功后清除 401 保存的恢复路由 */
    fun consumeRestoreRoute(): String? = sessionManager.consumeRestoreRoute()
}
