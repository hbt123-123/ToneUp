package com.toneup.app.ui.feature.practice

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.toneup.app.data.remote.api.NotesSharedApi
import com.toneup.app.data.remote.api.SharedNoteDto
import kotlinx.coroutines.launch

/**
 * 笔记共享面板（W6-23 F9）：
 * 双 Tab —— 网友笔记 / 我的笔记。
 * 网友笔记：LazyColumn + 点赞；我的笔记：现有编辑器 + 可见性切换。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesSharePanel(
    questionId: Long,
    bankId: String,
    notesSharedApi: NotesSharedApi,
    myNoteText: String,
    myNoteVisibility: Boolean,
    onMyNoteVisibilityChange: (Boolean) -> Unit,
    onMyNoteSave: (String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("网友笔记", "我的笔记")

    // M-180：网友笔记状态提升到面板级，切 tab 不再销毁组合导致已加载列表/状态丢失
    var sharedNotes by remember { mutableStateOf<List<SharedNoteDto>>(emptyList()) }
    var sharedLoading by remember { mutableStateOf(true) }
    var sharedError by remember { mutableStateOf<String?>(null) }
    // H-56：点赞/取消点赞失败走独立提示通道，不得顶掉加载错误分支
    var sharedActionError by remember { mutableStateOf<String?>(null) }
    // H-55：加载协程归 LaunchedEffect 所有——用 retryKey 触发重试，
    // 组合销毁时请求随 effect 取消，不会在离开屏幕后回写状态
    var sharedRetryKey by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // M-181：请求依赖 bankId，key 补齐 bankId，避免同题号跨题库时错用上一题库的结果
    LaunchedEffect(questionId, bankId, sharedRetryKey) {
        sharedLoading = true
        sharedError = null
        try {
            val result = notesSharedApi.getNotes(questionId, bankId, scope = "public")
            sharedNotes = result.data?.items ?: emptyList()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            sharedError = e.message ?: "加载失败"
        } finally {
            sharedLoading = false
        }
    }

    // 点赞/取消点赞：成功后回写面板级列表（H-56 失败写独立通道）
    fun toggleSharedNoteLike(note: SharedNoteDto) {
        scope.launch {
            try {
                if (note.isLikedByMe) {
                    notesSharedApi.unlikeNote(note.noteId)
                } else {
                    notesSharedApi.likeNote(note.noteId)
                }
                sharedNotes = sharedNotes.map {
                    if (it.noteId == note.noteId) {
                        it.copy(
                            isLikedByMe = !it.isLikedByMe,
                            likeCount = if (it.isLikedByMe) it.likeCount - 1 else it.likeCount + 1
                        )
                    } else it
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // H-56：写入独立通道，加载错误分支不受影响
                sharedActionError = e.message ?: "操作失败"
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标签页
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> SharedNotesTab(
                notes = sharedNotes,
                loading = sharedLoading,
                error = sharedError,
                actionError = sharedActionError,
                onRetry = { sharedRetryKey++ },
                onLikeToggle = { toggleSharedNoteLike(it) }
            )
            1 -> MyNotesTab(
                noteText = myNoteText,
                visibility = myNoteVisibility,
                onVisibilityChange = onMyNoteVisibilityChange,
                onSave = onMyNoteSave
            )
        }
    }
}

@Composable
private fun SharedNotesTab(
    notes: List<SharedNoteDto>,
    loading: Boolean,
    error: String?,
    actionError: String?,
    onRetry: () -> Unit,
    onLikeToggle: (SharedNoteDto) -> Unit
) {
    // M-180/M-182：状态已提升至 NotesSharePanel（切 tab 不丢列表）；
    // error 为函数参数可空安全 smart cast，无需 error!!
    when {
        loading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
        error != null -> {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onRetry) {
                    Text("重试")
                }
            }
        }
        notes.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "暂无笔记，成为第一个分享笔记的人",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            Column {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    items(notes, key = { it.noteId }) { note ->
                        SharedNoteItem(
                            note = note,
                            onLikeToggle = { onLikeToggle(note) }
                        )
                    }
                }
                actionError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SharedNoteItem(
    note: SharedNoteDto,
    onLikeToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 头部：用户 + 时间
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "用户 ${note.userId}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                note.createdAt?.let {
                    Text(
                        text = it.take(10),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(6.dp))

            // 笔记内容
            Text(
                text = note.noteText,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(6.dp))

            // 点赞按钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onLikeToggle() }
            ) {
                Icon(
                    imageVector = if (note.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = if (note.isLikedByMe) "取消点赞" else "点赞",
                    tint = if (note.isLikedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "${note.likeCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (note.isLikedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MyNotesTab(
    noteText: String,
    visibility: Boolean,
    onVisibilityChange: (Boolean) -> Unit,
    onSave: (String) -> Unit
) {
    var editingText by remember { mutableStateOf(noteText) }
    var isEditing by remember { mutableStateOf(false) }

    // M-183：noteText 外部刷新（加载完成/保存回传/清除）时同步编辑文本；
    // 编辑中不覆盖用户正在输入的内容
    LaunchedEffect(noteText) {
        if (!isEditing) editingText = noteText
    }

    Column(modifier = Modifier.padding(12.dp)) {
        // 可见性切换
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "笔记可见范围",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !visibility,
                    onClick = { onVisibilityChange(false) },
                    label = { Text("私有", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = visibility,
                    onClick = { onVisibilityChange(true) },
                    label = { Text("公开", style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 笔记编辑区
        if (isEditing) {
            OutlinedTextField(
                value = editingText,
                onValueChange = { editingText = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 8,
                placeholder = { Text("写下你的笔记...") },
                supportingText = {
                    Text(
                        "${editingText.length} 字",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = {
                    isEditing = false
                    editingText = noteText
                }) {
                    Text("取消")
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onSave(editingText.trim())
                        isEditing = false
                    }
                ) {
                    Text("保存")
                }
            }
        } else {
            if (noteText.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Text(
                        text = noteText,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无笔记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            TextButton(
                onClick = { isEditing = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ 添加笔记")
            }
        }
    }
}
