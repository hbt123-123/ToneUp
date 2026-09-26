package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.dto.OptionDto
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.domain.logic.SubQuestionParser
import com.toneup.app.domain.logic.CorrectAnswerParser
import com.toneup.app.ui.components.question.QuestionContext

/** §6.3.1 单选：竖排卡片，点击即时改选，无需确认键 */
@Composable
fun SingleRenderer(context: QuestionContext) {
    val options = context.question.options ?: emptyList()
    val selectedLabel = (context.answer as? AnswerValue.Choice)?.label
    val correctLabel = CorrectAnswerParser.singleLabel(context.question.answerText)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            OptionCard(
                option = option,
                selected = selectedLabel == option.label,
                // M-196：correctLabel 解析失败（answerText 空或畸形）时为 null，原 when 永远匹配不到
                // "正确"分支——此时全部选项不标对错，用户选中项保持普通高亮，避免误导
                correct = if (context.showAnswer && correctLabel != null) {
                    when (option.label) {
                        correctLabel -> true
                        selectedLabel -> false
                        else -> null
                    }
                } else {
                    null
                },
                enabled = !context.readonly && !context.disabled,
                onClick = { context.onAnswerChange(AnswerValue.Choice(option.label)) }
            )
        }
    }
}

/** §6.3.2 多选（reserved）：点击切换，底部计数，显式确认提交 */
@Composable
fun MultiRenderer(context: QuestionContext) {
    val options = context.question.options ?: emptyList()
    val selected = (context.answer as? AnswerValue.MultiChoice)?.labels?.toSet() ?: emptySet()
    val correctLabels = CorrectAnswerParser.multiLabels(context.question.answerText).toSet()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = "多选",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }

        options.forEach { option ->
            OptionCard(
                option = option,
                selected = option.label in selected,
                // M-197：补齐"选中但不在正确集合"→ false 的分支，提交后错选项与单选/判断一致标红
                correct = if (context.showAnswer) {
                    when {
                        option.label in correctLabels -> true
                        option.label in selected -> false
                        else -> null
                    }
                } else {
                    null
                },
                enabled = !context.readonly && !context.disabled,
                onClick = {
                    val next = if (option.label in selected) {
                        selected - option.label
                    } else {
                        selected + option.label
                    }
                    context.onAnswerChange(
                        AnswerValue.MultiChoice(next.sorted())
                    )
                },
                multiSelectMode = true
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "已选 ${selected.size} 项",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { contentDescription = "已选${selected.size}项" }
            )
            Spacer(Modifier.padding(horizontal = 8.dp))
            Button(
                onClick = context.onSubmitRequest,
                enabled = !context.readonly && !context.disabled && selected.isNotEmpty()
            ) {
                Text("提交")
            }
        }
    }
}

/** §6.3.3 判断（reserved）：对/错两枚大按钮 */
@Composable
fun JudgeRenderer(context: QuestionContext) {
    val judgeOptions: List<OptionDto> = context.question.options ?: listOf(
        OptionDto("A", "正确"),
        OptionDto("B", "错误")
    )
    val selectedLabel = (context.answer as? AnswerValue.Choice)?.label
    val correctLabel = CorrectAnswerParser.singleLabel(context.question.answerText)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        judgeOptions.forEach { option ->
            OptionCard(
                // M-198：text 空时不再按 label=="A" 硬编码判定语义（非 A/B 标签、乱序或多于
                // 两个选项时会全部误标"错误"）；作答态显示 label 本身不猜测判定语义，
                // 仅 showAnswer 时用实际正确答案比较标注，避免作答中泄露正确选项
                option = option.copy(
                    text = option.text.ifBlank {
                        if (context.showAnswer && option.label == correctLabel) "正确" else option.label
                    }
                ),
                selected = selectedLabel == option.label,
                correct = if (context.showAnswer) {
                    when (option.label) {
                        correctLabel -> true
                        selectedLabel -> false
                        else -> null
                    }
                } else {
                    null
                },
                enabled = !context.readonly && !context.disabled,
                onClick = { context.onAnswerChange(AnswerValue.Choice(option.label)) }
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}
