package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.toneup.app.domain.logic.SubQuestionParser
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.components.question.QuestionContext

/** §6.3.6 完形填空：passage 折叠 + 逐空输入 + 上一空/下一空跳转 */
@Composable
fun ClozeRenderer(context: QuestionContext) {
    val passage = context.question.passage ?: ""
    var passageExpanded by remember(context.question.passage) { mutableStateOf(false) }
    val blankCount = SubQuestionParser.passageBlankCount(context.question)
    val blanks = (context.answer as? AnswerValue.BlankLabels)?.values ?: emptyMap()
    var focusedBlank by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (passage.isNotBlank()) {
            PassageCollapsiblePanel(
                passage = passage,
                expanded = passageExpanded,
                onToggle = { passageExpanded = !passageExpanded },
                title = "完形文章"
            )
        }

        Text(
            text = "共 $blankCount 空，当前第 ${focusedBlank.coerceAtLeast(0) + 1} 空",
            style = MaterialTheme.typography.labelLarge
        )

        OutlinedTextField(
            value = blanks[focusedBlank] ?: "",
            onValueChange = { text ->
                context.onAnswerChange(
                    AnswerValue.BlankLabels(blanks + (focusedBlank to text))
                )
            },
            enabled = !context.readonly && !context.disabled,
            singleLine = true,
            label = { Text("第 ${focusedBlank + 1} 空") },
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { if (focusedBlank > 0) focusedBlank-- },
                enabled = focusedBlank > 0 && !context.readonly
            ) {
                Text("上一空")
            }
            OutlinedButton(
                onClick = { if (focusedBlank < blankCount - 1) focusedBlank++ },
                enabled = focusedBlank < blankCount - 1 && !context.readonly
            ) {
                Text("下一空")
            }
            Spacer(Modifier.size(8.dp))
            // 空序号快速定位
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                (0 until blankCount).take(MAX_BLANK_CHIPS).forEach { i ->
                    FilterChip(
                        selected = i == focusedBlank,
                        onClick = { focusedBlank = i },
                        label = {
                            Text(if ((blanks[i] ?: "").isBlank()) "${i + 1}" else "✓${i + 1}")
                        }
                    )
                }
            }
        }
    }
}

private const val MAX_BLANK_CHIPS = 10

/**
 * §6.3.7 阅读理解：
 * passage 折叠（展开状态随同一 passage 的兄弟小题保持——按 passage 内容记忆），
 * 子题交互同 SINGLE。
 */
@Composable
fun ReadingRenderer(context: QuestionContext) {
    val passage = context.question.passage
    var passageExpanded by remember(passage) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!passage.isNullOrBlank()) {
            PassageCollapsiblePanel(
                passage = passage,
                expanded = passageExpanded,
                onToggle = { passageExpanded = !passageExpanded },
                title = "阅读文章"
            )
        }
        SingleRenderer(context)
    }
}

/**
 * §6.3.8 排序：长按拖拽（默认）与序号下拉选择双路径；
 * 拖拽抬升阴影 + LIGHT_IMPACT 触感；序号徽标实时呈现当前顺序。
 */
