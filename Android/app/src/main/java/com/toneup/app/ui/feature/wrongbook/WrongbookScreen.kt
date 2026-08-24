package com.toneup.app.ui.feature.wrongbook

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.WrongbookItemDto
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.EmptyState
import com.toneup.app.ui.components.ErrorRetryCard
import com.toneup.app.ui.components.QuestionSkeleton
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.navigation.Routes
import androidx.navigation.NavHostController

/** 错题本（WB，二级页）：FR-WB-01~04 */
@Composable
fun WrongbookScreen(
    rootNavController: NavHostController,
    onBack: () -> Unit,
    viewModel: WrongbookViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("错题本", style = MaterialTheme.typography.titleLarge)
        }

        // 学科筛选
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                FilterChip(
                    selected = state.subjectId.isNullOrBlank(),
                    onClick = { viewModel.refresh("", state.typeCode) },
                    label = { Text("全部学科") }
                )
            }
            items(state.catalog?.subjects ?: emptyList(), key = { it.id }) { subject ->
                FilterChip(
                    selected = state.subjectId == subject.id,
                    onClick = { viewModel.refresh(subject.id, state.typeCode) },
                    label = { Text(subject.name) }
                )
            }
        }

        when (val load = state.items) {
            is Load.Loading -> QuestionSkeleton(Modifier.padding(16.dp))
            is Load.Failed -> ErrorRetryCard(message = load.message, onRetry = { viewModel.refresh() })
            is Load.Ready -> {
                if (load.value.isEmpty()) {
                    EmptyState("暂无错题记录，继续保持！")
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(load.value, key = {
                            "${it.bankId}:${it.questionId}"
                        }) { item ->
                            WrongbookCard(
                                item = item,
                                onRedo = { viewModel.redo(item) { sessionId ->
                                    rootNavController.navigate(Routes.practice(sessionId))
                                } }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WrongbookCard(item: WrongbookItemDto, onRedo: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${item.year} · ${item.typeCode} · 错 ${item.wrongCount} 次",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
            FormulaText(text = item.content.take(150))

            // FR-WB-04 掌握度展开详情
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Spacer(Modifier.size(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "掌握度：${item.masteryLevel?.let { "Level $it" } ?: "未评估"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    item.lastWrongAt?.let { lastWrong ->
                        Text(
                            text = "最近答错：$lastWrong",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "收起详情" else "查看详情")
                }
                OutlinedButton(onClick = onRedo) { Text("重做此题") }
            }
        }
    }
}
