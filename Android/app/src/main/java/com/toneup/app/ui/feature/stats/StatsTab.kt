package com.toneup.app.ui.feature.stats

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.EmptyState
import com.toneup.app.ui.components.ErrorRetryCard
import com.toneup.app.ui.components.SkeletonBlock

/** 统计 Tab（ST）：FR-ST-01~04 */
@Composable
fun StatsTab(viewModel: StatsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("学习统计", style = MaterialTheme.typography.titleLarge) }

        // FR-ST-03 时间范围切换 + 学科筛选
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(7 to "近7天", 30 to "近30天", null to "全部").forEach { (days, label) ->
                    FilterChip(
                        selected = state.rangeDays == days,
                        onClick = { viewModel.load(rangeDays = days) },
                        label = { Text(label) }
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.subjectId.isNullOrBlank(),
                    onClick = { viewModel.load(subjectId = "") },
                    label = { Text("全部学科") }
                )
                state.subjects.forEach { (id, name) ->
                    FilterChip(
                        selected = state.subjectId == id,
                        onClick = { viewModel.load(subjectId = id) },
                        label = { Text(name) }
                    )
                }
            }
        }

        // FR-ST-01 总览卡
        when (val overview = state.overview) {
            is Load.Loading -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkeletonBlock(Modifier.height(20.dp))
                    SkeletonBlock()
                }
            }
            is Load.Failed -> item { ErrorRetryCard(overview.message, onRetry = { viewModel.load() }) }
            is Load.Ready -> item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OverviewCard("正确率", "${(overview.value.accuracyRate * 100).toInt()}%", Modifier.weight(1f))
                    OverviewCard("刷题量", "${overview.value.totalAttempts}", Modifier.weight(1f))
                    OverviewCard("连续天数", "${overview.value.streakDays}", Modifier.weight(1f))
                }
            }
        }

        // FR-ST-02 薄弱项列表 + FR-ST-04 Canvas 图表
        item { Text("薄弱项", style = MaterialTheme.typography.titleMedium) }
        when (val weak = state.weaknesses) {
            is Load.Loading -> items((1..4).toList()) { SkeletonBlock() }
            is Load.Failed -> item { ErrorRetryCard(weak.message, onRetry = { viewModel.load() }) }
            is Load.Ready -> {
                if (weak.value.isEmpty()) {
                    item { EmptyState("暂无薄弱项数据") }
                } else {
                    item {
                        AccuracyBarChart(
                            entries = weak.value.take(5).map { w ->
                                val label = w.tagName ?: w.typeCode ?: w.subjectName ?: "未知"
                                label to w.accuracyRate
                            }
                        )
                    }
                    items(weak.value) { w ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                val label = w.tagName ?: w.typeCode ?: w.subjectName ?: "未知"
                                Text(label, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "作答 ${w.attemptCount} 次 · 正确率 ${(w.accuracyRate * 100).toInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // FR-ST-05 刷题趋势
        item { Text("刷题趋势", style = MaterialTheme.typography.titleMedium) }
        item {
            // TODO: StatsUiState 暂未提供 dailyCounts，接入真实数据后替换 emptyList()
            SevenDayTrendChart(
                dailyCounts = emptyList(),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OverviewCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineMedium)
            Text(title, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Compose Canvas 轻量柱状图（深浅色均清晰） */
@Composable
private fun AccuracyBarChart(entries: List<Pair<String, Double>>) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.forEach { (label, rate) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 72.dp, height = 16.dp),
                    maxLines = 1
                )
                Box(Modifier.weight(1f).height(14.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(size.width * rate.toFloat().coerceIn(0f, 1f), size.height),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
                Text(
                    "${(rate * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/** 7天刷题趋势 — Canvas 条形图 */
@Composable
private fun SevenDayTrendChart(
    dailyCounts: List<Pair<String, Int>>,
    modifier: Modifier = Modifier
) {
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    if (dailyCounts.isEmpty()) {
        EmptyState("暂无刷题趋势数据")
        return
    }

    val maxCount = dailyCounts.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        dailyCounts.takeLast(7).forEach { (date, count) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.size(width = 72.dp, height = 16.dp),
                    maxLines = 1
                )
                Box(Modifier.weight(1f).height(14.dp)) {
                    Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                        drawRoundRect(
                            color = trackColor,
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                        drawRoundRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(
                                size.width * (count.toFloat() / maxCount).coerceIn(0f, 1f),
                                size.height
                            ),
                            cornerRadius = CornerRadius(6f, 6f)
                        )
                    }
                }
                Text(
                    "$count",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}