@Composable
fun OrderingRenderer(context: QuestionContext) {
    val items = orderingItems(context.question)
    if (items.isEmpty()) {
        // §6.4 降级原则：解析不出排序项时降级提示，绝不崩溃
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("排序内容加载失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "题号 ${context.question.questionId} 未解析到可排序项，可重试或跳过",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { context.onRetryLoad?.invoke() }) { Text("重试") }
                    OutlinedButton(onClick = { context.onSkipQuestion?.invoke() }) { Text("跳过本题") }
                }
            }
        }
        return
    }

    val currentOrder = (context.answer as? AnswerValue.Order)?.ids
    var orderIds by remember(context.question.questionId) {
        mutableStateOf(currentOrder ?: items.map { it.id })
    }
    var dragMode by remember { mutableStateOf(true) }
    val haptics = LocalHapticFeedback.current

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = dragMode,
                onClick = { dragMode = true },
                label = { Text("拖拽排序") }
            )
            FilterChip(
                selected = !dragMode,
                onClick = { dragMode = false },
                label = { Text("序号选择")
                }
            )
        }

        if (dragMode) {
            DragOrderList(
                orderIds = orderIds,
                items = items,
                enabled = !context.readonly && !context.disabled,
                onOrderChange = { next ->
                    orderIds = next
                    context.onAnswerChange(AnswerValue.Order(next))
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        } else {
            DropdownOrderList(
                orderIds = orderIds,
                items = items,
                enabled = !context.readonly && !context.disabled,
                onSlotSelected = { slot, id ->
                    val mutable = orderIds.toMutableList()
                    val existingIndex = mutable.indexOf(id)
                    if (existingIndex >= 0) {
                        val swapped = mutable[existingIndex]
                        mutable[existingIndex] = mutable[slot]
                        mutable[slot] = swapped
                    }
                    orderIds = mutable.toList()
                    context.onAnswerChange(AnswerValue.Order(orderIds))
                }
            )
        }

        Text(
            text = "当前顺序：" + orderIds.joinToString(" → ") { id ->
                (items.firstOrNull { it.id == id }?.label ?: id)
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

data class OrderingItem(val id: String, val label: String, val text: String)

private fun orderingItems(question: com.toneup.app.data.remote.dto.QuestionDto): List<OrderingItem> {
    question.options?.takeIf { it.isNotEmpty() }?.let { options ->
        return options.map { OrderingItem(it.label, it.label, it.text) }
    }
    question.subQuestions?.let { subs ->
        return subs.mapIndexedNotNull { index, obj ->
            val text = SubQuestionParser.textOf(obj)
            text?.let { OrderingItem((index + 1).toString(), (index + 1).toString(), it) }
        }
    }
    return emptyList()
}

@Composable
private fun DragOrderList(
    orderIds: List<String>,
    items: List<OrderingItem>,
    enabled: Boolean,
    onOrderChange: (List<String>) -> Unit
) {
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        orderIds.forEachIndexed { index, id ->
            val item = items.firstOrNull { it.id == id } ?: return@forEachIndexed
            val isDragging = draggingId == id
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        translationY = if (isDragging) dragOffset.y else 0f
                        shadowElevation = if (isDragging) 12.dp.toPx() else 2.dp.toPx()
                    }
                    .onGloballyPositioned { bounds[id] = it.boundsInParent() }
                    .then(
                        if (enabled) {
                            Modifier.pointerInput(id) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        draggingId = id
                                        dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        dragOffset += amount
                                        val draggedBounds = bounds[id] ?: return@detectDragGesturesAfterLongPress
                                        val centerY = draggedBounds.center.y + dragOffset.y
                                        // 与相邻项交换位置
                                        val target = orderIds.firstOrNull { other ->
                                            other != id &&
                                                bounds[other]?.let { b ->
                                                    centerY in b.top..b.bottom
                                                } == true
                                        }
                                        if (target != null) {
                                            val from = orderIds.indexOf(id)
                                            val to = orderIds.indexOf(target)
                                            val next = orderIds.toMutableList().apply {
                                                removeAt(from)
                                                add(to, id)
                                            }
                                            onOrderChange(next)
                                            dragOffset = androidx.compose.ui.geometry.Offset.Zero
                                        }
                                    },
                                    onDragEnd = { draggingId = null },
                                    onDragCancel = { draggingId = null }
                                )
                            }
                        } else {
                            Modifier
                        }
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Text(
                            text = "${index + 1}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Spacer(Modifier.size(10.dp))
                    Box(Modifier.weight(1f)) {
                        FormulaText(text = item.text)
                    }
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "长按拖拽调整顺序",
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

@Composable
private fun DropdownOrderList(
    orderIds: List<String>,
    items: List<OrderingItem>,
    enabled: Boolean,
    onSlotSelected: (slot: Int, id: String) -> Unit
) {
    var expandedSlot by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        orderIds.forEachIndexed { slot, id ->
            val item = items.firstOrNull { it.id == id }
            Box {
                OutlinedButton(
                    onClick = { expandedSlot = slot },
                    enabled = enabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text("第 ${slot + 1} 位：${item?.label ?: id}")
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = expandedSlot == slot,
                    onDismissRequest = { expandedSlot = -1 }
                ) {
                    items.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text("${candidate.label}. ${candidate.text.take(30)}") },
                            onClick = {
                                expandedSlot = -1
                                onSlotSelected(slot, candidate.id)
                            }
                        )
                    }
                }
            }
        }
    }
}
