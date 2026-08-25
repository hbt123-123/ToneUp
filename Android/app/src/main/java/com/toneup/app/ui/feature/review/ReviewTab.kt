package com.toneup.app.ui.feature.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.ReviewItemDto
import com.toneup.app.data.repository.AppException
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.EmptyState
import com.toneup.app.ui.components.ErrorRetryCard
import com.toneup.app.ui.components.QuestionSkeleton
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.navigation.Routes
import androidx.navigation.NavHostController

/**
 * 今日复习 Tab（RV）：
 * FR-RV-01 列表；FR-RV-02 复习模式进入刷题；FR-RV-03 暂缓（服务端无撤销端点，提示不可撤销）；
 * FR-RV-04 空态鼓励语；FR-RV-05 下拉刷新。
 */
@Composable
fun ReviewTab(
    rootNavController: NavHostController,
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // FR-RV-03 暂缓成功短暂提示；服务端无撤销端点，不提供撤销动作。
    // 展示完即消费，避免重进 Tab 时重放旧提示
    LaunchedEffect(state.lastSkipped) {
        state.lastSkipped?.let {
            snackbarHostState.showSnackbar(
                message = "已暂缓 1 题（暂缓后不可撤销）",
                duration = SnackbarDuration.Short
            )
            viewModel.consumeSkipNotice()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "今日复习",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp)
        )

        state.errorHint?.let { hint ->
            Text(
                text = hint,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        when (val itemsLoad = state.items) {
            is Load.Loading -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                QuestionSkeleton()
                QuestionSkeleton()
            }

            is Load.Failed -> ErrorRetryCard(message = itemsLoad.message, onRetry = { viewModel.refresh() })

            is Load.Ready -> {
                val items = itemsLoad.value
                if (items.isEmpty()) {
                    // FR-RV-04 空态文案 + 去刷题入口
                    EmptyState(
                        text = "今日复习已完成，太棒了！保持节奏，上岸在望。",
                        action = {
                            Button(onClick = { rootNavController.navigate(Routes.TAB_BANK) }) {
                                Text("去刷题")
                            }
                        }
                    )
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Button(
                                onClick = { viewModel.startReview { sessionId ->
                                    rootNavController.navigate(Routes.practice(sessionId, mode = "review"))
                                } },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("开始复习（${items.size} 题）") }
                        }
                        items(items, key = { it.questionId }) { item ->
                            ReviewItemCard(
                                item = item,
                                busy = item.questionId in state.skippingIds,
                                onSkip = { viewModel.skip(item) },
                                onClick = { viewModel.startReview { sessionId ->
                                    rootNavController.navigate(Routes.practice(sessionId, mode = "review"))
                                } }
                            )
                        }
                    }
                }
            }
        }

        // FR-RV-03 暂缓提示：由 SnackbarHost 托管，Short 时长自动消失
        SnackbarHost(snackbarHostState)
    }
}

@Composable
private fun ReviewItemCard(
    item: ReviewItemDto,
    busy: Boolean,
    onSkip: () -> Unit,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .clickableNoRipple(onClick),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = "${item.subjectName ?: item.subjectId ?: "综合"} · ${typeLabel(item.typeCode)}" +
                            (item.estimatedSeconds?.let { " · 约 ${(it / 60).coerceAtLeast(1)} 分钟" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FormulaText(text = item.content.take(120))
            }
            OutlinedButton(onClick = onSkip, enabled = !busy) {
                Text(if (busy) "…" else "暂缓")
            }
        }
    }
}

@Composable
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(role = Role.Button, onClick = onClick)

private fun typeLabel(code: String): String = when (code) {
    "SINGLE" -> "单选"; "MULTI" -> "多选"; "JUDGE" -> "判断"
    "FILL_BLANK" -> "填空"; "SOLUTION" -> "解答"; "CLOZE" -> "完形"
    "READING" -> "阅读"; "ORDERING" -> "排序"; "TRANSLATION" -> "翻译"
    "ESSAY" -> "作文"; else -> code
}
