package com.toneup.app.ui.feature.practice.renderers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.toneup.app.domain.logic.SubQuestionParser
import com.toneup.app.domain.model.AnswerValue
import com.toneup.app.ui.components.formula.FormulaText
import com.toneup.app.ui.components.question.QuestionContext
import kotlinx.coroutines.delay

/**
 * §6.3.4 填空：逐空独立输入，行内 LaTeX 预览（300ms 节流）位于输入框正下方。
 * 多空草稿按空序保存；留空由宿主二次确认。
 */
@Composable
fun FillBlankRenderer(context: QuestionContext) {
    val blankCount = SubQuestionParser.blankCount(context.question)
    val blanks = (context.answer as? AnswerValue.Blanks)?.values ?: emptyMap()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(blankCount) { index ->
            val value = blanks[index] ?: ""
            FillBlankItem(
                index = index,
                value = value,
                enabled = !context.readonly && !context.disabled,
                showPreview = true,
                onValueChange = {
                    context.onAnswerChange(AnswerValue.Blanks(blanks + (index to it)))
                }
            )
        }
    }
}

@Composable
private fun FillBlankItem(
    index: Int,
    value: String,
    enabled: Boolean,
    showPreview: Boolean,
    onValueChange: (String) -> Unit
) {
    // previewText 不以 value 为 key：否则每次输入都会同步重置，300ms 节流失效
    var previewText by remember { mutableStateOf("") }

    LaunchedEffect(value) {
        if (value != previewText) {
            delay(LATEX_PREVIEW_THROTTLE_MS)
            previewText = value
        }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            label = { Text("第 ${index + 1} 空") },
            modifier = Modifier.fillMaxWidth()
        )
        if (showPreview && previewText.isNotBlank()) {
            Text(
                text = "预览",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
            FormulaText(
                text = "$$${previewText}$$",
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/** §6.3.5 解答题 / §6.3.9 翻译 / §6.3.10 作文 共用长文本编辑器 */
@Composable
fun SolutionRenderer(context: QuestionContext) {
    LongAnswerSection(
        context = context,
        placeholder = "写下你的解题过程",
        suggestedRange = null
    )
}

@Composable
fun TranslationRenderer(context: QuestionContext) {
    LongAnswerSection(
        context = context,
        placeholder = "在此输入你的译文",
        suggestedRange = null
    )
}

@Composable
fun EssayRenderer(context: QuestionContext) {
    val minWords = context.question.subQuestions
        ?.firstOrNull()?.let { SubQuestionParser.textOf(it) }
    LongAnswerSection(
        context = context,
        placeholder = "作答区（建议结构：开头—论证—结尾）",
        suggestedRange = null,
        hint = minWords
    )
}

@Composable
private fun LongAnswerSection(
    context: QuestionContext,
    placeholder: String,
    suggestedRange: IntRange?,
    hint: String? = null
) {
    val value = (context.answer as? AnswerValue.Text)?.text ?: ""
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LongAnswerEditor(
            value = value,
            onValueChange = { context.onAnswerChange(AnswerValue.Text(it)) },
            enabled = !context.readonly && !context.disabled,
            placeholder = placeholder
        )
        if (suggestedRange != null) {
            Text(
                text = "建议字数：${suggestedRange.first} - ${suggestedRange.last}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "${value.length} 字",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private const val LATEX_PREVIEW_THROTTLE_MS = 300L
