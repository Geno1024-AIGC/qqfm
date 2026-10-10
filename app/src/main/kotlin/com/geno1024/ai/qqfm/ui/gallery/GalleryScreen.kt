package com.geno1024.ai.qqfm.ui.gallery

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaSource
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
    onOpenUpdates: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { GalleryTopBar(state, viewModel, onOpenUpdates) },
        bottomBar = {
            if (state.selectionActive) SelectionBar(state, viewModel)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.rootAvailable == false -> RootPrompt(onRetry = viewModel::retryRoot)
                state.source == null -> Unit
                state.loading -> Centered { CircularProgressIndicator() }
                state.scanning && state.items.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在扫描…")
                        Text("已发现 ${state.scannedCount} 张")
                    }
                }
                state.items.isEmpty() -> Centered { Text("没有找到图片") }
                else -> GalleryGrid(state, viewModel, onOpen)
            }
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
                )
            }
            if (state.source == null || state.pickerVisible) {
                SourcePicker(
                    current = state.source,
                    onPick = viewModel::chooseSource,
                    onDismiss = viewModel::dismissPicker,
                    dismissible = state.pickerVisible,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onOpenUpdates: () -> Unit,
) {
    TopAppBar(
        title = {
            if (state.selectionActive) {
                Text("已选 ${state.selection.size} 项")
            } else {
                Text("${state.source?.id ?: "QQ 图片"} · ${state.items.size}")
            }
        },
        navigationIcon = {
            if (state.selectionActive) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "取消选择")
                }
            } else {
                TextButton(onClick = viewModel::openPicker) {
                    Text(state.source?.id ?: "选择目录")
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换目录")
                }
            }
        },
        actions = {
            if (state.selectionActive) {
                IconButton(onClick = viewModel::selectAll) {
                    Icon(Icons.Filled.Done, contentDescription = "全选")
                }
                IconButton(onClick = viewModel::deleteSelected, enabled = !state.busy) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            } else {
                SortMenu(state.sortMode, viewModel::setSortMode)
                IconButton(onClick = viewModel::refresh, enabled = !state.scanning) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                }
                IconButton(onClick = onOpenUpdates) {
                    Icon(Icons.Filled.Info, contentDescription = "升级")
                }
            }
        },
    )
}

@Composable
private fun SourcePicker(
    current: MediaSource?,
    onPick: (MediaSource) -> Unit,
    onDismiss: () -> Unit,
    dismissible: Boolean,
) {
    Dialog(
        onDismissRequest = { if (dismissible) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
        ),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "选择图片目录",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                )
                MediaSource.entries.forEach { source ->
                    ListItem(
                        headlineContent = { Text(source.id) },
                        supportingContent = { Text(sourceHint(source)) },
                        leadingContent = {
                            if (source == current) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        },
                        modifier = Modifier.clickable { onPick(source) },
                    )
                }
            }
        }
    }
}

private fun sourceHint(source: MediaSource): String = when (source) {
    MediaSource.IMG -> "聊天窗口里展示的图片"
    MediaSource.RAW -> "收到的原始图片"
    MediaSource.THUMB -> "缩略图"
}

@Composable
private fun SortMenu(current: SortMode, onSelect: (SortMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "排序")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SortMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(sortLabel(mode)) },
                    onClick = {
                        expanded = false
                        onSelect(mode)
                    },
                    trailingIcon = {
                        if (mode == current) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectionBar(state: GalleryUiState, viewModel: GalleryViewModel) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${state.selection.size} 项 · ${formatBytes(state.selectionBytes)}")
            Spacer(Modifier.weight(1f))
            Button(onClick = viewModel::deleteSelected, enabled = !state.busy) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("删除")
            }
        }
    }
}

@Composable
private fun GalleryGrid(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onOpen: (MediaItem) -> Unit,
) {
    val gridState: LazyGridState = rememberLazyGridState()
    val rows = state.displayRows
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        gridState.rowAt(position)?.let(viewModel::beginDrag)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        gridState.rowAt(change.position)?.let(viewModel::extendDrag)
                    },
                    onDragEnd = viewModel::endDrag,
                    onDragCancel = viewModel::endDrag,
                )
            },
    ) {
        itemsIndexed(
            items = rows,
            key = { _, row ->
                when (row) {
                    is GalleryRow.Header -> "h:${row.key}"
                    is GalleryRow.Item -> "i:${state.items[row.index].id}"
                }
            },
            span = { _, row ->
                if (row is GalleryRow.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
        ) { _, row ->
            when (row) {
                is GalleryRow.Header -> GroupHeader(
                    row = row,
                    collapsed = row.key in state.collapsed,
                    onClick = { viewModel.toggleCollapse(row.key) },
                )
                is GalleryRow.Item -> {
                    val item = state.items[row.index]
                    GridCell(
                        item = item,
                        selected = item.id in state.selection,
                        selectionActive = state.selectionActive,
                        frozen = item.id in state.frozen,
                        imageStore = viewModel.imageStore,
                        onClick = { onOpen(item) },
                        onToggleSelection = { viewModel.toggleSelection(item.id) },
                        onToggleFrozen = { viewModel.toggleFrozen(item.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(row: GalleryRow.Header, collapsed: Boolean, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (collapsed) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = row.label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp),
            )
            Text(
                text = " ${row.count}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = formatBytes(row.bytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun GridCell(
    item: MediaItem,
    selected: Boolean,
    selectionActive: Boolean,
    frozen: Boolean,
    imageStore: ImageStore,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onToggleFrozen: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
        value = imageStore.thumbnail(item, 320)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center).size(22.dp),
                strokeWidth = 2.dp,
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)),
                    ),
                )
                .padding(horizontal = 4.dp, vertical = 3.dp),
        ) {
            Text(
                text = formatBytes(item.size),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = formatTimestamp(item.mtime, short = true),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }

        if (frozen) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .size(18.dp),
            )
        }
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
        }
        if (selectionActive && !frozen) {
            SelectionBadge(
                selected = selected,
                onClick = onToggleSelection,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
            )
        }
        if (!selectionActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.35f))
                    .clickable(onClick = onToggleFrozen),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (frozen) Icons.Filled.Lock else Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

@Composable
private fun SelectionBadge(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun RootPrompt(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "未获得 root 权限",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            "QQ 的图片缓存位于 Android/data 私有目录，读取和删除都需要 root。请在授权提示中允许本应用使用 root，然后重试。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Maps a pointer position to the index of the item it lands on, ignoring headers. */
/**
 * The grid row under [position], read from the live layout.
 *
 * The row list must not be captured here: collapsing a group rebuilds the list,
 * and a captured copy would be indexed by the *new* layout and pick the cell of
 * whatever group used to sit there.
 */
private fun LazyGridState.rowAt(position: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { info ->
        position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
            position.y >= info.offset.y && position.y < info.offset.y + info.size.height
    }?.index