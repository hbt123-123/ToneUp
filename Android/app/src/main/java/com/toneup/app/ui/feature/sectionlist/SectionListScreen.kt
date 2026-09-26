package com.toneup.app.ui.feature.sectionlist

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.toneup.app.data.remote.dto.SectionItem
import com.toneup.app.domain.logic.SessionCountPolicy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionListScreen(
    onNavigateToPractice: (bankId: String, year: Int?, typeCode: String?, count: Int?) -> Unit,
    onCreateSession: (
        bankId: String, collectionIds: List<Long>?, year: Int?, typeCode: String?, count: Int
    ) -> Unit,
    viewModel: SectionListViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showSelectDialog by remember { mutableStateOf(false) }
    var selectedSection by remember { mutableStateOf<SectionItem?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = state.category.ifBlank { "题库" }) }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 顶部 tab：真题 / 专题 / 全部
            SectionTabRow(
                selectedTab = state.selectedTab,
                onSelect = viewModel::selectTab
            )

            // 主体内容
            when {
                state.isLoading -> {
                    // M-226：占位列表与主列表同样以 weight(1f) 约束，避免无界高度铺满挤压底部筛选行
                    ShimmerSectionList(modifier = Modifier.weight(1f))
                }
                state.error != null && state.filteredSections.isEmpty() -> {
                    Box(
                        // M-226：错误态占位以 weight(1f) 约束高度，避免无界高度破坏 Column 布局
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = state.error ?: "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(onClick = viewModel::retry) {
                                Text("重试")
                            }
                        }
                    }
                }
                state.filteredSections.isEmpty() -> {
                    Box(
                        // M-226：空态占位同样以 weight(1f) 约束高度
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "暂无分组数据",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            vertical = 12.dp
                        )
                    ) {
                        // M-229：hashCode 作 key 跨重组/重载不稳定，改用 year/title 优先、index 兜底的稳定组合
                        itemsIndexed(state.filteredSections, key = { index, section ->
                            sectionKey(section, index)
                        }) { _, section ->
                            SectionCard(
                                section = section,
                                onContinue = {
                                    onNavigateToPractice(
                                        state.bankId,
                                        section.year,
                                        section.types.firstOrNull()?.typeCode,
                                        null
                                    )
                                },
                                onSelect = {
                                    selectedSection = section
                                    showSelectDialog = true
                                }
                            )
                        }
                    }
                }
            }

            // 底部筛选 chip 行
            FilterChipRow(
                selectedFilter = state.filterTab,
                onSelect = viewModel::selectFilter
            )
        }
    }

    // 选题弹窗：EC-01 服务端会话（collection_ids + count 真实约束本轮题目）
    // H-68：局部快照替代 `!!`——null 检查与解引用是两次独立的属性读取（无智能转换），
    // 中间被重组/状态重置打破时会抛 NPE
    val dialogSection = selectedSection
    if (showSelectDialog && dialogSection != null) {
        SelectQuestionDialog(
            section = dialogSection,
            // M-227：关闭弹窗时同步清空 selectedSection，避免残留旧分组引用影响下次打开
            onDismiss = {
                showSelectDialog = false
                selectedSection = null
            },
            onStart = { count ->
                showSelectDialog = false
                selectedSection = null
                onCreateSession(
                    state.bankId,
                    dialogSection.collectionIds.ifEmpty { null },
                    dialogSection.year,
                    dialogSection.types.firstOrNull()?.typeCode,
                    count
                )
            }
        )
    }
}

@Composable
private fun SectionTabRow(
    selectedTab: SectionTab,
    onSelect: (SectionTab) -> Unit
) {
    androidx.compose.material3.TabRow(
        selectedTabIndex = SectionTab.entries.indexOf(selectedTab)
    ) {
        SectionTab.entries.forEach { tab ->
            androidx.compose.material3.Tab(
                selected = selectedTab == tab,
                onClick = { onSelect(tab) },
                text = {
                    Text(
                        text = tab.label,
                        fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

@Composable
private fun FilterChipRow(
    selectedFilter: FilterTab,
    onSelect: (FilterTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterTab.entries.forEach { filter ->
            FilterChip(
                selected = selectedFilter == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    }
}

@Composable
private fun SectionCard(
    section: SectionItem,
    onContinue: () -> Unit,
    onSelect: () -> Unit
) {
    val title = section.title ?: "${section.year}年真题"
    val total = section.total
    val done = section.done
    val progress = if (total > 0) done.toFloat() / total else 0f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$done / $total",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onContinue) {
                    Text("继续")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = onSelect) {
                    Text("选题")
                }
            }
        }
    }
}

@Composable
private fun SelectQuestionDialog(
    section: SectionItem,
    onDismiss: () -> Unit,
    onStart: (Int) -> Unit
) {
    val maxCount = section.total - section.done
    // M-228：全部做完时 maxCount<=0，禁用开始按钮并明确提示，避免 resolve 返回 0 仍发起空会话
    val hasAvailable = maxCount > 0
    var countText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择题目数量") },
        text = {
            Column {
                Text(
                    text = if (hasAvailable) {
                        "共 $maxCount 题未做 · 默认 ${SessionCountPolicy.DEFAULT_COUNT} 题，最多 ${SessionCountPolicy.MAX_COUNT} 题"
                    } else {
                        "本组题目已全部完成，无可练习的题目"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter { c -> c.isDigit() } },
                    label = { Text("题目数量") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // 空输入 → 默认 20；>50 钳制 50；不超过可用题量（EC-01 D1）
                    onStart(SessionCountPolicy.resolve(countText, maxCount))
                },
                enabled = hasAvailable
            ) {
                Text("开始练习")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/** 骨架屏 shimmer 占位 */
@Composable
private fun ShimmerSectionList(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerOffset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "shimmerOffset"
    )
    val brush = Brush.linearGradient(
        colors = listOf(
            Color.LightGray.copy(alpha = 0.4f),
            Color.LightGray.copy(alpha = 0.1f),
            Color.LightGray.copy(alpha = 0.4f)
        ),
        start = Offset(0f, 0f),
        end = Offset(shimmerOffset * 400f, 0f)
    )

    LazyColumn(
        // M-226：接收外部 weight(1f) 约束，避免内部 LazyColumn 无界高度铺满挤压底部筛选行
        modifier = modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
    ) {
        items(5) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(brush)
            )
        }
    }
}

// M-229：year/title 组合字符串 + index 兜底生成稳定 key，替换跨重组/重载不稳定的 identity hashCode
private fun sectionKey(section: SectionItem, index: Int): String =
    "y${section.year ?: "-"}|t${section.title ?: "-"}|i$index"
