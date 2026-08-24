package com.toneup.app.ui.feature.mine

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

    val questionId: Long = savedStateHandle.get<String>("questionId")?.toLongOrNull() ?: -1L
    val bankId: String = savedStateHandle.get<String>("bankId") ?: ""

    private val _state = MutableStateFlow(NoteEditorUiState())
    val state: StateFlow<NoteEditorUiState> = _state

    init {
        viewModelScope.launch {
            try {
                val note = notesRepository.note(bankId, questionId)
                _state.value = _state.value.copy(
                    loaded = true, noteText = note?.noteText ?: "", dirty = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loaded = true, error = (e as? AppException)?.userMessage ?: "笔记加载失败")
            }
        }
    }

    fun onNoteChange(text: String) {
        _state.value = _state.value.copy(noteText = text, dirty = true)
    }

    fun save(onSaved: () -> Unit) {
        viewModelScope.launch {
            try {
                notesRepository.saveNote(bankId, questionId, _state.value.noteText)
                _state.value = _state.value.copy(dirty = false, hint = "已保存", error = null)
                onSaved()
            } catch (e: AppException) {
                _state.value = _state.value.copy(error = e.userMessage)
            } catch (_: Exception) {
                _state.value = _state.value.copy(error = "保存失败")
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
