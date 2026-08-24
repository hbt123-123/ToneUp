package com.toneup.app.ui.feature.mine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.BuildConfig
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.EmptyState
import com.toneup.app.ui.components.ErrorRetryCard

/** 我的 Tab（ME）：FR-ME-01~05 */
@Composable
fun MineTab(
    rootNavController: androidx.navigation.NavHostController,
    onLoggedOut: () -> Unit = {},
    viewModel: MineViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLogoutDialog by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(state.loggedOut) {
        if (state.loggedOut) onLoggedOut()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Text("我的", style = MaterialTheme.typography.titleLarge) }

        // 用户信息卡
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (val u = state.user) {
                        is Load.Ready -> {
                            Text(u.value.username, style = MaterialTheme.typography.titleLarge)
                            Text(
                                "注册时间：${u.value.createdAt ?: "未知"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        is Load.Failed -> Text(u.message, color = MaterialTheme.colorScheme.error)
                        Load.Loading -> Text("加载中…")
                    }
                }
            }
        }

        // 设置（FR-ME-03）
        item { Text("设置", style = MaterialTheme.typography.titleMedium) }
        item {
            SettingSwitchRow(
                title = "界面动效",
                subtitle = "关闭后退场动画简化、庆祝动效停用",
                checked = state.preferences.animationsEnabled,
                onCheckedChange = { viewModel.setAnimationsEnabled(it) }
            )
        }
        item {
            SettingSwitchRow(
                title = "触感反馈",
                subtitle = "收藏、错误与打卡的振动反馈",
                checked = state.preferences.hapticsEnabled,
                onCheckedChange = { viewModel.setHapticsEnabled(it) }
            )
        }
        item {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.toneup.app.ui.theme.DarkModePolicy.entries.forEach { policy ->
                        androidx.compose.material3.FilterChip(
                            selected = state.preferences.darkModePolicy == policy,
                            onClick = { viewModel.setDarkModePolicy(policy) },
                            label = { Text(policy.label) }
                        )
                    }
                }
            }
        }
        item { HorizontalDivider() }

        // 我的笔记列表（FR-ME-02）
        item { Text("我的笔记", style = MaterialTheme.typography.titleMedium) }
        when (val notes = state.notes) {
            is Load.Loading -> item { Text("加载中…") }
            is Load.Failed -> item {
                ErrorRetryCard(message = notes.message, onRetry = { viewModel.loadNotes() })
            }
            is Load.Ready -> {
                if (notes.value.isEmpty()) {
                    item { EmptyState("还没有笔记，去题目解析里记一条吧") }
                } else {
                    items(notes.value, key = { "${it.bankId}:${it.questionId}" }) { note ->
                        Card(
                            onClick = {
                                rootNavController.navigate(
                                    com.toneup.app.ui.navigation.Routes.noteEditor(
                                        questionId = note.questionId,
                                        bankId = note.bankId
                                    )
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    note.questionSummary ?: "笔记",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2
                                )
                                Text(
                                    text = note.updatedAt ?: "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
        item { HorizontalDivider() }

        // 关于（P2 简版）+ PoC 入口（debug）
        item {
            Text(
                text = "一潼上岸 ToneUp v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (BuildConfig.DEBUG) {
            item {
                OutlinedButton(onClick = {
                    rootNavController.navigate(com.toneup.app.ui.navigation.Routes.FORMULA_POC)
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("公式渲染 PoC（调试）")
                }
            }
        }

        // FR-ME-04 退出登录
        item {
            Button(
                onClick = { showLogoutDialog = true },
                enabled = !state.logoutBusy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.logoutBusy) "退出中…" else "退出登录")
            }
        }
    }

    if (showLogoutDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("退出登录") },
            text = { Text("将清除本机保存的草稿与待同步记录，确定退出？") },
            confirmButton = {
                Button(onClick = {
                    showLogoutDialog = false
                    viewModel.logout()
                }) { Text("确定退出") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogoutDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
