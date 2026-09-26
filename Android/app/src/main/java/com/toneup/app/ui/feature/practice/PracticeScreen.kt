package com.toneup.app.ui.feature.practice

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.logic.PracticeStatus
import com.toneup.app.domain.logic.ReciteMode
import com.toneup.app.domain.model.QuestionType
import com.toneup.app.ui.LocalToneUpPreferences
import com.toneup.app.ui.components.Haptic
import com.toneup.app.ui.components.QuestionSkeleton
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.components.performHaptic
import com.toneup.app.ui.components.question.QuestionContext
import com.toneup.app.ui.components.question.RendererRegistry
import com.toneup.app.ui.feature.practice.renderers.FallbackRenderer
import com.toneup.app.ui.feature.practice.renderers.GradingResultBar
import com.toneup.app.domain.logic.CorrectAnswerParser
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.theme.CorrectGreen
import com.toneup.app.ui.theme.WrongRed
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.launch

/**
 * 刷题页（PR）：沉浸模式、新顶栏（返回+计时+重做+设置）、
 * 新底栏（答题卡+交卷+答案+收藏+翻页）、题型分发渲染、
 * 边缘手势切题（共享轴 X）、题号面板、待同步横幅。
 */
@Composable
fun PracticeScreen(
    onExit: () -> Unit,
    onOpenAnalysis: (Long) -> Unit,
    onOpenReviewCheck: () -> Unit = {},
    onOpenSummary: () -> Unit = {},
    initialIndex: Int = -1,
    viewModel: PracticeViewModel = hiltViewModel(),
    featureApis: PracticeFeatureApis? = null
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = LocalToneUpPreferences.current
    val haptics = rememberHapticsPerformer(preferences.hapticsEnabled)
    var showGridPanel by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }
    var showSubmitDialog by remember { mutableStateOf(false) }

    // 收藏状态
    val isFavorited by viewModel.isFavorited.collectAsStateWithLifecycle()
    // 背题模式（显示答案）状态
    val showAnswerMode by viewModel.showAnswer.collectAsStateWithLifecycle()

    ImmersiveModeEffect()

    // 从交卷检查页跳回指定题号（FR-PR-09）
    LaunchedEffect(initialIndex) {
        if (initialIndex >= 0 && initialIndex != state.currentIndex) {
            viewModel.loadQuestion(initialIndex)
        }
    }

    // M-186：当前题槽尚未装载（会话初始化/分页装载中）时给出加载占位反馈，
    // 而非裸 return 隐藏整屏（顶栏/底栏/横幅全部消失且无任何提示）
    val slot = state.slots.getOrNull(state.currentIndex)
    if (slot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        // ===== 新顶栏 =====
        EnhancedTopBar(
            onBack = {
                if (slot.status is PracticeStatus.Editing) showExitDialog = true else onExit()
            },
            elapsedSeconds = viewModel.elapsedSeconds.collectAsStateWithLifecycle().value,
            onRedo = {
                viewModel.redoQuestion()
                haptics(Haptic.LIGHT_IMPACT)
            },
            onSettings = { /* TODO: 设置入口 */ },
            questionTypeLabel = slot.question?.let { resolveTypeLabel(it.typeCode) },
            currentIndex = state.currentIndex,
            knownTotal = state.knownTotal,
            progress = if (state.knownTotal > 0) {
                ((state.currentIndex + 1f) / state.knownTotal).coerceIn(0f, 1f)
            } else 0f,
            reciteMode = showAnswerMode
        )

        // 待同步横幅（§8.3）
        if (state.pendingSyncCount > 0) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    text = "有 ${state.pendingSyncCount} 条记录待同步，网络恢复后自动上传",
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }

        // 分页模式（knownTotal<0 或 hasMore）下放行下一题，由 ensureSlot 分页装载
        val canGoNext = state.currentIndex + 1 < state.slots.size ||
            state.currentIndex + 1 < state.knownTotal ||
            state.hasMore

        BoxWithEdgeSwipe(
            enabled = true,
            onSwipeLeft = {
                if (canGoNext) {
                    viewModel.loadQuestion(state.currentIndex + 1)
                }
            },
            onSwipeRight = {
                if (state.currentIndex > 0) viewModel.loadQuestion(state.currentIndex - 1)
            },
            modifier = Modifier.weight(1f)
        ) {
            if (isReadingGroup(slot)) {
                // 阅读题组：固定文章 + 翻动小题
                ReadingGroupBody(
                    state = state,
                    viewModel = viewModel,
                    onOpenAnalysis = onOpenAnalysis,
                    onRetryLoad = { viewModel.retryLoad(state.currentIndex) },
                    featureApis = featureApis,
                    showAnswerMode = showAnswerMode
                )
            } else {
                // 普通单题
                AnimatedContent(
                    targetState = state.currentIndex,
                    transitionSpec = {
                        if (!preferences.animationsEnabled) {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(120))
                        } else if (targetState > initialState) {
                            (slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { -it / 3 } + fadeOut(tween(220)))
                        } else {
                            (slideInHorizontally(tween(220)) { -it / 3 } + fadeIn(tween(220))) togetherWith
                                (slideOutHorizontally(tween(220)) { it / 3 } + fadeOut(tween(220)))
                        }
                    },
                    label = "question"
                ) { index ->
                    Column(Modifier.fillMaxSize()) {
                        // 背题模式：在题干下方直接展示答案
                        if (showAnswerMode) {
                            AnswerModeOverlay(
                                slot = state.slots.getOrNull(index),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        QuestionBody(
                            index = index,
                            state = state,
                            viewModel = viewModel,
                            onOpenAnalysis = onOpenAnalysis,
                            onRetryLoad = { viewModel.retryLoad(index) },
                            onSkip = { viewModel.loadQuestion(index + 1) },
                            showAnswerMode = showAnswerMode,
                            featureApis = featureApis
                        )
                    }
                }
            }
        }

        // ===== 新底栏 =====
        EnhancedBottomBar(
            canPrev = state.currentIndex > 0,
            hasNext = canGoNext,
            onPrev = {
                viewModel.loadQuestion(state.currentIndex - 1)
                haptics(Haptic.LIGHT_IMPACT)
            },
            onNext = {
                viewModel.loadQuestion(state.currentIndex + 1)
                haptics(Haptic.LIGHT_IMPACT)
            },
            onOpenGrid = { showGridPanel = true },
            onSubmit = { showSubmitDialog = true },
            showAnswerMode = showAnswerMode,
            onToggleAnswerMode = {
                viewModel.toggleAnswerMode()
                haptics(Haptic.LIGHT_IMPACT)
            },
            isFavorited = isFavorited,
            onToggleFavorite = {
                viewModel.toggleFavorite()
                haptics(Haptic.LIGHT_IMPACT)
            }
        )
    }

    // ===== 题号面板 =====
    if (showGridPanel) {
        QuestionGridPanel(
            slots = state.slots,
            currentIndex = state.currentIndex,
            knownTotal = state.knownTotal,
            hasMore = state.hasMore,
            favoritedIds = state.favoritedIds,
            onSelect = { index ->
                showGridPanel = false
                viewModel.loadQuestion(index)
            },
            onDismiss = { showGridPanel = false }
        )
    }

    // ===== 退出确认对话框 =====
    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出刷题") },
            text = { Text("存在未提交的编辑，保存草稿并退出？") },
            confirmButton = {
                Button(onClick = {
                    showExitDialog = false
                    // H-57：兑现“保存草稿并退出”的承诺——此前仅退出，未持久化未提交编辑
                    viewModel.persistDraftsForExit()
                    onExit()
                }) { Text("保存草稿并退出") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) { Text("继续作答") }
            }
        )
    }

    // ===== 交卷确认对话框 =====
    if (showSubmitDialog) {
        // M-187：stats 不再在组合期直调 submitPaperStats（原每次重组都重复扫描
        // 全部题槽），改由 LaunchedEffect 在对话框打开时计算一次缓存
        var paperStats by remember(showSubmitDialog) { mutableStateOf<PaperStats?>(null) }
        LaunchedEffect(showSubmitDialog) {
            paperStats = viewModel.submitPaperStats()
        }
        val submitState by viewModel.sessionSubmitState.collectAsStateWithLifecycle()
        val context = androidx.compose.ui.platform.LocalContext.current
        LaunchedEffect(submitState) {
            when (val s = submitState) {
                is com.toneup.app.ui.feature.practice.SessionSubmitState.Done,
                    com.toneup.app.ui.feature.practice.SessionSubmitState.LocalOnly -> {
                    showSubmitDialog = false
                    viewModel.consumeSessionSubmitState()
                    onOpenSummary()
                }
                is com.toneup.app.ui.feature.practice.SessionSubmitState.Failed -> {
                    viewModel.consumeSessionSubmitState()
                    android.widget.Toast.makeText(context, s.message, android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> {}
            }
        }
        val stats = paperStats
        if (stats != null) {
            SubmitConfirmDialog(
                stats = stats,
                submitting = submitState is com.toneup.app.ui.feature.practice.SessionSubmitState.Submitting,
                onConfirm = { viewModel.submitSession() },
                onDismiss = {
                    if (submitState !is com.toneup.app.ui.feature.practice.SessionSubmitState.Submitting) {
                        showSubmitDialog = false
                    }
                }
            )
        }
    }
}

// ===== 顶栏 =====

@Composable
private fun EnhancedTopBar(
    onBack: () -> Unit,
    elapsedSeconds: Int,
    onRedo: () -> Unit,
    onSettings: () -> Unit,
    questionTypeLabel: String?,
    currentIndex: Int,
    knownTotal: Int,
    progress: Float,
    reciteMode: Boolean = false
) {
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .fillMaxWidth()
    ) {
        // 第一行：返回 | 计时 | 重做 | 设置
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出刷题")
            }

            // 计时器 HH:mm:ss（背题模式隐藏计时，进度与题号保留——EC-02）
            if (!reciteMode) {
                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.weight(1f))

            // 重做按钮
            TextButton(onClick = onRedo) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "重做",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("重做", style = MaterialTheme.typography.labelMedium)
            }

            // 设置齿轮
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "设置")
            }
        }

        // 第二行：题型标签 + 当前题号/总数
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (questionTypeLabel != null) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text(
                        text = questionTypeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
            Text(
                text = "当前题号 ${currentIndex + 1}/" +
                    if (knownTotal > 0) knownTotal.toString() else "?",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // 进度条
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(3.dp)
                .padding(horizontal = 16.dp)
        )
    }
}

