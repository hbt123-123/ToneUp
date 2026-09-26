package com.toneup.app.ui.feature.practice

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.domain.logic.PracticeStatus

/**
 * 交卷检查页（FR-PR-09）：只读题号网格，4 列三态着色
 * （灰=未答 / 绿=已答 / 橙星=已标记），点击题号跳回刷题页对应题。
 */
// M-212：移除从未使用的 sessionId 死参数——hiltViewModel() 依据当前 NavBackStackEntry 解析 VM，参数本身无作用
@Composable
fun ReviewCheckScreen(
    onBack: () -> Unit,
    onSelectQuestion: (Int) -> Unit,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    // M-213：knownTotal 可能未知（<=0）或小于实际已装载题数，分母回退 slots.size 并把位置钳制在分母内，
    // 避免「第 15/? 题」「第 25/20 题」式异常，头部/进度条/已答行均与实际题数一致
    val totalCount = if (state.knownTotal > 0) state.knownTotal else state.slots.size
    val position = (state.currentIndex + 1).coerceIn(1, totalCount.coerceAtLeast(1))

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .statusBarsPadding()
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = buildString {
                    append("第 $position/")
                    append(if (totalCount > 0) totalCount.toString() else "?")
                    append(" 题")
                },
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.size(10.dp))
            LinearProgressIndicator(
                progress = {
                    if (totalCount > 0) {
                        (position.toFloat() / totalCount).coerceIn(0f, 1f)
                    } else 0f
                },
                modifier = Modifier.weight(1f).height(4.dp)
            )
        }

        Text(
            // M-213：已答行分母与头部共用 totalCount，knownTotal 滞后/未知时同步降级
            text = "已答 ${state.answeredCount}/${if (totalCount > 0) totalCount.toString() else "?"} 题",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // itemsIndexed 用真实位置：data class 的结构化 equals 会让
            // indexOf 永远命中第一个相同 slot，跳转错题且 O(n²)（C-5）
            // M-214：补稳定 key（bankId:questionId），题目未就位的 slot 回退位置 key，保证列表变化时项身份与状态复用正确
            itemsIndexed(state.slots, key = { index, slot ->
                slot.question?.let { "${it.bankId}:${it.questionId}" } ?: "pos$index"
            }) { index, slot ->
                val answered = slot.answer?.isEmpty == false ||
                    slot.status is PracticeStatus.Submitted
                Surface(
                    shape = CircleShape,
                    color = when {
                        slot.marked -> MaterialTheme.colorScheme.tertiaryContainer
                        answered -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    },
                    modifier = Modifier.size(56.dp),
                    onClick = { onSelectQuestion(index) },
                    border = if (index == state.currentIndex) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.outline)
                    } else null
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (slot.marked) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = "已标记",
                                tint = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(14.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
