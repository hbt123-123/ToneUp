package com.toneup.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.toneup.app.ui.navigation.addSectionListGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val prefsStore: com.toneup.app.data.local.UserPreferencesStore,
    // M-261：SessionManager 为数据层单例，收窄为 private，不再向 UI 暴露
    private val sessionManager: SessionManager
) : ViewModel() {

    sealed interface BootState {
        data object Loading : BootState
        data object LoggedIn : BootState
        data object NeedLogin : BootState
    }

    private val _state = MutableStateFlow<BootState>(BootState.Loading)
    // M-262：对外仅暴露只读 StateFlow，防止 UI 直接改写启动态
    val state: kotlinx.coroutines.flow.StateFlow<BootState> = _state.asStateFlow()

    // M-261：401 失效信号在 ViewModel 内转换为 UI 事件流（UI 事件模型，屏蔽数据层
    // SessionManager/UnauthorizedEvent 类型），UI 只消费"发生了 401"这一信号
    val unauthorizedEvents: Flow<Unit> = sessionManager.unauthorizedEvents.map { }

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
            // H-82：runCatching 会连 CancellationException 一起吞掉（restoreSession 内部已
            // 显式 rethrow CE），scope 取消时仍会继续写 state，破坏结构化并发；
            // 显式放行取消，其余异常按"恢复失败→进登录页"处理
            val user = try {
                authRepository.restoreSession()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
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
        // M-263：remember 缓存偏好实例（按相关字段作为 key），避免每次重组新分配
        // ToneUpPreferences 导致 staticCompositionLocalOf 的下游全量失效
        val toneUpPrefs = remember(prefs.animationsEnabled, prefs.hapticsEnabled) {
            ToneUpPreferences(
                animationsEnabled = prefs.animationsEnabled,
                hapticsEnabled = prefs.hapticsEnabled
            )
        }
        androidx.compose.runtime.CompositionLocalProvider(
            LocalToneUpPreferences provides toneUpPrefs
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
    // M-261：改为消费 ViewModel 封装的 UI 事件流，UI 不再直接触达数据层 SessionManager
    LaunchedEffect(Unit) {
        rootViewModel.unauthorizedEvents.collect {
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

        // M-264：逐分支枚举替代 else，保留 sealed 层级的穷尽性检查（新增状态时编译期即暴露漏分支）
        RootViewModel.BootState.LoggedIn,
        RootViewModel.BootState.NeedLogin -> {
            // M-265：NavHost 的 startDestination 仅在图首次创建时按当时 bootState 决定；
            // refresh() 事后翻转登录态时图不会重建，这里监听后续翻转并显式补导航对齐，
            // 避免 UI 停留在与登录态不符的目的地
            var navGraphPlaced by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(bootState) {
                if (!navGraphPlaced) {
                    // 首次进入：startDestination 已按当前 bootState 决定，无需补导航
                    navGraphPlaced = true
                    return@LaunchedEffect
                }
                val target =
                    if (bootState == RootViewModel.BootState.LoggedIn) Routes.MAIN else Routes.LOGIN
                if (navController.currentBackStackEntry?.destination?.route != target) {
                    navController.navigate(target) {
                        // 清掉与目标登录态相反一侧的回栈（登录栈 ↔ 主栈），语义同"登录态切换"
                        popUpTo(if (target == Routes.MAIN) Routes.LOGIN else Routes.MAIN) {
                            inclusive = true
                        }
                        launchSingleTop = true
                    }
                }
            }
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
                addSectionListGraph(navController)
            }
        }
    }
}
