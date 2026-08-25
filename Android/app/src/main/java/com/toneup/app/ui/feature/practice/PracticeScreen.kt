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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.logic.PracticeStatus
import com.toneup.app.domain.model.QuestionType
import com.toneup.app.ui.LocalToneUpPreferences
import com.toneup.app.ui.components.Haptic
import com.toneup.app.ui.components.QuestionSkeleton
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.components.performHaptic
import com.toneup.app.ui.components.question.QuestionContext
import com.toneup.app.ui.components.question.RendererRegistry
import com.toneup.app.ui.feature.practice.renderers.FallbackRenderer

/**
 * 刷题页（PR）：沉浸模式、极简顶栏、题型分发渲染、底部操作栏、
 * 边缘手势切题（共享轴 X）、题号面板、待同步横幅。
 */
@Composable
fun PracticeScreen(
    onExit: () -> Unit,
    onOpenAnalysis: (Long) -> Unit,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferences = LocalToneUpPreferences.current
    val haptics = rememberHapticsPerformer(preferences.hapticsEnabled)
    var showGridPanel by remember { mutableStateOf(false) }
    var showExitDialog by remember { mutableStateOf(false) }

    ImmersiveModeEffect()

    val slot = state.slots.getOrNull(state.currentIndex) ?: return

    Column(Modifier.fillMaxSize()) {
        // 顶部极简栏（FR-PR-01）
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (slot.status is PracticeStatus.Editing) showExitDialog = true else onExit()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出刷题")
            }
            Text(
                text = buildString {
                    append(if (state.mode == "review") "今日复习 · " else "")
                    append("${state.currentIndex + 1}/")
                    append(if (state.knownTotal > 0) state.knownTotal.toString() else "?")
                    append(" · 已答${state.answeredCount}")
                },
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.size(10.dp))
            LinearProgressIndicator(
                progress = {
                    if (state.knownTotal > 0) {
                        ((state.currentIndex + 1f) / state.knownTotal).coerceIn(0f, 1f)
                    } else 0f
                },
                modifier = Modifier.weight(1f).height(4.dp)
            )
            Spacer(Modifier.size(10.dp))
            IconButton(
                onClick = {
                    viewModel.toggleMark(state.currentIndex)
                    haptics(Haptic.LIGHT_IMPACT)
                }
            ) {
                Icon(
                    imageVector = if (slot.marked) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    contentDescription = if (slot.marked) "取消标记" else "标记本题",
                    tint = if (slot.marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
            }
        }

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
                QuestionBody(
                    index = index,
                    state = state,
                    viewModel = viewModel,
                    onOpenAnalysis = onOpenAnalysis,
                    onRetryLoad = { viewModel.retryLoad(index) },
                    onSkip = { viewModel.loadQuestion(index + 1) }
                )
            }
        }

        BottomActionBar(
            slot = slot,
            canPrev = state.currentIndex > 0,
            hasNext = canGoNext,
            onPrev = { viewModel.loadQuestion(state.currentIndex - 1) },
            onNext = { viewModel.loadQuestion(state.currentIndex + 1) },
            onSubmit = { haptics(Haptic.LIGHT_IMPACT); viewModel.submitCurrent(state.currentIndex) },
            onRetrySubmit = { viewModel.retrySubmit(state.currentIndex) },
            onOpenGrid = { showGridPanel = true },
            attemptId = (slot.status as? PracticeStatus.Submitted)?.attemptId,
            onOpenAnalysis = onOpenAnalysis
        )
    }

    if (showGridPanel) {
        QuestionGridPanel(
            slots = state.slots,
            currentIndex = state.currentIndex,
            knownTotal = state.knownTotal,
            hasMore = state.hasMore,
            onSelect = { index ->
                showGridPanel = false
                viewModel.loadQuestion(index)
            },
            onDismiss = { showGridPanel = false }
        )
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("退出刷题") },
            text = { Text("存在未提交的编辑，保存草稿并退出？") },
            confirmButton = {
                Button(onClick = {
                    showExitDialog = false
                    onExit()
                }) { Text("保存草稿并退出") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showExitDialog = false }) { Text("继续作答") }
            }
        )
    }
}

/** 单题主体：loading 骨架 / error 重试 / 渲染器分发（FR-PR-05） */
@Composable
fun QuestionBody(
    index: Int,
    state: PracticeUiState,
    viewModel: PracticeViewModel,
    onOpenAnalysis: (Long) -> Unit,
    onRetryLoad: () -> Unit,
    onSkip: () -> Unit
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
                    val renderer = RendererRegistry.rendererFor(question.typeCode)
                    val questionContext = buildContext(question, slot, viewModel, index, onSkip)
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

private fun buildContext(
    question: QuestionDto,
    slot: QuestionSlot,
    viewModel: PracticeViewModel,
    index: Int,
    onSkip: () -> Unit
): QuestionContext = QuestionContext(
    question = question,
    answer = slot.answer,
    readonly = slot.status is PracticeStatus.Submitted,
    disabled = slot.status == PracticeStatus.Submitting,
    showAnswer = slot.status is PracticeStatus.Submitted && question.typeCode in OBJECTIVE_TYPES,
    showAnalysis = slot.status is PracticeStatus.Submitted,
    onAnswerChange = { viewModel.onAnswerChange(index, it) },
    onSubmitRequest = { viewModel.submitCurrent(index) },
    onToggleMark = { viewModel.toggleMark(index) },
    onRetryLoad = { viewModel.retryLoad(index) },
    onSkipQuestion = onSkip
)

private val OBJECTIVE_TYPES = setOf(
    QuestionType.Single.typeCode,
    QuestionType.Multi.typeCode,
    QuestionType.Judge.typeCode,
    QuestionType.Cloze.typeCode,
    QuestionType.Reading.typeCode,
    QuestionType.Ordering.typeCode
)

/** FR-PR-09 题号面板：已答/未答/已标记三态着色 + 未答筛选由列表顺序体现 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionGridPanel(
    slots: List<QuestionSlot>,
    currentIndex: Int,
    knownTotal: Int,
    hasMore: Boolean,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.heightIn(max = 380.dp).padding(bottom = 24.dp)
        ) {
            // 分页模式下展示未装载的题号（含 hasMore 时的“更多”占位），点击由 ensureSlot 装载
            val panelCount = maxOf(slots.size, if (knownTotal > 0) knownTotal else 0) +
                if (hasMore) 1 else 0
            items(panelCount) { index ->
                val slotState = slots.getOrNull(index)
                val answered = slotState?.answer?.isEmpty == false ||
                    slotState?.status is PracticeStatus.Submitted
                Surface(
                    shape = CircleShape,
                    color = when {
                        slotState?.marked == true -> MaterialTheme.colorScheme.tertiaryContainer
                        answered -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(40.dp),
                    onClick = { onSelect(index) },
                    border = if (index == currentIndex) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                    } else null
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            if (slotState == null) "+" else "${index + 1}",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
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
    var startX by remember { mutableStateOf(0f) }
    var hasFired by remember { mutableStateOf(false) }

    Box(
        modifier
            .onSizeChanged { containerWidth = it.width.toFloat() }
            .pointerInput(enabled) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        startX = offset.x
                        hasFired = false
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        if (enabled && !hasFired && amount != 0f) {
                            val isLeftEdge = startX <= edgeBandPx
                            val isRightEdge = startX >= containerWidth - edgeBandPx
                            if (isLeftEdge || isRightEdge) {
                                hasFired = true
                                if (amount < 0) onSwipeLeft() else onSwipeRight()
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
