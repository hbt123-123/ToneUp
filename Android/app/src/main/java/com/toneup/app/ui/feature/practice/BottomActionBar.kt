package com.toneup.app.ui.feature.practice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.toneup.app.domain.logic.PracticeStatus

/**
 * 底部操作栏（FR-PR-03）：上一题 / 确认答案或下一题 / 题号面板。
 * navigationBarsPadding 保证与全面屏手势区安全距离（§9.3）。
 */
@Composable
fun BottomActionBar(
    slot: QuestionSlot,
    canPrev: Boolean,
    hasNext: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSubmit: () -> Unit,
    onRetrySubmit: () -> Unit,
    onOpenGrid: () -> Unit,
    onOpenReviewCheck: () -> Unit,
    attemptId: Long?,
    onOpenAnalysis: (Long) -> Unit
) {
    val status = slot.status
    val isNetworkError = status is PracticeStatus.Error && status.isNetwork
    val submitting = status == PracticeStatus.Submitting

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPrev,
            enabled = canPrev && !submitting,
            modifier = Modifier.height(48.dp)
        ) {
            Text("上一题")
        }

        Column(Modifier.weight(1f)) {
            when {
                attemptId != null -> Button(
                    onClick = { onOpenAnalysis(attemptId) },
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("查看解析")
                }

                isNetworkError -> Button(
                    onClick = onRetrySubmit,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text("重试提交")
                }

                else -> Button(
                    onClick = onSubmit,
                    enabled = !submitting && slot.answer?.isEmpty == false &&
                        status !is PracticeStatus.Submitted,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Text(
                        when {
                            submitting -> "提交中…"
                            status is PracticeStatus.Submitted -> "已提交"
                            else -> "确认答案"
                        }
                    )
                }
            }
            if (slot.errorHint != null) {
                Text(
                    text = slot.errorHint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        OutlinedButton(
            onClick = onOpenReviewCheck,
            enabled = !submitting,
            modifier = Modifier.height(48.dp)
        ) {
            Text("交卷检查")
        }

        IconButton(onClick = onOpenGrid, enabled = !submitting) {
            Icon(Icons.Filled.Apps, contentDescription = "题号面板")
        }
    }
}
