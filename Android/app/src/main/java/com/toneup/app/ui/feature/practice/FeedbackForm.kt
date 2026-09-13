package com.toneup.app.ui.feature.practice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.api.FeedbackApi
import com.toneup.app.data.remote.api.FeedbackRequest
import kotlinx.coroutines.launch

private val FEEDBACK_CATEGORIES = listOf(
    "答案有误",
    "解析有误",
    "题干有误",
    "图片显示异常",
    "其他"
)

private const val MAX_CONTENT_LENGTH = 500

/**
 * 题目反馈入口（W6-22 F8）：
 * 点击"有问题,去反馈"展开表单；提交后 toast 成功并收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackForm(
    bankId: String,
    questionId: Long,
    feedbackApi: FeedbackApi,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(FEEDBACK_CATEGORIES.first()) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth()) {
        // 触发行
        Text(
            text = if (expanded) "▲ 收起反馈" else "⊙ 有问题,去反馈 >",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable {
                    expanded = !expanded
                    if (!expanded) {
                        showForm = false
                        errorMsg = null
                    }
                }
                .padding(vertical = 4.dp)
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            if (!showForm) {
                // 选择类别后进入表单
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "请选择反馈类别",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))

                        FEEDBACK_CATEGORIES.forEach { category ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCategory = category
                                        showForm = true
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (selectedCategory == category)
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .width(16.dp)
                                        .height(16.dp)
                                ) {}
                                Spacer(Modifier.width(8.dp))
                                Text(category, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            } else {
                // 反馈表单
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 类别下拉
                        ExposedDropdownMenuBox(
                            expanded = categoryExpanded,
                            onExpandedChange = { categoryExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedCategory,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("反馈类别") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                                )
                            )
                            ExposedDropdownMenu(
                                expanded = categoryExpanded,
                                onDismissRequest = { categoryExpanded = false }
                            ) {
                                FEEDBACK_CATEGORIES.forEach { category ->
                                    DropdownMenuItem(
                                        text = { Text(category) },
                                        onClick = {
                                            selectedCategory = category
                                            categoryExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // 内容输入
                        OutlinedTextField(
                            value = content,
                            onValueChange = {
                                if (it.length <= MAX_CONTENT_LENGTH) content = it
                            },
                            label = { Text("详细描述（选填）") },
                            placeholder = { Text("请描述您发现的问题...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            maxLines = 6,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface
                            ),
                            supportingText = {
                                Text(
                                    "${content.length}/$MAX_CONTENT_LENGTH",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (content.length > MAX_CONTENT_LENGTH - 50)
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )

                        if (errorMsg != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = errorMsg!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // 按钮行
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    showForm = false
                                    errorMsg = null
                                }
                            ) {
                                Text("取消")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    if (content.trim().isEmpty()) {
                                        errorMsg = "请填写反馈内容"
                                        return@Button
                                    }
                                    submitting = true
                                    errorMsg = null
                                    scope.launch {
                                        try {
                                            val envelope = feedbackApi.submitFeedback(
                                                FeedbackRequest(
                                                    bankId = bankId,
                                                    questionId = questionId,
                                                    category = selectedCategory,
                                                    content = content.trim()
                                                )
                                            )
                                            if (!envelope.success) {
                                                submitting = false
                                                errorMsg = envelope.message ?: "提交失败"
                                                return@scope
                                            }
                                            submitting = false
                                            android.widget.Toast
                                                .makeText(context, "反馈已提交", android.widget.Toast.LENGTH_SHORT)
                                                .show()
                                            onSubmit()
                                            expanded = false
                                            showForm = false
                                            content = ""
                                        } catch (e: Exception) {
                                            submitting = false
                                            errorMsg = e.message ?: "提交失败，请重试"
                                        }
                                    }
                                },
                                enabled = !submitting
                            ) {
                                Text(if (submitting) "提交中..." else "提交")
                            }
                        }
                    }
                }
            }
        }
    }
}
