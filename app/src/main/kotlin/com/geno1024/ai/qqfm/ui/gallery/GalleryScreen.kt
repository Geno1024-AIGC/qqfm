package com.geno1024.ai.qqfm.ui.gallery

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    gridState: LazyGridState,
    onOpen: (MediaItem) -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSources: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    BackHandler(enabled = searching) {
        searching = false
        viewModel.setSearchQuery("")
    }
    // Held here rather than in the view model: it is a question about this screen, and
    // it has to survive recompositions while the grid redraws underneath it.
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            GalleryTopBar(
                state = state,
                viewModel = viewModel,
                onOpenAbout = onOpenAbout,
                onOpenSources = onOpenSources,
                searching = searching,
                onSearchingChange = { searching = it },
            )
        },
        bottomBar = {
            if (state.selectionActive) SelectionBar(state, viewModel)
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.rootAvailable == false -> RootPrompt(onRetry = viewModel::retryRoot)
                state.loading -> Centered { CircularProgressIndicator() }
                state.scanning && state.items.isEmpty() -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("正在扫描…")
                        Text("已发现 ${state.scannedCount} 张")
                    }
                }
                state.items.isEmpty() -> Centered {
                    Text(
                        if (state.searchQuery.isNotBlank()) {
                            "没有匹配的文字\n只搜索已经打开过的图片"
                        } else {
                            "没有找到图片"
                        },
                    )
                }
                else -> GalleryGrid(state, viewModel, gridState, onOpen)
            }
            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
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
    onOpenAbout: () -> Unit,
    onOpenSources: () -> Unit,
    searching: Boolean,
    onSearchingChange: (Boolean) -> Unit,
) {
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(searching) {
        if (searching) searchFocus.requestFocus()
    }
    TopAppBar(
        title = {
            when {
                searching -> TextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::setSearchQuery,
                    placeholder = { Text("搜索图片里的文字") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                state.selectionActive -> Text("已选 ${state.selection.size} 项")
                else -> Text("${state.items.size} 张")
            }
        },
        navigationIcon = {
            when {
                searching -> IconButton(
                    onClick = {
                        onSearchingChange(false)
                        viewModel.setSearchQuery("")
                    },
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出搜索")
                }
                state.selectionActive -> IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "取消选择")
                }
                else -> IconButton(onClick = onOpenSources) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "切换目录")
                }
            }
        },
        actions = {
            when {
                searching -> Unit
                state.selectionActive -> {
                    IconButton(onClick = viewModel::selectAll) {
                        Icon(Icons.Filled.Done, contentDescription = "全选")
                    }
                    IconButton(onClick = viewModel::deleteSelected, enabled = !state.busy) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                }
                else -> {
                    IconButton(onClick = { onSearchingChange(true) }) {
                        Icon(Icons.Filled.Search, contentDescription = "搜索文字")
                    }
                    SortMenu(state.sortMode, viewModel::setSortMode)
                    IconButton(onClick = viewModel::refresh, enabled = !state.scanning) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                    }
                    IconButton(onClick = onOpenAbout) {
                        Icon(Icons.Filled.Info, contentDescription = "关于")
                    }
                }
            }
        },
    )
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
private fun SelectionBar(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
) {
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
    gridState: LazyGridState,
    onOpen: (MediaItem) -> Unit,
) {
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
        // Emitted as runs rather than one item per entry: a group is a header followed
        // by a long stretch of cells, and registering 165k individual providers to
        // rebuild that list on every composition would cost far more than it saves.
        var cursor = 0
        while (cursor < rows.size) {
            val header = rows[cursor] as? GalleryRow.Header
            if (header != null) {
                stickyHeader(key = "h:${header.key}") {
                    GroupHeader(
                        row = header,
                        collapsed = header.key in state.collapsed,
                        onClick = { viewModel.toggleCollapse(header.key) },
                    )
                }
                cursor++
                continue
            }
            var end = cursor
            while (end < rows.size && rows[end] !is GalleryRow.Header) end++
            val from = cursor
            items(
                count = end - from,
                key = { offset -> "i:${cellAt(rows, state.items, from, offset)?.id}" },
            ) { offset ->
                val item = cellAt(rows, state.items, from, offset) ?: return@items
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
            cursor = end
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
/** The image a run of cells stands for, or null if the row list moved under us. */
private fun cellAt(
    rows: List<GalleryRow>,
    items: List<MediaItem>,
    from: Int,
    offset: Int,
): MediaItem? {
    val entry = rows.getOrNull(from + offset) as? GalleryRow.Item ?: return null
    return items.getOrNull(entry.index)
}

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