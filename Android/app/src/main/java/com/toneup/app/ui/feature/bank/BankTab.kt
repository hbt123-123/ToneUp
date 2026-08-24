package com.toneup.app.ui.feature.bank

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.repository.AppException
import com.toneup.app.ui.common.Load
import com.toneup.app.ui.components.EmptyState
import com.toneup.app.ui.components.ErrorRetryCard
import com.toneup.app.ui.components.SkeletonBlock
import com.toneup.app.ui.navigation.Routes
import androidx.navigation.NavHostController

/**
 * 题库 Tab：顶部首页区块（FR-HM-01~05）+ 目录浏览入口。
 * 选题三级联动用半屏 ModalBottomSheet 承载，不占独立路由（§4.3）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankTab(
    rootNavController: NavHostController,
    viewModel: BankViewModel = hiltViewModel()
) {
    val home by viewModel.home.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = home.refreshing,
            onRefresh = { viewModel.refreshHome(forceRefreshCatalog = true) },
            modifier = Modifier.weight(1f)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StreakCard(
                        streakDays = home.streakDays,
                        checkedToday = home.checkedToday
                    )
                }

                // 错题本入口（WB 二级页）
                item {
                    Card(
                        onClick = { rootNavController.navigate(Routes.WRONGBOOK) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.FactCheck,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.size(12.dp))
                            Text("错题本", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                // FR-HM-02 继续上次刷题（无历史隐藏）
                if (home.lastContext != null) {
                    item {
                        Card(
                            onClick = {
                                viewModel.continueLastPractice { sessionId ->
                                    rootNavController.navigate(Routes.practice(sessionId))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.FastForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.size(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("继续上次刷题", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = home.lastContext?.title ?: "",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null)
                            }
                        }
                    }
                }

                // FR-HM-03 学科入口列表
                item {
                    Text("开始刷题", style = MaterialTheme.typography.titleLarge)
                }
                when (val catalogState = home.catalog) {
                    is Load.Loading -> items((1..3).toList()) {
                        SkeletonBlock(Modifier.height(56.dp))
                    }

                    is Load.Failed -> item {
                        ErrorRetryCard(message = catalogState.message, onRetry = {
                            viewModel.refreshHome(forceRefreshCatalog = true)
                        })
                    }

                    is Load.Ready -> {
                        val catalog = catalogState.value
                        if (catalog.subjects.isEmpty() && catalog.banks.isEmpty()) {
                            item { EmptyState("题库目录为空") }
                        } else {
                            items(catalog.subjects, key = { it.id }) { subject ->
                                Card(
                                    onClick = { viewModel.openPicker(subject.id) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f)) {
                                            Text(subject.name, style = MaterialTheme.typography.titleMedium)
                                            val bankCount = catalog.banks.count { it.subjectId == subject.id && it.enabled }
                                            Text(
                                                "$bankCount 个题库 · ${subject.types.size} 类",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Icon(Icons.Filled.ChevronRight, contentDescription = "进入${subject.name}")
                                    }
                                }
                            }
                            item {
                                Button(
                                    onClick = { viewModel.openPicker(null) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) { Text("浏览完整目录选题") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (home.catalog is Load.Ready) {
        BankPickerSheet(
            viewModel = viewModel,
            onSessionReady = { sessionId ->
                rootNavController.navigate(Routes.practice(sessionId))
            }
        )
    }
}

@Composable
private fun StreakCard(streakDays: Int, checkedToday: Boolean) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.LocalFireDepartment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.size(14.dp))
            Column {
                Text(
                    text = "连续学习 $streakDays 天",
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = if (checkedToday) "今日已打卡，继续保持！" else "今日尚未打卡，做一题就算打卡",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
