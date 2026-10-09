package com.geno1024.ai.qqfm.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaRepository
import com.geno1024.ai.qqfm.data.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortMode {
    TIME_DESC,
    TIME_ASC,
    SIZE_DESC,
    SIZE_ASC,
    NAME,
}

data class GalleryUiState(
    val rootAvailable: Boolean? = null,
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val items: List<MediaItem> = emptyList(),
    val totalBytes: Long = 0L,
    val sortMode: SortMode = SortMode.TIME_DESC,
    val selection: Set<String> = emptySet(),
    val selectionBytes: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val selectionActive: Boolean get() = selection.isNotEmpty()
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    val imageStore = ImageStore(application)

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var rawItems: List<MediaItem> = emptyList()
    private var byId: Map<String, MediaItem> = emptyMap()

    private var dragAnchor: Int = -1
    private var dragPinned: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            _state.update { it.copy(rootAvailable = root) }
            if (root) load(forceScan = false) else _state.update { it.copy(loading = false) }
        }
    }

    fun retryRoot() {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            _state.update { it.copy(rootAvailable = root) }
            if (root) load(forceScan = false)
        }
    }

    fun refresh() {
        if (_state.value.rootAvailable == true) load(forceScan = true)
    }

    private fun load(forceScan: Boolean) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanning = false, message = null) }
            val cached = if (forceScan) null
            else withContext(Dispatchers.IO) { repository.loadCached() }

            if (cached != null) {
                applyItems(cached, fromScan = false)
                return@launch
            }

            _state.update { it.copy(scanning = true, scannedCount = 0) }
            val outcome = withContext(Dispatchers.IO) {
                repository.scan { count -> _state.update { it.copy(scannedCount = count) } }
            }
            applyItems(outcome.items, fromScan = true)
        }
    }

    private fun applyItems(items: List<MediaItem>, fromScan: Boolean) {
        rawItems = items
        byId = items.associateBy { it.id }
        _state.update {
            it.copy(
                items = sort(items, it.sortMode),
                totalBytes = items.sumOf { item -> item.size },
                selection = emptySet(),
                selectionBytes = 0L,
                loading = false,
                scanning = false,
                scannedCount = items.size,
                message = if (fromScan) "扫描完成：${items.size} 张图片" else null,
            )
        }
    }

    fun setSortMode(mode: SortMode) {
        endDrag()
        _state.update {
            it.copy(sortMode = mode, items = sort(rawItems, mode), selection = emptySet(), selectionBytes = 0L)
        }
    }

    fun toggleSelection(id: String) {
        _state.update {
            val next = it.selection.toMutableSet()
            val active = if (!next.add(id)) {
                next.remove(id)
                false
            } else true
            it.copy(
                selection = next,
                selectionBytes = it.selectionBytes + (byId[id]?.size ?: 0L) * (if (active) 1 else -1),
            )
        }
    }

    fun clearSelection() = _state.update { it.copy(selection = emptySet(), selectionBytes = 0L) }

    fun selectAll() = _state.update {
        it.copy(
            selection = it.items.mapTo(HashSet()) { item -> item.id },
            selectionBytes = it.totalBytes,
        )
    }

    fun beginDrag(index: Int) {
        val items = _state.value.items
        if (index !in items.indices) return
        dragPinned = _state.value.selection
        dragAnchor = index
        updateDragSelection(dragPinned + items[index].id)
    }

    fun extendDrag(index: Int) {
        if (dragAnchor < 0) return
        val items = _state.value.items
        if (index !in items.indices) return
        val range = if (dragAnchor <= index) dragAnchor..index else index..dragAnchor
        val ids = range.mapTo(HashSet()) { items[it].id }
        updateDragSelection(dragPinned + ids)
    }

    private fun updateDragSelection(selection: Set<String>) {
        _state.update { it.copy(selection = selection, selectionBytes = bytesOf(selection)) }
    }

    fun endDrag() {
        dragAnchor = -1
        dragPinned = emptySet()
    }

    fun deleteSelected() {
        val snapshot = _state.value
        val selected = snapshot.items.filter { it.id in snapshot.selection }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { repository.delete(selected) }
            val removed = selected.mapTo(HashSet()) { it.id }
            rawItems = rawItems.filterNot { it.id in removed }
            byId = rawItems.associateBy { it.id }
            withContext(Dispatchers.IO) { repository.save(rawItems) }
            _state.update {
                it.copy(
                    items = sort(rawItems, it.sortMode),
                    totalBytes = rawItems.sumOf { item -> item.size },
                    selection = emptySet(),
                    selectionBytes = 0L,
                    busy = false,
                    message = "已删除 ${result.filesRemoved} 个文件，释放约 " +
                        formatBytes(result.bytesReclaimed),
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun bytesOf(selection: Set<String>): Long =
        selection.sumOf { byId[it]?.size ?: 0L }

    private fun sort(items: List<MediaItem>, mode: SortMode): List<MediaItem> {
        val comparator = when (mode) {
            SortMode.TIME_DESC -> compareByDescending<MediaItem> { it.mtime }.thenBy { it.base }
            SortMode.TIME_ASC -> compareBy<MediaItem> { it.mtime }.thenBy { it.base }
            SortMode.SIZE_DESC -> compareByDescending<MediaItem> { it.size }.thenBy { it.base }
            SortMode.SIZE_ASC -> compareBy<MediaItem> { it.size }.thenBy { it.base }
            SortMode.NAME -> compareBy { it.base }
        }
        return items.sortedWith(comparator)
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var index = 0
    while (value >= 1024 && index < units.lastIndex) {
        value /= 1024
        index++
    }
    return String.format("%.2f %s", value, units[index])
}
