package com.toneup.app.ui.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.AuthRepository
import com.toneup.app.data.local.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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

    // H-46：成功态是持久 StateFlow 状态，不是一次性事件——用它驱动导航会在
    // 组合重建后重放导航。改用零重放事件流承载“导航信号”。
    private val _loginSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val loginSuccess: SharedFlow<Unit> = _loginSuccessEvent

    // H-47：注册成功同样一次性消费，避免 Success 状态未消费导致重复导航
    private val _registerSuccessEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val registerSuccess: SharedFlow<Unit> = _registerSuccessEvent

    fun login(username: String, password: String) {
        if (_loginState.value is UiState.Loading) return // 防重复提交
        _loginState.value = UiState.Loading
        viewModelScope.launch {
            performLogin(username, password)
        }
    }

    // M-150：抽取登录主体。手动登录经 login() 的 Loading 守卫防重复提交；
    // 注册成功后的自动登录不经守卫直接 await（守卫会在在途登录态下静默吞掉自动登录）
    private suspend fun performLogin(username: String, password: String) {
        try {
            val user = authRepository.login(username, password)
            _loginState.value = UiState.Success(user.username)
            _loginSuccessEvent.tryEmit(Unit)
        } catch (e: CancellationException) {
            throw e // H-43：scope 取消不得转为用户可见失败
        } catch (e: AppException) {
            _loginState.value = UiState.Failure(e.userMessage)
        } catch (e: Exception) {
            _loginState.value = UiState.Failure("登录失败，请稍后重试")
        }
    }

    fun register(username: String, password: String) {
        if (_registerState.value is UiState.Loading) return
        // M-148：注册流程开始时清空旧值，避免上次注册残留的用户名被再次消费
        _registeredUsername.value = null
        _registerState.value = UiState.Loading
        viewModelScope.launch {
            try {
                // M-149：使用后端返回的规范化用户名，而非用户原始输入
                val user = authRepository.register(username, password)
                _registerState.value = UiState.Success(user.username)
                _registeredUsername.value = user.username
                _registerSuccessEvent.tryEmit(Unit)
                // 注册成功引导直接登录：M-150 直接 await 登录主体，
                // 绕过 login() 的 Loading 守卫；register 自身守卫保证不会并发双登录
                _loginState.value = UiState.Loading
                performLogin(username, password)
            } catch (e: CancellationException) {
                throw e // H-44：同 login，取消必须传播
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
