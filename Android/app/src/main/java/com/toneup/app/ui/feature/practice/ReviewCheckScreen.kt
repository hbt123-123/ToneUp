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
import androidx.compose.foundation.lazy.grid.items
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
@Composable
fun ReviewCheckScreen(
    sessionId: String,
    onBack: () -> Unit,
    onSelectQuestion: (Int) -> Unit,
    viewModel: PracticeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
                    append("第 ${state.currentIndex + 1}/")
                    append(if (state.knownTotal > 0) state.knownTotal.toString() else "?")
                    append(" 题")
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
        }

        Text(
            text = "已答 ${state.answeredCount}/${state.knownTotal.coerceAtLeast(0)} 题",
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
            items(state.slots) { slot ->
                val index = state.slots.indexOf(slot)
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
