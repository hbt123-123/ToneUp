package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.dto.QuestionDto
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.components.question.QuestionContext

/** §6.4 未知题型降级卡：含原始 code、重试与跳过，绝不崩溃 */
@Composable
fun FallbackRenderer(
    context: QuestionContext,
    typeCode: String = context.question.typeCode
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("该题型暂不支持渲染", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "题型代码：$typeCode（题号 ${context.question.questionId}），可截图反馈给开发者",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.onRetryLoad?.invoke() }) {
                    Text("重试")
                }
                OutlinedButton(onClick = { context.onSkipQuestion?.invoke() }) {
                    Text("跳过本题")
                }
            }
        }
    }
}
