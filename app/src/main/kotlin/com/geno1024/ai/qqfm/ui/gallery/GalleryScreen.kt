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
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    state: GalleryUiState,
    viewModel: GalleryViewModel,
    onOpen: (Int) -> Unit,
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
                state.loading -> Centered { CircularProgressIndicator() }
                state.scanning && state.items.isEmpty() ->
                    Centered { Text("正在扫描… 已发现 ${state.scannedCount} 张") }
                state.items.isEmpty() -> Centered { Text("没有找到图片") }
                else -> GalleryGrid(state, viewModel, onOpen)
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
                Text("QQ 图片 · ${state.items.size}")
            }
        },
        navigationIcon = {
            if (state.selectionActive) {
                IconButton(onClick = viewModel::clearSelection) {
                    Icon(Icons.Filled.Close, contentDescription = "取消选择")
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
    onOpen: (Int) -> Unit,
) {
    val gridState: LazyGridState = rememberLazyGridState()
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Adaptive(minSize = 108.dp),
        contentPadding = PaddingValues(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { position ->
                        gridState.indexAt(position)?.let(viewModel::beginDrag)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        gridState.indexAt(change.position)?.let(viewModel::extendDrag)
                    },
                    onDragEnd = viewModel::endDrag,
                    onDragCancel = viewModel::endDrag,
                )
            },
    ) {
        itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
            GridCell(
                item = item,
                selected = item.id in state.selection,
                selectionActive = state.selectionActive,
                imageStore = viewModel.imageStore,
                onClick = {
                    if (state.selectionActive) viewModel.toggleSelection(item.id) else onOpen(index)
                },
            )
        }
    }
}

@Composable
private fun GridCell(
    item: MediaItem,
    selected: Boolean,
    selectionActive: Boolean,
    imageStore: ImageStore,
    onClick: () -> Unit,
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
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
        }
        if (selectionActive) {
            SelectionBadge(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun SelectionBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.35f)),
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

private fun LazyGridState.indexAt(position: Offset): Int? =
    layoutInfo.visibleItemsInfo.firstOrNull { info ->
        position.x >= info.offset.x && position.x < info.offset.x + info.size.width &&
            position.y >= info.offset.y && position.y < info.offset.y + info.size.height
    }?.index

private fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.TIME_DESC -> "时间（新 → 旧）"
    SortMode.TIME_ASC -> "时间（旧 → 新）"
    SortMode.SIZE_DESC -> "大小（大 → 小）"
    SortMode.SIZE_ASC -> "大小（小 → 大）"
    SortMode.NAME -> "名称"
}
