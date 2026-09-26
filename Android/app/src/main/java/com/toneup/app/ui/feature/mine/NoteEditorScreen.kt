package com.toneup.app.ui.feature.mine

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.toneup.app.data.repository.AppException
import com.toneup.app.data.repository.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NoteEditorUiState(
    val loaded: Boolean = false,
    val noteText: String = "",
    val dirty: Boolean = false,
    val hint: String? = null,
    val error: String? = null
)

@HiltViewModel
class NoteEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val notesRepository: NotesRepository
) : ViewModel() {

    // 路由参数声明为 NavType.LongType，必须按 Long 读取（C-4）；
    // get<String> 会对 Long 做未检查强转，进屏即 ClassCastException
    val questionId: Long = savedStateHandle.get<Long>("questionId") ?: -1L
    val bankId: String = savedStateHandle.get<String>("bankId") ?: ""

    // M-171：无效路由参数（缺 questionId/bankId）不再静默默认后照常请求，
    // 标记非法参数态：加载与保存全部短路并显示错误
    val hasValidArgs: Boolean = questionId > 0L && bankId.isNotBlank()

    // M-173：保存 in-flight 守卫，防连点并发 PUT 与状态竞写
    private var saving = false

    private val _state = MutableStateFlow(NoteEditorUiState())
    val state: StateFlow<NoteEditorUiState> = _state

    init {
        if (!hasValidArgs) {
            _state.value = _state.value.copy(loaded = true, error = "笔记参数无效")
        } else {
            viewModelScope.launch {
                try {
                    val note = notesRepository.note(bankId, questionId)
                    _state.value = _state.value.copy(
                        loaded = true,
                        // H-52：请求在飞期间用户已输入（dirty）时不得覆盖其内容
                        noteText = if (_state.value.dirty) _state.value.noteText else (note?.noteText ?: "")
                    )
                } catch (e: CancellationException) {
                    // M-172：不再吞掉取消异常，交给协程正常取消
                    throw e
                } catch (e: Exception) {
                    _state.value = _state.value.copy(loaded = true, error = (e as? AppException)?.userMessage ?: "笔记加载失败")
                }
            }
        }
    }

    fun onNoteChange(text: String) {
        _state.value = _state.value.copy(noteText = text, dirty = true)
    }

    fun save(onSaved: () -> Unit) {
        // M-171：参数非法时直接错误态返回，不调用 saveNote()
        if (!hasValidArgs) {
            _state.value = _state.value.copy(error = "笔记参数无效")
            return
        }
        // M-173：已有保存在飞则忽略本次点击
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                notesRepository.saveNote(bankId, questionId, _state.value.noteText)
                _state.value = _state.value.copy(dirty = false, hint = "已保存", error = null)
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppException) {
                _state.value = _state.value.copy(error = e.userMessage)
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "保存失败")
            } finally {
                saving = false
            }
        }
    }
}

/** 笔记编辑二级页（§2.7 noteEditor/{questionId}?bankId={}） */
@Composable
fun NoteEditorScreen(
    onBack: () -> Unit,
    viewModel: NoteEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmLeave by remember { mutableStateOf(false) }

    // 系统返回手势/物理键与顶栏返回一致：dirty 时先确认
    BackHandler(enabled = state.dirty) { confirmLeave = true }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = {
                if (state.dirty) confirmLeave = true else onBack()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("编辑笔记", style = MaterialTheme.typography.titleLarge)
        }

        com.toneup.app.ui.feature.analysis.NotesSection(
            noteText = state.noteText,
            dirty = state.dirty,
            hint = state.hint,
            onChange = { viewModel.onNoteChange(it) },
            onSave = { viewModel.save {} }
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("笔记未保存") },
            text = { Text("保存当前修改再离开？") },
            confirmButton = {
                Button(onClick = {
                    confirmLeave = false
                    viewModel.save(onSaved = onBack)
                }) { Text("保存并离开") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirmLeave = false; onBack() }) { Text("不保存") }
            }
        )
    }
}
