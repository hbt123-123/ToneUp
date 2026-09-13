package com.toneup.app.ui.navigation

import androidx.hilt.navigation.compose.hiltViewModel
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
import java.util.UUID
import javax.inject.Inject

/** 单题重做会话助手 */
@HiltViewModel
class RedoSessionHelper @Inject constructor(
    private val registry: PracticeSessionRegistry
) : ViewModel() {
    fun createSingleQuestionSession(
        bankId: String,
        questionId: Long,
        title: String = "重做此题",
        onReady: (String) -> Unit
    ) {
        val sessionId = "redo_" + UUID.randomUUID().toString().take(8)
        registry.register(
            PracticeSession(
                sessionId = sessionId,
                bankId = bankId,
                title = title,
                mode = PracticeSession.MODE_PRACTICE,
                fixedRefs = listOf(QuestionRef(bankId, questionId))
            )
        )
        onReady(sessionId)
    }
}

/** 分组列表练习会话助手 */
@HiltViewModel
class SectionListSessionHelper @Inject constructor(
    private val registry: PracticeSessionRegistry
) : ViewModel() {
    fun createSectionPracticeSession(
        bankId: String,
        year: Int?,
        typeCodeFilter: String?,
        title: String = "分组练习",
        onReady: (String) -> Unit
    ) {
        val sessionId = "sec_" + UUID.randomUUID().toString().take(8)
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
        onReady(sessionId)
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
        ReviewCheckScreen(
            sessionId = sessionId,
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
        val practiceEntry = checkNotNull(navController.previousBackStackEntry)
        val viewModel: com.toneup.app.ui.feature.practice.PracticeViewModel = hiltViewModel(practiceEntry)
        val stats = viewModel.submitPaperStats()
        val totalTime = viewModel.elapsedSeconds.collectAsStateWithLifecycle().value
        SummaryScreen(
            stats = stats,
            totalTime = totalTime,
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
        SectionListScreen(
            onNavigateToPractice = { navBankId, year, typeCode, count ->
                helper.createSectionPracticeSession(
                    bankId = navBankId,
                    year = year,
                    typeCodeFilter = typeCode,
                    title = buildString {
                        append("分组练习")
                        year?.let { append(" $it年") }
                        typeCode?.let { append(" $it") }
                        count?.let { append(" $it题") }
                    }
                ) { sessionId ->
                    navController.navigate(Routes.practice(sessionId))
                }
            },
            viewModel = hiltViewModel(entry)
        )
    }
}