/** 将秒数格式化为 HH:mm:ss */
private fun formatElapsed(seconds: Int): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return "%02d:%02d:%02d".format(h, m, s)
}

/** 根据题型 code 返回中文标签 */
private fun resolveTypeLabel(typeCode: String): String = when (typeCode) {
    QuestionType.Single.typeCode -> "单选题"
    QuestionType.Multi.typeCode -> "多选题"
    QuestionType.Judge.typeCode -> "判断题"
    QuestionType.Cloze.typeCode -> "完形填空"
    QuestionType.Reading.typeCode -> "阅读理解"
    QuestionType.Ordering.typeCode -> "排序题"
    QuestionDto.TYPE_ESSAY -> "主观题"
    else -> "其他"
}

// ===== 底栏 =====

@Composable
private fun EnhancedBottomBar(
    canPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenGrid: () -> Unit,
    onSubmit: () -> Unit,
    showAnswerMode: Boolean,
    onToggleAnswerMode: () -> Unit,
    isFavorited: Boolean,
    onToggleFavorite: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            // 上一行：功能按钮（答题卡 | 交卷 | 答案 | 收藏）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BottomToolButton(
                    icon = Icons.Filled.AccountBox,
                    label = "答题卡",
                    onClick = onOpenGrid
                )
                BottomToolButton(
                    icon = Icons.Filled.DateRange,
                    label = "交卷",
                    onClick = onSubmit,
                    // 背题模式禁用交卷（零上报，EC-02）；答题卡跳题保持可用
                    enabled = !showAnswerMode
                )
                BottomToolButton(
                    icon = Icons.Filled.Visibility,
                    label = "答案",
                    onClick = onToggleAnswerMode,
                    tint = if (showAnswerMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
                BottomToolButton(
                    icon = if (isFavorited) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    label = "收藏",
                    onClick = onToggleFavorite,
                    tint = if (isFavorited) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }

            // 下一行：上一题 | 下一题
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onPrev,
                    enabled = canPrev,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("上一题")
                }
                OutlinedButton(
                    onClick = onNext,
                    enabled = hasNext,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("下一题")
                }
            }
        }
    }
}

