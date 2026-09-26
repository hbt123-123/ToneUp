package com.toneup.app.ui.navigation

import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.toneup.app.BuildConfig
import com.toneup.app.data.repository.PracticeSession
import com.toneup.app.data.repository.PracticeSessionRegistry
import com.toneup.app.data.repository.QuestionRef
import com.toneup.app.ui.feature.analysis.AnalysisScreen
import com.toneup.app.ui.feature.aiphoto.AiPhotoScreen
import com.toneup.app.ui.feature.mine.FormulaPocScreen
import com.toneup.app.ui.feature.mine.NoteEditorScreen
import com.toneup.app.ui.feature.practice.PracticeScreen
import com.toneup.app.ui.feature.practice.PracticeFeatureApis
import com.toneup.app.ui.feature.practice.ReviewCheckScreen
import com.toneup.app.ui.feature.practice.SummaryScreen
import com.toneup.app.ui.feature.sectionlist.SectionListScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** 单题重做会话助手 */
@HiltViewModel
class RedoSessionHelper @Inject constructor(
    private val registry: PracticeSessionRegistry
) : ViewModel() {
    // M-252：登记本 helper 上一次注册的会话 id，注册新会话前先移除旧会话，
    // 避免单例 registry 随每次重做点击无界累积（同一页面停留期间至多保留一个会话）
    private var lastRegisteredId: String? = null

    fun createSingleQuestionSession(
        bankId: String,
        questionId: Long,
        title: String = "重做此题",
        onReady: (String) -> Unit
    ) {
        val sessionId = "redo_" + UUID.randomUUID().toString().take(8)
        // M-252：旧会话的 practice 条目此时必然已被弹出（回到本页才会再次注册），移除安全
        lastRegisteredId?.let(registry::remove)
        registry.register(
            PracticeSession(
                sessionId = sessionId,
                bankId = bankId,
                title = title,
                mode = PracticeSession.MODE_PRACTICE,
                fixedRefs = listOf(QuestionRef(bankId, questionId))
            )
        )
        lastRegisteredId = sessionId
        onReady(sessionId)
    }
}

/** 分组列表练习会话助手 */
@HiltViewModel
class SectionListSessionHelper @Inject constructor(
    private val registry: PracticeSessionRegistry,
    private val sessionRepository: com.toneup.app.data.repository.SessionRepository
) : ViewModel() {
    // M-252：同 RedoSessionHelper——注册新会话前移除本 helper 上一个会话，防单例 registry 累积
    private var lastRegisteredId: String? = null

    /** 本地全量练习（离线兜底路径）：按年份/题型分页装载 */
    fun createLocalSectionSession(
        bankId: String,
        year: Int?,
        typeCodeFilter: String?,
        title: String = "分组练习",
        onReady: (String) -> Unit
    ) {
        val sessionId = "sec_" + UUID.randomUUID().toString().take(8)
        // M-252：旧会话的 practice 条目此时必然已被弹出，移除安全
        lastRegisteredId?.let(registry::remove)
        registry.register(
            PracticeSession(
                sessionId = sessionId,
                bankId = bankId,
                title = title,
                mode = PracticeSession.MODE_PRACTICE,
                year = year,
                typeCodeFilter = typeCodeFilter
            )
        )
        lastRegisteredId = sessionId
        onReady(sessionId)
    }

    /**
     * EC-01：优先创建服务端会话（collection_ids + count 真实约束本轮题目）；
     * 创建失败（离线/服务端异常）回退既有本地全量刷题路径，serverBacked=false 供 UI Toast。
     */
    fun createSectionSession(
        bankId: String,
        collectionIds: List<Long>?,
        year: Int?,
        typeCodeFilter: String?,
        count: Int,
        title: String = "分组练习",
        onReady: (sessionId: String, serverBacked: Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val resp = sessionRepository.createSession(
                    bankId = bankId,
                    collectionIds = collectionIds,
                    typeCodes = typeCodeFilter?.let { listOf(it) },
                    count = count
                )
                val sid = resp.sessionId
                val session = PracticeSession(
                    sessionId = "srv_$sid",
                    bankId = bankId,
                    title = title,
                    mode = PracticeSession.MODE_PRACTICE,
                    fixedRefs = resp.questions.map { QuestionRef(bankId, it.questionId) },
                    serverSessionId = sid
                )
                session.appendAll(resp.questions)
                // M-252：服务端会话同样纳入本 helper 的移除管理
                lastRegisteredId?.let(registry::remove)
                registry.register(session)
                lastRegisteredId = session.sessionId
                onReady(session.sessionId, true)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // M-253：显式重抛取消，避免作用域取消后仍执行 fallback/回调破坏结构化并发
                throw e
            } catch (_: Exception) {
                // M-254：TODO(M-254)：fallback 丢弃 collectionIds/count 约束——本地分页装载
                // 路径尚不支持这两个约束的表达，需统一设计本地会话约束模型后透传（涉及
                // PracticeSession/装载逻辑/既有测试语义），当前已有 Toast 降级提示兜底
                createLocalSectionSession(bankId, year, typeCodeFilter, title) { localId ->
                    onReady(localId, false)
                }
            }
        }
    }
}

