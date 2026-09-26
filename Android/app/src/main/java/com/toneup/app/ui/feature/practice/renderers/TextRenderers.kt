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
 * §6.3.4 填空：逐空独立输入，行内 LaTeX 预览（300ms 防抖）位于输入框正下方。
 * 多空草稿按空序保存；留空由宿主二次确认。
 */
@Composable
fun FillBlankRenderer(context: QuestionContext) {
    val blankCount = SubQuestionParser.blankCount(context.question)
    val hostBlanks = (context.answer as? AnswerValue.Blanks)?.values ?: emptyMap()
    // M-208：原先 blanks 是组合期快照，编辑回调从快照重建 map，同一帧内连续两次输入会
    // 互相覆盖丢字。改为本地实时副本：编辑先写本地（State 读写同步，无帧间隙）再上报宿主；
    // 宿主权威变化（恢复草稿/重做清空）经 lastReported 对账识别后回写本地
    var localBlanks by remember(context.question.questionId) { mutableStateOf(hostBlanks) }
    var lastReported by remember(context.question.questionId) { mutableStateOf(hostBlanks) }
    LaunchedEffect(hostBlanks) {
        if (hostBlanks != lastReported) {
            localBlanks = hostBlanks
            lastReported = hostBlanks
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(blankCount) { index ->
            FillBlankItem(
                index = index,
                value = localBlanks[index] ?: "",
                enabled = !context.readonly && !context.disabled,
                showPreview = true,
                onValueChange = { text ->
                    val next = localBlanks + (index to text)
                    localBlanks = next
                    lastReported = next
                    context.onAnswerChange(AnswerValue.Blanks(next))
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
    // previewText 不以 value 为 key：否则每次输入都会同步重置，300ms 防抖失效
    var previewText by remember { mutableStateOf("") }

    // M-209：LaunchedEffect(value) 每次击键重启 effect——这是防抖（停顿 300ms 后才刷新预览），
    // 并非 KDoc 原称的"节流"；常量与注释已同步改名以免误导
    LaunchedEffect(value) {
        if (value != previewText) {
            delay(LATEX_PREVIEW_DEBOUNCE_MS)
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
            // M-210：用户输入是不可信 LaTeX 源，原样插值进 $$...$$ 会因 $/花括号/反斜杠失衡
            // 导致渲染错乱；预览放弃数学渲染，走 FormulaText 纯文本通道，任何输入都稳定回显
            FormulaText(
                text = previewText,
                forceRawText = true,
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
        placeholder = "写下你的解题过程"
    )
}

@Composable
fun TranslationRenderer(context: QuestionContext) {
    LongAnswerSection(
        context = context,
        placeholder = "在此输入你的译文"
    )
}

@Composable
fun EssayRenderer(context: QuestionContext) {
    val minWords = context.question.subQuestions
        ?.firstOrNull()?.let { SubQuestionParser.textOf(it) }
    LongAnswerSection(
        context = context,
        placeholder = "作答区（建议结构：开头—论证—结尾）",
        hint = minWords
    )
}

@Composable
private fun LongAnswerSection(
    context: QuestionContext,
    placeholder: String,
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
        // M-211：suggestedRange 死参数已删——全部调用方均传 null，该分支不可达
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = "${value.length} 字",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private const val LATEX_PREVIEW_DEBOUNCE_MS = 300L