/** 底栏单个工具按钮（图标+文字） */
@Composable
private fun BottomToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp), enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) tint else tint.copy(alpha = 0.38f)
        )
    }
}

// ===== 交卷确认对话框 =====

@Composable
private fun SubmitConfirmDialog(
    stats: PaperStats,
    submitting: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("确认交卷") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // M-192：总数未知（unansweredCount=-1 哨兵）时显示 "?"，不把已装载部分当全量
                Text(
                    text = "已答 ${stats.answeredCount} / 未答 " +
                        (if (stats.unansweredCount >= 0) stats.unansweredCount.toString() else "?") +
                        " / 标记 ${stats.markedCount}",
                    style = MaterialTheme.typography.bodyMedium
                )
                if (stats.unansweredCount > 0) {
                    Text(
                        text = "还有${stats.unansweredCount}题未作答，确定交卷吗？",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (submitting) {
                    Text(
                        text = "正在交卷…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !submitting) {
                Text(if (submitting) "提交中" else "确认交卷")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, enabled = !submitting) { Text("取消") }
        }
    )
}

// ===== 背题模式答案遮层 =====

@Composable
private fun AnswerModeOverlay(
    slot: QuestionSlot?,
    modifier: Modifier = Modifier
) {
    val question = slot?.question
    if (question == null || slot.status is PracticeStatus.Loading) return

    val answerText = question.answerText?.trim().orEmpty()
    if (answerText.isEmpty()) return

    Surface(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "参考答案",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Text(
                    text = answerText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

// ===== 题目主体 =====

/** 单题主体：loading 骨架 / error 重试 / 渲染器分发（FR-PR-05） */
@Composable
fun QuestionBody(
    index: Int,
    state: PracticeUiState,
    viewModel: PracticeViewModel,
    onOpenAnalysis: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    onSkip: () -> Unit,
    showAnswerMode: Boolean = false,
    featureApis: PracticeFeatureApis? = null
) {
    val slot = state.slots.getOrNull(index) ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val st = slot.status) {
            PracticeStatus.Loading -> QuestionSkeleton()

            is PracticeStatus.Error ->
                com.toneup.app.ui.components.ErrorRetryCard(message = st.message, onRetry = onRetryLoad)

            else -> {
                val question = slot.question
                if (question != null) {
                    FormulaText(text = question.content, modifier = Modifier.fillMaxWidth())

                    if (slot.status is PracticeStatus.Submitted) {
                        val isCorrect = if (question.typeCode in listOf(QuestionType.Single.typeCode, QuestionType.Multi.typeCode)) {
                            slot.answer != null && run {
                                val myLabels = when (val a = slot.answer) {
                                    is AnswerValue.Choice -> listOf(a.label)
                                    is AnswerValue.MultiChoice -> a.labels
                                    else -> emptyList()
                                }
                                val correctLabels = when (question.typeCode) {
                                    QuestionType.Multi.typeCode -> CorrectAnswerParser.multiLabels(question.answerText).toSet()
                                    else -> setOfNotNull(CorrectAnswerParser.singleLabel(question.answerText))
                                }
                                myLabels.isNotEmpty() && myLabels.all { it in correctLabels } &&
                                    correctLabels.all { it in myLabels }
                            }
                        } else null
                        val myAnswer = when (val a = slot.answer) {
                            is AnswerValue.Choice -> a.label
                            is AnswerValue.MultiChoice -> a.labels.joinToString(", ")
                            else -> ""
                        }
                        val correctAnswer = question.answerText?.trim().orEmpty()
                        // 客观题判分条（主观题无对错判定，isCorrect=null 不渲染）
                        if (isCorrect != null) {
                            GradingResultBar(
                                isCorrect = isCorrect,
                                myAnswer = myAnswer,
                                correctAnswer = correctAnswer
                            )
                        }
                        if (isCorrect == false && featureApis != null) {
                            // M-188：加 questionId/index key，随题切换重置，
                            // 避免 QuestionBody 复用同一组合槽时残留上一题的移除/撤销状态
                            var removed by remember(question.questionId, index) { mutableStateOf(false) }
                            Spacer(Modifier.height(8.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = if (removed) "已从错题本移除" else "此题已加入错题本",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    if (!removed) {
                                        // H-58：原实现赋值自反（false→false/true→true），状态永不改变
                                        TextButton(onClick = { removed = true }) {
                                            Text("⊗ 移除错题本")
                                        }
                                    } else {
                                        TextButton(onClick = { removed = false }) {
                                            Text("撤销")
                                        }
                                    }
                                }
                            }
                        }

                        if (featureApis != null) {
                            Spacer(Modifier.height(8.dp))
                            FeedbackForm(
                                bankId = question.bankId,
                                questionId = question.questionId,
                                feedbackApi = featureApis.feedbackApi,
                                onSubmit = { },
                                onDismiss = { }
                            )
                        }

                        if (featureApis != null) {
                            Spacer(Modifier.height(12.dp))
                            NotesSharePanel(
                                questionId = question.questionId,
                                bankId = question.bankId,
                                notesSharedApi = featureApis.notesSharedApi,
                                myNoteText = "",
                                myNoteVisibility = false,
                                onMyNoteVisibilityChange = { },
                                onMyNoteSave = { /* 待接入笔记存储 */ }
                            )
                        }
                    }

                    val renderer = RendererRegistry.rendererFor(question.typeCode)
                    val questionContext = buildContext(
                        question, slot, viewModel, index, onSkip, showAnswerMode
                    )
                    if (renderer != null) {
                        renderer(questionContext)
                    } else {
                        FallbackRenderer(questionContext)
                    }
                }
            }
        }
    }
}

/** 判断当前题是否为阅读题组（有 passage 的 CLOZE/READING） */
private fun isReadingGroup(slot: QuestionSlot): Boolean {
    val q = slot.question ?: return false
    return q.typeCode in READING_GROUP_TYPES && !q.passage.isNullOrBlank()
}

private val READING_GROUP_TYPES = setOf(
    QuestionType.Cloze.typeCode,
    QuestionType.Reading.typeCode
)

/** 阅读题组：固定文章 + 翻动小题 */
@Composable
fun ReadingGroupBody(
    state: PracticeUiState,
    viewModel: PracticeViewModel,
    onOpenAnalysis: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    featureApis: PracticeFeatureApis? = null,
    showAnswerMode: Boolean = false
) {
    val slot = state.slots.getOrNull(state.currentIndex) ?: return
    val question = slot.question ?: return
    val passage = question.passage ?: return

    // 找到同一 passage 的所有题目
    val passageQuestions = state.slots.filter { s ->
        val q = s.question
        q != null && q.typeCode in READING_GROUP_TYPES && q.passage == passage
    }
    val currentSubIndex = passageQuestions.indexOfFirst {
        it.question?.questionId == question.questionId
    }.coerceAtLeast(0)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 固定文章区域（可滚动，限高）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .padding(bottom = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                Text(
                    "阅读文章",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(8.dp))
                FormulaText(text = passage)
            }
        }

        // 小题导航
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "第 ${currentSubIndex + 1}/${passageQuestions.size} 题",
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = {
                        val prev = passageQuestions.getOrNull(currentSubIndex - 1)
                        if (prev != null) viewModel.loadQuestion(state.slots.indexOf(prev))
                    },
                    enabled = currentSubIndex > 0
                ) { Text("上一题") }
                OutlinedButton(
                    onClick = {
                        val next = passageQuestions.getOrNull(currentSubIndex + 1)
                        if (next != null) viewModel.loadQuestion(state.slots.indexOf(next))
                    },
                    enabled = currentSubIndex < passageQuestions.size - 1
                ) { Text("下一题") }
            }
        }

        // 当前小题内容（占剩余空间）
        Box(modifier = Modifier.weight(1f)) {
            QuestionBody(
                index = state.currentIndex,
                state = state,
                viewModel = viewModel,
                onOpenAnalysis = onOpenAnalysis,
                onRetryLoad = onRetryLoad,
                onSkip = {
                    val next = passageQuestions.getOrNull(currentSubIndex + 1)
                    if (next != null) viewModel.loadQuestion(state.slots.indexOf(next))
                },
                featureApis = featureApis,
                showAnswerMode = showAnswerMode
            )
        }
    }
}