fun NavGraphBuilder.addPracticeGraph(navController: NavHostController) {
    composable(
        Routes.PRACTICE_PATTERN,
        arguments = listOf(
            navArgument("sessionId") { type = NavType.StringType },
            navArgument("mode") { type = NavType.StringType; defaultValue = "practice" },
            navArgument("index") { type = NavType.IntType; defaultValue = -1 }
        )
    ) { entry ->
        val sessionId = entry.arguments?.getString("sessionId") ?: ""
        // M-255：sessionId 为必填参数，缺失/空串时不再静默以空串继续（会导致 PracticeScreen
        // 取不到会话或后续路由构建异常），直接回退上一页
        if (sessionId.isEmpty()) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        PracticeScreen(
            initialIndex = entry.arguments?.getInt("index") ?: -1,
            onExit = { navController.popBackStack() },
            onOpenAnalysis = { attemptId ->
                navController.navigate(Routes.analysis(attemptId))
            },
            onOpenReviewCheck = {
                navController.navigate(Routes.reviewCheck(sessionId))
            },
            onOpenSummary = {
                navController.navigate(Routes.SUMMARY)
            },
            featureApis = hiltViewModel()
        )
    }
    composable(
        Routes.REVIEW_CHECK_PATTERN,
        arguments = listOf(navArgument("sessionId") { type = NavType.StringType })
    ) { entry ->
        val sessionId = entry.arguments?.getString("sessionId") ?: ""
        // M-255：空 sessionId 会使后续 Routes.practice(sessionId) 构建出无法匹配的路由，直接回退
        if (sessionId.isEmpty()) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        ReviewCheckScreen(
            onBack = { navController.popBackStack() },
            onSelectQuestion = { index ->
                navController.navigate(Routes.practice(sessionId, index = index)) {
                    popUpTo(Routes.practice(sessionId)) { inclusive = false }
                    launchSingleTop = true
                }
            }
        )
    }
    composable(Routes.SUMMARY) { entry ->
        // H-79：SUMMARY 可能无前序条目（deep link / 进程恢复后直接落到此路由），
        // checkNotNull 会直接崩溃；此时回退到上一页而不是抛 IllegalStateException
        val practiceEntry = navController.previousBackStackEntry
        if (practiceEntry == null) {
            LaunchedEffect(Unit) { navController.popBackStack() }
            return@composable
        }
        val viewModel: com.toneup.app.ui.feature.practice.PracticeViewModel = hiltViewModel(practiceEntry)
        val stats = viewModel.submitPaperStats()
        val totalTime = viewModel.elapsedSeconds.collectAsStateWithLifecycle().value
        SummaryScreen(
            stats = stats,
            totalTime = totalTime,
            serverSessionId = viewModel.serverSessionId,
            onReviewWrong = {
                navController.popBackStack()
            },
            onPracticeAgain = {
                navController.popBackStack()
            },
            onBackToList = {
                navController.popBackStack()
            },
            onBack = { navController.popBackStack() }
        )
    }
}

