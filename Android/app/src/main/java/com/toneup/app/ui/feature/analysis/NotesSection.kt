package com.toneup.app.ui.feature.analysis

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 笔记区（FR-AN-05 / noteEditor 路由共用）：编辑保存，未保存提示由宿主拦截 */
@Composable
fun NotesSection(
    noteText: String,
    dirty: Boolean,
    hint: String?,
    onChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("我的笔记", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = noteText,
                onValueChange = onChange,
                placeholder = { Text("记录这道题的思路、易错点…") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onSave, enabled = dirty) {
                    Text(if (dirty) "保存笔记" else "已保存")
                }
                if (hint != null && !dirty) {
                    Text(hint, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                if (dirty) {
                    Text("有未保存修改", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