private fun buildContext(
    question: QuestionDto,
    slot: QuestionSlot,
    viewModel: PracticeViewModel,
    index: Int,
    onSkip: () -> Unit,
    showAnswerMode: Boolean = false
): QuestionContext {
    // 背题派生统一走 ReciteMode 纯函数（EC-02，JVM 单测覆盖）
    val derived = ReciteMode.deriveContext(
        submitted = slot.status is PracticeStatus.Submitted,
        objectiveType = question.typeCode in OBJECTIVE_TYPES,
        submitting = slot.status == PracticeStatus.Submitting,
        recite = showAnswerMode
    )
    return QuestionContext(
        question = question,
        answer = slot.answer,
        readonly = derived.readonly,
        disabled = derived.disabled,
        showAnswer = derived.showAnswer,
        showAnalysis = derived.showAnalysis,
        onAnswerChange = { viewModel.onAnswerChange(index, it) },
        onSubmitRequest = { viewModel.submitCurrent(index) },
        onToggleMark = { viewModel.toggleMark(index) },
        onRetryLoad = { viewModel.retryLoad(index) },
        onSkipQuestion = onSkip
    )
}

private val OBJECTIVE_TYPES = setOf(
    QuestionType.Single.typeCode,
    QuestionType.Multi.typeCode,
    QuestionType.Judge.typeCode,
    QuestionType.Cloze.typeCode,
    QuestionType.Reading.typeCode,
    QuestionType.Ordering.typeCode
)

