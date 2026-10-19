package com.geno1024.ai.qqfm.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.MediaItem

/**
 * The last thing between a person and an irreversible `rm`.
 *
 * Deleting from a merged view means the file that goes is not obvious from the grid
 * cell it was picked in, so every path is spelled out in full: absolute, unabridged,
 * and attributed to its own tree. Nothing here is abbreviated with an ellipsis
 * because the whole point is to be copyable and checkable.
 */
@Composable
fun DeleteConfirmDialog(
    items: List<MediaItem>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val total = items.sumOf { it.size }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (items.size == 1) "删除这一个文件？" else "删除选中的 ${items.size} 个文件？") },
        text = {
            Column {
                Text(
                    text = "将删除 ${formatBytes(total)}，只删本地缓存，聊天记录不会变。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    items.forEach { item ->
                        Text(
                            text = item.path,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}