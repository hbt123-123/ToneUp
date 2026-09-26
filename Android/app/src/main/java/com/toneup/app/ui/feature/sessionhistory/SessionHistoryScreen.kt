package com.toneup.app.ui.feature.sessionhistory

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.toneup.app.data.remote.dto.SessionDto
import com.toneup.app.data.remote.dto.SessionListItemDto

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionHistoryScreen(
    onBack: () -> Unit,
    onContinue: (sessionId: String, index: Int) -> Unit,
    viewModel: SessionHistoryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.resumeError) {
        state.resumeError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearResumeError()
        }
    }

    // H-72：删除失败走独立通道展示，不再混入 resumeError
    LaunchedEffect(state.deleteError) {
        state.deleteError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDeleteError()
        }
    }

    // M-235：删除为破坏性操作，点击后先弹确认框，确认后才真正执行
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }
    pendingDeleteId?.let { deleteId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除会话") },
            text = { Text("确定删除该练习会话吗？删除后无法恢复。") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    pendingDeleteId = null
                    viewModel.delete(deleteId)
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pendingDeleteId = null }) {
                    Text("取消")
                }
            }
        )
    }

    val listState = rememberLazyListState()
    // M-233：改为滚动事件驱动加载更多——snapshotFlow 仅以"最后可见项下标"为发射源，
    // loadMore 失败后用户停在底部不会自动重试，避免布尔 key 翻转引发的无限重试循环
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { lastVisible ->
                // M-233：lastVisible 可为 null（列表尚未完成首次布局），先空值跳过，
                // 避免 Int? 与 Int 直接比较
                val lastIdx = lastVisible ?: return@collect
                if (
                    lastIdx >= state.items.size - 3 &&
                    state.hasMore && !state.loadingMore
                ) {
                    viewModel.loadMore()
                }
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("练习会话") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.error != null && state.items.isEmpty() -> {
                // H-70：局部快照替代 `!!`——guard 与读取是两次独立的属性访问（无智能转换），
                // 中间被刷新重置为 null 时会抛 NPE
                val errorText = state.error
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        errorText ?: "",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.TextButton(onClick = { viewModel.refresh() }) {
                        Text("重试")
                    }
                }
            }
            state.items.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("暂无练习会话", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // M-234：跨页拼接可能出现重复 id，key 追加下标兜底，保证 key 全局唯一不崩溃
                    itemsIndexed(
                        state.items,
                        key = { index, item -> "${item.id}_$index" }
                    ) { _, item ->
                        SessionHistoryCard(
                            item = item,
                            resuming = state.resumingId == item.id,
                            onContinue = { viewModel.resumeSession(item) { sid, idx -> onContinue(sid, idx) } },
                            // M-235：删除改为经确认框后执行
                            onDelete = { pendingDeleteId = item.id }
                        )
                    }
                    if (state.loadingMore) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                horizontalArrangement = Arrangement.Center
                            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionHistoryCard(
    item: SessionListItemDto,
    resuming: Boolean,
    onContinue: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                item.title.ifBlank { "练习会话 #${item.id}" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val progress = if (item.totalCount > 0) {
                    item.answered.toFloat() / item.totalCount
                } else 0f
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).height(6.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "${item.answered}/${item.totalCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                item.createdAt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                androidx.compose.material3.TextButton(
                    onClick = onContinue,
                    enabled = !resuming && item.status == SessionDto.STATUS_ACTIVE
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(if (resuming) "恢复中…" else "继续")
                }
                Spacer(Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
