package com.toneup.app.ui.feature.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SummaryScreen(
    stats: PaperStats,
    totalTime: Int,
    serverSessionId: Long? = null,
    onReviewWrong: () -> Unit,
    onPracticeAgain: () -> Unit,
    onBackToList: () -> Unit,
    onBack: () -> Unit,
    viewModel: SummaryViewModel = hiltViewModel()
) {
    // EC-01：服务端会话拉取服务端 summary 覆盖本地估算
    val serverSummary by viewModel.serverSummary.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(serverSessionId) {
        serverSessionId?.let { viewModel.loadServerResult(it) }
    }
    val localState = viewModel.buildState(stats, totalTime)
    val uiState = serverSummary ?: localState

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本轮练习小结") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatRow(label = "总题数", value = "${uiState.totalCount}")
                    StatRow(label = "已答", value = "${uiState.answeredCount}")
                    StatRow(
                        label = "正确率",
                        value = "${uiState.correctRate}%",
                        valueColor = if (uiState.correctRate >= 60) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    StatRow(label = "用时", value = uiState.formattedTime)
                    StatRow(
                        label = "错题数",
                        value = "${uiState.wrongCount}",
                        valueColor = if (uiState.wrongCount > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                    if (uiState.serverBacked) {
                        Text(
                            text = "已同步服务端成绩",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Button(
                onClick = onReviewWrong,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = uiState.wrongCount > 0
            ) {
                Text("回顾错题")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onPracticeAgain,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("再练一轮")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onBackToList,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("返回专题页")
            }
        }
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