/** FR-PR-09 题号面板：6 态着色 + 图例 + 筛选 + 收藏星标 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionGridPanel(
    slots: List<QuestionSlot>,
    currentIndex: Int,
    knownTotal: Int,
    hasMore: Boolean,
    favoritedIds: Set<Long> = emptySet(),
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var activeFilter by remember { mutableStateOf(GridFilter.All) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(bottom = 24.dp)
                .navigationBarsPadding()
        ) {
            // 六态图例
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegendItem(color = MaterialTheme.colorScheme.outline, label = "当前")
                LegendItem(color = MaterialTheme.colorScheme.primaryContainer, label = "已答")
                LegendItem(color = MaterialTheme.colorScheme.surfaceVariant, label = "未答")
                LegendItem(color = CorrectGreen, label = "答对")
                LegendItem(color = WrongRed, label = "答错")
                LegendItemStar(label = "已收藏")
            }

            // 筛选 Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GridFilter.entries.forEach { filter ->
                    FilterChip(
                        selected = activeFilter == filter,
                        onClick = { activeFilter = filter },
                        label = { Text(filter.label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            // 题号网格
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 340.dp)
            ) {
                val panelCount = maxOf(slots.size, if (knownTotal > 0) knownTotal else 0) +
                    if (hasMore) 1 else 0
                items(panelCount) { index ->
                    val slotState = slots.getOrNull(index)
                    val answered = slotState?.answer?.isEmpty == false ||
                        slotState?.status is PracticeStatus.Submitted
                    val isFavorited = slotState?.question?.questionId?.let { it in favoritedIds } == true
                    val correct = slotState?.let { isQuestionCorrect(it) }

                    // 筛选逻辑
                    val matchesFilter = when (activeFilter) {
                        GridFilter.All -> true
                        GridFilter.Wrong -> correct == false
                        GridFilter.Favorited -> isFavorited
                        GridFilter.Unanswered -> !answered
                    }
                    if (!matchesFilter) return@items

                    val bgColor = when {
                        correct == true -> CorrectGreen
                        correct == false -> WrongRed
                        slotState?.marked == true -> MaterialTheme.colorScheme.tertiaryContainer
                        answered -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val textColor = when {
                        correct == true || correct == false -> MaterialTheme.colorScheme.surface
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Surface(
                        shape = CircleShape,
                        color = bgColor,
                        modifier = Modifier.size(40.dp),
                        onClick = { onSelect(index) },
                        border = if (index == currentIndex) {
                            BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                        } else null
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                if (slotState == null) "+" else "${index + 1}",
                                style = MaterialTheme.typography.labelLarge,
                                color = textColor
                            )
                            if (isFavorited) {
                                Icon(
                                    imageVector = Icons.Filled.Star,
                                    contentDescription = "已收藏",
                                    tint = MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 图例：纯色圆点 + 标签 */