fun NavGraphBuilder.addAnalysisGraph(navController: NavHostController) {
    composable(
        Routes.ANALYSIS_PATTERN,
        arguments = listOf(navArgument("attemptId") { type = NavType.LongType })
    ) { entry ->
        val helper: RedoSessionHelper = hiltViewModel(entry)
        AnalysisScreen(
            onExit = { navController.popBackStack() },
            onOpenAiPhoto = { bankId, questionId, attemptId ->
                navController.navigate(Routes.aiPhoto(bankId, questionId, attemptId))
            },
            onRetryQuestion = { bankId, questionId ->
                helper.createSingleQuestionSession(bankId, questionId) { sessionId ->
                    navController.navigate(Routes.practice(sessionId))
                }
            }
        )
    }
}

fun NavGraphBuilder.addSecondaryGraphs(navController: NavHostController) {
    composable(Routes.WRONGBOOK) {
        com.toneup.app.ui.feature.wrongbook.WrongbookScreen(
            rootNavController = navController,
            onBack = { navController.popBackStack() }
        )
    }
    composable(
        Routes.NOTE_EDITOR_PATTERN,
        arguments = listOf(
            navArgument("questionId") { type = NavType.LongType },
            navArgument("bankId") { type = NavType.StringType; defaultValue = "" }
        )
    ) {
        NoteEditorScreen(onBack = { navController.popBackStack() })
    }
    composable(
        Routes.AI_PHOTO_PATTERN,
        arguments = listOf(
            navArgument("bankId") { type = NavType.StringType; defaultValue = "" },
            navArgument("questionId") { type = NavType.StringType; defaultValue = "-1" },
            navArgument("attemptId") { type = NavType.StringType; defaultValue = "-1" }
        )
    ) {
        AiPhotoScreen(onBack = { navController.popBackStack() })
    }
    composable(Routes.SESSION_HISTORY) {
        com.toneup.app.ui.feature.sessionhistory.SessionHistoryScreen(
            onBack = { navController.popBackStack() },
            onContinue = { sessionId, index ->
                navController.navigate(Routes.practice(sessionId, index = index))
            }
        )
    }
    if (BuildConfig.DEBUG) {
        composable(Routes.FORMULA_POC) {
            FormulaPocScreen(onBack = { navController.popBackStack() })
        }
    }
}

fun NavGraphBuilder.addSectionListGraph(navController: NavHostController) {
    composable(
        Routes.SECTION_LIST_PATTERN,
        arguments = listOf(navArgument("bankId") { type = NavType.StringType })
    ) { entry ->
        val bankId = entry.arguments?.getString("bankId") ?: ""
        val helper: SectionListSessionHelper = hiltViewModel(entry)
        val context = androidx.compose.ui.platform.LocalContext.current
        SectionListScreen(
            onNavigateToPractice = { navBankId, year, typeCode, count ->
                helper.createLocalSectionSession(
                    bankId = navBankId,
                    year = year,
                    typeCodeFilter = typeCode,
                    title = buildString {
                        append("分组练习")
                        year?.let { append(" ${it}年") }
                        typeCode?.let { append(" $it") }
                        count?.let { append(" ${it}题") }
                    }
                ) { sessionId ->
                    navController.navigate(Routes.practice(sessionId))
                }
            },
            onCreateSession = { navBankId, collectionIds, year, typeCode, count ->
                helper.createSectionSession(
                    bankId = navBankId,
                    collectionIds = collectionIds,
                    year = year,
                    typeCodeFilter = typeCode,
                    count = count,
                    title = "练习 · ${count}题"
                ) { sessionId, serverBacked ->
                    if (!serverBacked) {
                        android.widget.Toast.makeText(
                            context, "在线会话创建失败，已进入本地练习", android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    navController.navigate(Routes.practice(sessionId))
                }
            },
            viewModel = hiltViewModel(entry)
        )
    }
}
