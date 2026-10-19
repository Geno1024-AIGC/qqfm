package com.geno1024.ai.qqfm.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.MediaSource
import java.util.Collections

/**
 * Which of QQ's picture trees to browse.
 *
 * A page rather than a dialog because there is a decision to make here and it is
 * worth showing in full: each tree carries an absolute path, and the paths are long
 * enough that only half of one fits on a phone screen. More than one can be picked,
 * and the gallery then treats the selection as one directory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcePickerScreen(
    initial: Set<MediaSource>,
    onConfirm: (Set<MediaSource>) -> Unit,
    onCancel: (() -> Unit)?,
) {
    var selected by rememberSaveable(
        initial.map { it.id }.toList(),
    ) { mutableStateOf(initial.toList()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("选择目录") },
                navigationIcon = {
                    if (onCancel != null) {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (selected.isEmpty()) "至少选一个" else "已选 ${selected.size} 个",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (onCancel != null) {
                        TextButton(onClick = onCancel) { Text("取消") }
                        Spacer(Modifier.width(8.dp))
                    }
                    Button(onClick = { onConfirm(selected.toSet()) }, enabled = selected.isNotEmpty()) {
                        Text("确定")
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "可以多选，图库会把选中的目录合并成一个列表排序和分组。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
            MediaSource.entries.forEach { source ->
                val checked = source in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (checked) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
                            } else {
                                androidx.compose.ui.graphics.Color.Transparent
                            },
                        )
                        .clickable {
                            val next = selected.toMutableList()
                            if (!next.remove(source)) next.add(source)
                            selected = next
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = source.id,
                            style = MaterialTheme.typography.bodyLarge,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = source.dir,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = sourceHint(source),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

private fun sourceHint(source: MediaSource): String = when (source) {
    MediaSource.IMG -> "聊天窗口里展示的图片"
    MediaSource.RAW -> "收到的原始图片"
    MediaSource.THUMB -> "缩略图"
}