@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            shape = CircleShape,
            color = color,
            modifier = Modifier.size(10.dp)
        ) {}
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 图例：星标圆点 + 标签 */
@Composable
private fun LegendItemStar(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(10.dp)
        )
        Spacer(Modifier.width(3.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/** 判断题目是否答对 */
// H-59：三态判定——null 表示未提交/未作答/非客观题，不得折叠为“答错”
//（原实现把这些情况标红并计入“错题”筛选）
private fun isQuestionCorrect(slot: QuestionSlot): Boolean? {
    val question = slot.question ?: return null
    if (slot.status !is PracticeStatus.Submitted) return null
    val answer = slot.answer ?: return null
    val myLabels = when (answer) {
        is AnswerValue.Choice -> listOf(answer.label)
        is AnswerValue.MultiChoice -> answer.labels
        else -> return null
    }
    if (myLabels.isEmpty()) return null
    val correctLabels = when (question.typeCode) {
        QuestionType.Multi.typeCode -> CorrectAnswerParser.multiLabels(question.answerText).toSet()
        else -> setOfNotNull(CorrectAnswerParser.singleLabel(question.answerText))
    }
    return myLabels.all { it in correctLabels } && correctLabels.all { it in myLabels }
}

/** 筛选枚举 */
private enum class GridFilter(val label: String) {
    All("全部"),
    Wrong("只看错题"),
    Favorited("只看收藏"),
    Unanswered("只看未答")
}

/**
 * 边缘手势切题（§9.1）：仅响应左右边缘约 24dp 起手的横向滑动，
 * 避免与选项点击、文本光标选择冲突。
 */
@Composable
fun BoxWithEdgeSwipe(
    enabled: Boolean,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val edgeBandPx = with(density) { EDGE_BAND_DP.dp.toPx() }
    var containerWidth by remember { mutableStateOf(0f) }
    var hasFired by remember { mutableStateOf(false) }
    // M-190：拖动起点是否落在边缘带内，仅带内拖动才消费手势
    var startInEdgeBand by remember { mutableStateOf(false) }
    // M-189：始终捕获最新回调——pointerInput 仅以 enabled 为 key，
    // 手势协程重启前组合层传入的新 lambda 旧实现拿不到
    val currentOnSwipeLeft by rememberUpdatedState(onSwipeLeft)
    val currentOnSwipeRight by rememberUpdatedState(onSwipeRight)

    Box(
        modifier
            .onSizeChanged { containerWidth = it.width.toFloat() }
            .pointerInput(enabled) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        hasFired = false
                        // M-190：起点在边缘带内才标记消费，避免吞掉内部横向滚动/文本选择手势
                        startInEdgeBand = offset.x <= edgeBandPx ||
                            offset.x >= containerWidth - edgeBandPx
                    },
                    onHorizontalDrag = { change, amount ->
                        if (startInEdgeBand) {
                            change.consume()
                            if (enabled && !hasFired && amount != 0f) {
                                hasFired = true
                                if (amount < 0) currentOnSwipeLeft() else currentOnSwipeRight()
                            }
                        }
                    }
                )
            }
    ) {
        content()
    }
}

private const val EDGE_BAND_DP = 24

/** 沉浸模式：进入隐藏状态栏，退出恢复；图标颜色随深浅色（§4.4） */
@Composable
fun ImmersiveModeEffect() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (context as? Activity)?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, view)
                .hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        onDispose {
            window?.let {
                androidx.core.view.WindowCompat.getInsetsController(it, view)
                    .show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}

@Composable
private fun rememberHapticsPerformer(enabled: Boolean): (Haptic) -> Unit {
    val view = LocalView.current
    return remember(enabled, view) {
        { haptic ->
            if (enabled) performHaptic(view, haptic)
        }
    }
}
