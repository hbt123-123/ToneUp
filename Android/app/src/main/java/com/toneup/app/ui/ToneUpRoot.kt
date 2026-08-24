package com.toneup.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.toneup.app.data.local.SessionManager
import com.toneup.app.data.repository.AuthRepository
import com.toneup.app.ui.feature.auth.LoginScreen
import com.toneup.app.ui.feature.auth.RegisterScreen
import com.toneup.app.ui.main.MainScaffold
import com.toneup.app.ui.navigation.Routes
import com.toneup.app.ui.navigation.addAnalysisGraph
import com.toneup.app.ui.navigation.addPracticeGraph
import com.toneup.app.ui.navigation.addSecondaryGraphs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prefsStore: com.toneup.app.data.local.UserPreferencesStore,
    val sessionManager: SessionManager
) : ViewModel() {

    sealed interface BootState {
        data object Loading : BootState
        data object LoggedIn : BootState
        data object NeedLogin : BootState
    }

    private val _state = MutableStateFlow<BootState>(BootState.Loading)
    val state = _state

    /** 全局偏好：动效/触感/深色策略 */
    val preferences: kotlinx.coroutines.flow.StateFlow<com.toneup.app.data.local.UserPreferences> =
        prefsStore.preferences.stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            com.toneup.app.data.local.UserPreferences()
        )

    init {
        refresh()
    }

    /** FR-AU-05：存在有效令牌则静默校验，有效直接进主框架 */
    fun refresh() {
        viewModelScope.launch {
            val user = runCatching { authRepository.restoreSession() }.getOrNull()
            if (user != null) {
                sessionManager.restoreCachedUser(
                    com.toneup.app.data.local.SessionUser(user.id, user.username, user.role)
                )
            }
            _state.value = if (user != null) BootState.LoggedIn else BootState.NeedLogin
        }
    }
}

/** 单 Activity 根组件：主题 + 登录栈 + 主框架 + 全屏二级页 */
@Composable
fun ToneUpRoot(rootViewModel: RootViewModel = hiltViewModel()) {
    val navController = rememberNavController()
    val bootState by rootViewModel.state.collectAsStateWithLifecycle()
    val prefs by rootViewModel.preferences.collectAsStateWithLifecycle()

    com.toneup.app.ui.theme.ToneUpTheme(darkModePolicy = prefs.darkModePolicy) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalToneUpPreferences provides ToneUpPreferences(
                animationsEnabled = prefs.animationsEnabled,
                hapticsEnabled = prefs.hapticsEnabled
            )
        ) {
            ToneUpNavGraph(navController, rootViewModel, bootState)
        }
    }
}

@Composable
private fun ToneUpNavGraph(
    navController: NavHostController,
    rootViewModel: RootViewModel,
    bootState: RootViewModel.BootState
) {

    // 401 失效事件：清会话跳登录，保留恢复路由（§2.5）
    LaunchedEffect(Unit) {
        rootViewModel.sessionManager.unauthorizedEvents.collect { _ ->
            navController.navigate(Routes.LOGIN) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    when (bootState) {
        RootViewModel.BootState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        else -> {
            NavHost(
                navController = navController,
                startDestination =
                    if (bootState == RootViewModel.BootState.LoggedIn) Routes.MAIN else Routes.LOGIN
            ) {
                composable(Routes.LOGIN) {
                    LoginScreen(
                        onLoginSuccess = {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onGoRegister = { navController.navigate(Routes.REGISTER) }
                    )
                }
                composable(Routes.REGISTER) {
                    RegisterScreen(
                        onRegisterSuccess = {
                            navController.navigate(Routes.MAIN) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Routes.MAIN) {
                    MainScaffold(navController)
                }
                addPracticeGraph(navController)
                addAnalysisGraph(navController)
                addSecondaryGraphs(navController)
            }
        }
    }
}
