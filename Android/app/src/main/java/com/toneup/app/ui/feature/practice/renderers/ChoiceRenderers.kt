package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
    }
}

/** §6.3.2 多选（reserved）：点击切换，底部计数，显式确认提交 */
@Composable
fun MultiRenderer(context: QuestionContext) {
    val options = context.question.options ?: emptyList()
    val selected = (context.answer as? AnswerValue.MultiChoice)?.labels?.toSet() ?: emptySet()
    val correctLabels = CorrectAnswerParser.multiLabels(context.question.answerText).toSet()

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        options.forEach { option ->
            OptionCard(
                option = option,
                selected = option.label in selected,
                correct = if (context.showAnswer && option.label in correctLabels) true else null,
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
                multiSelectCounter = selected.size
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
                enabled = !context.readonly && !context.disabled
            ) {
                Text("确认本组选择")
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
                option = option.copy(text = option.text.ifBlank { if (option.label == "A") "正确" else "错误" }),
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
