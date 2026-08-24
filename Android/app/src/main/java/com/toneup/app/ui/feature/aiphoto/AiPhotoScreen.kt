package com.toneup.app.ui.feature.aiphoto

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.toneup.app.ui.theme.CorrectGreen
import com.toneup.app.ui.theme.WrongRed
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * AI 拍照纠错流程页（AI）：FR-AI-01~07。
 * 相机 → 预览确认 → 压缩上传 → 轮询四态 → 结构化诊断卡；重试与自评兜底。
 */
@Composable
fun AiPhotoScreen(
    onBack: () -> Unit,
    viewModel: AiPhotoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionFlow = rememberCameraPermissionFlow()

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(4.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("拍照纠错", style = MaterialTheme.typography.titleLarge)
        }

        CameraPermissionDialogs(permissionFlow)

        when (val step = state.step) {
            is AiFlowStep.Camera -> {
                if (permissionFlow.state == CameraPermissionState.GRANTED) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        CameraCaptureView(
                            onCaptured = { viewModel.onCaptured(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    Column(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "拍照纠错需要相机权限",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "授权后可拍摄手写答案，由 AI 诊断解题过程",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(16.dp))
                        Button(onClick = permissionFlow.request, enabled = true) {
                            Text("去开启相机权限")
                        }
                    }
                }
            }

            is AiFlowStep.ConfirmPreview -> {
                // FR-AI-01 拍后确认预览（可重拍）
                AsyncImage(
                    model = step.file,
                    contentDescription = "拍照结果预览",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                )
                Row(
                    Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(onClick = { viewModel.retake() }, modifier = Modifier.weight(1f)) {
                        Text("重拍")
                    }
                    Button(
                        onClick = {
                            viewModel.confirmAndUpload { raw ->
                                kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    ImageCompressor.compress(
                                        context,
                                        raw,
                                        File(context.cacheDir, "toneup_ai_${System.currentTimeMillis()}.jpg")
                                    )
                                }
                            }
                        },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.busy) "处理中…" else "上传诊断")
                    }
                }
            }

            is AiFlowStep.Uploading -> FullScreenBusy("正在上传…")

            is AiFlowStep.Polling -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.size(14.dp))
                    Text("AI 正在诊断你的手写过程…")
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
            }

            is AiFlowStep.Result -> DiagnosisResultCard(
                outcome = step.outcome,
                onRedoQuestion = onBack,
                busy = state.busy,
                onSelfJudge = { correct -> viewModel.submitSelfJudge(correct); },
                errorHint = state.errorHint
            )

            is AiFlowStep.Failure -> {
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Filled.Cancel, null, tint = WrongRed)
                    Text(step.message, color = MaterialTheme.colorScheme.error)
                    if (step.canRetryUpload) {
                        Button(onClick = { viewModel.retryUpload() }, enabled = !state.busy) {
                            Text("重试上传")
                        }
                    }
                    SelfJudgeEntry(busy = state.busy, onSelfJudge = { correct -> viewModel.submitSelfJudge(correct) })
                }
            }
        }
    }
}

@Composable
private fun FullScreenBusy(text: String) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator()
        Text(text, Modifier.padding(top = 12.dp))
    }
}

/** §10.3 结构化诊断卡片：对/错大标识 + 中文原因 + 知识点标签 Chip + 重做此题 */
@Composable
private fun DiagnosisResultCard(
    outcome: com.toneup.app.data.repository.AiFeedbackDetailResult,
    onRedoQuestion: () -> Unit,
    busy: Boolean,
    onSelfJudge: (Boolean) -> Unit,
    errorHint: String?
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = if (outcome.isCorrect == true) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (outcome.isCorrect == true) "判定正确" else "判定有误",
            tint = if (outcome.isCorrect == true) CorrectGreen else WrongRed,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = if (outcome.isCorrect == true) "这道题做对了" else "发现解题问题",
            style = MaterialTheme.typography.titleLarge
        )

        outcome.errorReason?.let {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("错误原因", style = MaterialTheme.typography.titleSmall)
                    Text(it, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (outcome.tagIds.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                outcome.tagIds.take(6).forEach { tagId ->
                    androidx.compose.material3.SuggestionChip(
                        onClick = {},
                        label = { Text("知识点 $tagId") }
                    )
                }
            }
        }

        if (errorHint != null) {
            Text(errorHint, color = MaterialTheme.colorScheme.error)
        }

        Button(onClick = onRedoQuestion, modifier = Modifier.fillMaxWidth()) {
            Text("重做此题")
        }

        Text("AI 结论不对？", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelfJudgeEntry(busy, onSelfJudge = onSelfJudge)
    }
}

@Composable
private fun SelfJudgeEntry(busy: Boolean, onSelfJudge: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = { onSelfJudge(true) }, enabled = !busy) { Text("我做对了") }
        OutlinedButton(onClick = { onSelfJudge(false) }, enabled = !busy) { Text("我做错了") }
    }
}
