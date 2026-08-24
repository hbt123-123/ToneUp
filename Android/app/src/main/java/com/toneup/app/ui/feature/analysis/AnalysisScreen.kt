package com.toneup.app.ui.feature.analysis

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Help
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.AttemptResultDto
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.logic.SubQuestionParser
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.ErrorRetryCard
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.theme.CorrectGreen
import com.toneup.app.ui.theme.WrongRed

/**
 * 解析视图（AN）：FR-AN-01~07。
 * 我的答案 vs 正确答案（颜色+图标+文字三通道）；主观题判分状态卡；
 * 笔记区；拍照纠错入口；平滑展开动画。
 */
@Composable
fun AnalysisScreen(
    onExit: () -> Unit,
    onOpenAiPhoto: (bankId: String, questionId: Long, attemptId: Long?) -> Unit,
    onRetryQuestion: (bankId: String, questionId: Long) -> Unit = { _, _ -> },
    viewModel: AnalysisViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var analysisExpanded by rememberSaveable { mutableStateOf(true) }
    var showNoteLeaveDialog by remember { mutableStateOf(false) }

    when (val attemptLoad = state.attempt) {
        is Load.Loading -> Column(Modifier.fillMaxSize()) {
            CircularProgressIndicator()
        }

        is Load.Failed -> ErrorRetryCard(message = attemptLoad.message, onRetry = { viewModel.load() })

        is Load.Ready -> {
            val result = attemptLoad.value
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ResultHeader(result, state.gradingStatus)

                // FR-AN-04 主观题判分状态卡
                if (state.gradingStatus != null || result.isCorrect == null && state.question?.typeCode in SUBJECTIVE_TYPES) {
                    GradingStatusCard(
                        status = state.gradingStatus,
                        timedOut = state.pollTimedOut,
                        feedback = result.feedback,
                        busy = state.selfJudgeBusy,
                        onRetryPoll = { viewModel.retryPolling() },
                        onSelfJudge = { correct -> viewModel.submitSelfJudge(correct) }
                    )
                }

                // FR-AN-01/02 答案对比
                AnswerComparisonCard(state)

                // FR-AN-07 平滑展开解析
                Button(
                    onClick = { analysisExpanded = !analysisExpanded },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (analysisExpanded) "收起官方解析" else "展开官方解析")
                }
                AnimatedVisibility(
                    visible = analysisExpanded,
                    enter = fadeIn() + expandVertically()
                ) {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("官方解析", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.size(8.dp))
                            if (result.solution.isNullOrBlank()) {
                                Text(
                                    "暂无解析内容",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                FormulaText(text = result.solution)
                            }
                        }
                    }
                }

                // FR-AN-05 笔记区
                NotesSection(
                    noteText = state.noteText,
                    dirty = state.noteDirty,
                    hint = state.noteSavedAtHint,
                    onChange = { viewModel.onNoteChange(it) },
                    onSave = { viewModel.saveNote() }
                )

                // FR-AN-06 拍照纠错入口
                if (result.bankId != null && result.questionId != null) {
                    OutlinedButton(
                        onClick = {
                            onOpenAiPhoto(result.bankId!!, result.questionId!!, result.attemptId)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("拍照纠错（AI 诊断手写过程）")
                    }
                    OutlinedButton(
                        onClick = { onRetryQuestion(result.bankId!!, result.questionId!!) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("重做此题")
                    }
                }

                state.errorHint?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showNoteLeaveDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNoteLeaveDialog = false },
            title = { Text("笔记未保存") },
            text = { Text("保存当前笔记再离开？") },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveNote { showNoteLeaveDialog = false }
                }) { Text("保存") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showNoteLeaveDialog = false
                    onExit()
                }) { Text("不保存离开") }
            }
        )
    }
}

private val SUBJECTIVE_TYPES = setOf("FILL_BLANK", "SOLUTION", "TRANSLATION", "ESSAY")

@Composable
private fun ResultHeader(result: AttemptResultDto, gradingStatus: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val icon: androidx.compose.ui.graphics.vector.ImageVector
        val color: Color
        val label: String
        when {
            result.isCorrect == true -> {
                icon = Icons.Filled.CheckCircle; color = CorrectGreen; label = "回答正确"
            }
            result.isCorrect == false -> {
                icon = Icons.Filled.Cancel; color = WrongRed; label = "回答错误"
            }
            else -> {
                icon = Icons.Filled.Help; color = MaterialTheme.colorScheme.tertiary
                label = "待判分"
            }
        }
        Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        result.score?.let {
            Text("得分：$it", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** 判分四态状态卡 + 自评兜底（§10.4） */
@Composable
fun GradingStatusCard(
    status: String?,
    timedOut: Boolean,
    feedback: com.toneup.app.data.remote.dto.AiFeedbackDto?,
    busy: Boolean,
    onRetryPoll: () -> Unit,
    onSelfJudge: (Boolean) -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AI 判分", style = MaterialTheme.typography.titleMedium)
            when {
                status == AttemptResultDto.GRADING_QUEUED ||
                    status == AttemptResultDto.GRADING_PROCESSING -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(if (status == AttemptResultDto.GRADING_QUEUED) "排队中…" else "AI 正在批改…")
                    }
                }

                status == AttemptResultDto.GRADING_SUCCEEDED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.CheckCircle, contentDescription = null,
                            tint = CorrectGreen
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = buildString {
                                append(feedback?.isCorrect?.let { if (it) "判定正确" } ?: "已出分")
                                feedback?.errorReason?.let { reason -> append("：$reason") }
                            }.ifBlank { "判分完成" }
                        )
                    }
                }

                status == AttemptResultDto.GRADING_FAILED || timedOut -> {
                    Text(
                        text = feedback?.errorMessage ?: "AI 判分暂不可用",
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onRetryPoll, enabled = !busy) {
                            Text(if (timedOut) "继续等待" else "重试查询")
                        }
                        Text("或自己判断：", modifier = Modifier.align(Alignment.CenterVertically))
                    }
                    SelfJudgeRow(busy, onSelfJudge)
                }

                else -> Text("状态未知")
            }
        }
    }
}

@Composable
private fun SelfJudgeRow(busy: Boolean, onSelfJudge: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onSelfJudge(true) }, enabled = !busy) { Text("我做对了") }
        OutlinedButton(onClick = { onSelfJudge(false) }, enabled = !busy) { Text("我做错了") }
    }
}

@Composable
private fun AnswerComparisonCard(state: AnalysisUiState) {
    val result = (state.attempt as? Load.Ready)?.value ?: return
    val question = state.question
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("答案对比", style = MaterialTheme.typography.titleMedium)

            // 我的答案
            Row {
                Icon(Icons.Filled.Help, contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.size(8.dp))
                Text("我的答案：", style = MaterialTheme.typography.labelLarge)
            }
            FormulaText(text = myAnswerText(result, question))

            // 正确答案（绿）
            Row(modifier = Modifier.semantics { contentDescription = "正确答案" }) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = CorrectGreen)
                Spacer(Modifier.size(8.dp))
                Text("正确答案：", style = MaterialTheme.typography.labelLarge)
            }
            FormulaText(text = result.answerText ?: "（待判分后展示）")
        }
    }
}

private fun myAnswerText(result: AttemptResultDto, question: QuestionDto?): String =
    "见提交作答记录" // 服务端未回显用户答案时以提示文案兜底，避免误导
