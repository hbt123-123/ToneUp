package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.dto.OptionDto
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.theme.CorrectGreen
import com.toneup.app.ui.theme.WrongRed

/** 选项竖排卡片（§6.3.1）：点击即时更新，showAnswer 时对错双通道标注 */
@Composable
fun OptionCard(
    option: OptionDto,
    selected: Boolean,
    correct: Boolean?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    multiSelectCounter: Int? = null
) {
    val borderColor = when {
        correct == true -> CorrectGreen
        selected && correct == false -> WrongRed
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val container = when {
        correct == true -> CorrectGreen.copy(alpha = 0.10f)
        selected && correct == false -> WrongRed.copy(alpha = 0.10f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .semantics {
                role = Role.RadioButton
                contentDescription = buildString {
                    append("选项 ${option.label}")
                    if (selected) append("，已选中")
                    if (correct == true) append("，正确答案")
                    if (correct == false && selected) append("，你的错误选择")
                }
            },
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(12.dp))
            Box(Modifier.weight(1f)) {
                FormulaText(text = option.text)
            }
            when {
                correct == true -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "正确",
                    tint = CorrectGreen
                )
                selected && correct == false -> Icon(
                    Icons.Filled.Close,
                    contentDescription = "错误",
                    tint = WrongRed
                )
                multiSelectCounter != null && selected -> Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** passage 折叠面板（§6.3.6/7）：默认收起显示摘要，展开内部滚动不撑爆页面 */
@Composable
fun PassageCollapsiblePanel(
    passage: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    title: String = "阅读材料"
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button, onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.size(8.dp))
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开全文"
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                ) {
                    FormulaText(text = passage, modifier = Modifier.padding(top = 8.dp))
                }
            }
            AnimatedVisibility(visible = !expanded) {
                Text(
                    text = passage.take(SUMMARY_CHARS).replace("\n", " ") +
                        if (passage.length > SUMMARY_CHARS) "……" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

private const val SUMMARY_CHARS = 80

/** 多行作答编辑器：字数统计右下角（§6.3.5） */
@Composable
fun LongAnswerEditor(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    placeholder: String = "在此输入你的答案",
    minLines: Int = 4
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        minLines = minLines,
        maxLines = 12,
        placeholder = { Text(placeholder) },
        modifier = modifier.fillMaxWidth(),
        supportingText = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                Text("${value.length} 字", style = MaterialTheme.typography.labelSmall)
            }
        }
    )
}

@Composable
fun answerStateLabel(isCorrect: Boolean?): String = when (isCorrect) {
    true -> "回答正确"
    false -> "回答错误"
    null -> "待判分"
}

val CorrectColor: Color @Composable get() = CorrectGreen
val WrongColor: Color @Composable get() = WrongRed
