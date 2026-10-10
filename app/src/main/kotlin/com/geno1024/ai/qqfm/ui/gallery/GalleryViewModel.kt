package com.geno1024.ai.qqfm.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.qqfm.data.AppSettings
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaRepository
import com.geno1024.ai.qqfm.data.MediaSource
import com.geno1024.ai.qqfm.data.RootShell
import com.geno1024.ai.qqfm.ui.formatBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

enum class SortMode {
    TIME_DESC,
    TIME_ASC,
    SIZE_DESC,
    SIZE_ASC,
    NAME,
}

/**
 * A single slot of the gallery grid. Grouped sorts insert [GalleryRow.Header]
 * entries; [GalleryRow.Item] carries the index of the entry in [GalleryUiState.items]
 * so drag selection keeps working unchanged over grouped lists.
 */
sealed interface GalleryRow {
    data class Header(val key: String, val label: String, val count: Int, val bytes: Long) : GalleryRow
    data class Item(val index: Int) : GalleryRow
}

data class GalleryUiState(
    val rootAvailable: Boolean? = null,
    val loading: Boolean = true,
    val scanning: Boolean = false,
    val scannedCount: Int = 0,
    val source: MediaSource? = null,
    val pickerVisible: Boolean = false,
    val items: List<MediaItem> = emptyList(),
    val totalBytes: Long = 0L,
    val sortMode: SortMode = SortMode.TIME_DESC,
    val collapsed: Set<String> = emptySet(),
    val displayRows: List<GalleryRow> = emptyList(),
    val frozen: Set<String> = emptySet(),
    val cleanedFiles: Long = 0L,
    val cleanedBytes: Long = 0L,
    val selection: Set<String> = emptySet(),
    val selectionBytes: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val selectionActive: Boolean get() = selection.isNotEmpty()
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MediaRepository(application)
    private val settings = AppSettings.of(application)
    val imageStore = ImageStore(application)

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var rawItems: List<MediaItem> = emptyList()
    private var byId: Map<String, MediaItem> = emptyMap()

    private var dragAnchorRow: Int = -1
    private var dragPinned: Set<String> = emptySet()

    init {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    frozen = settings.frozenIds,
                    cleanedFiles = settings.cleanedFiles,
                    cleanedBytes = settings.cleanedBytes,
                )
            }
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            _state.update { it.copy(rootAvailable = root, loading = false) }
        }
    }

    fun retryRoot() {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            _state.update { it.copy(rootAvailable = root) }
        }
    }

    /** Switches the browsed tree; the first choice is what the gallery opens with. */
    fun chooseSource(source: MediaSource) {
        val changed = _state.value.source != source
        _state.update {
            it.copy(
                source = source,
                pickerVisible = false,
                collapsed = emptySet(),
                selection = emptySet(),
                selectionBytes = 0L,
                message = null,
                items = if (changed) emptyList() else it.items,
                loading = changed,
                scanning = false,
                scannedCount = 0,
                totalBytes = 0L,
            )
        }
        endDrag()
        rawItems = emptyList()
        byId = emptyMap()
        if (changed && _state.value.rootAvailable == true) load(forceScan = false)
    }

    fun openPicker() = _state.update { it.copy(pickerVisible = true) }

    fun dismissPicker() = _state.update { it.copy(pickerVisible = false) }

    fun refresh() {
        if (_state.value.rootAvailable == true) load(forceScan = true)
    }

    private fun load(forceScan: Boolean) {
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanning = false, message = null) }
            val cached = if (forceScan) null
            else withContext(Dispatchers.IO) { repository.loadCached(source) }

            if (cached != null) {
                applyItems(source, cached, fromScan = false)
                return@launch
            }

            _state.update { it.copy(scanning = true, scannedCount = 0) }
            val outcome = withContext(Dispatchers.IO) {
                repository.scan(source) { count -> _state.update { it.copy(scannedCount = count) } }
            }
            applyItems(source, outcome.items, fromScan = true)
        }
    }

    private fun applyItems(source: MediaSource, items: List<MediaItem>, fromScan: Boolean) {
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
            ).withRows()
        }
    }

    fun setSortMode(mode: SortMode) {
        endDrag()
        _state.update {
            it.copy(sortMode = mode, items = sort(rawItems, mode), selection = emptySet(), selectionBytes = 0L)
                .withRows()
        }
    }

    fun toggleCollapse(key: String) {
        endDrag()
        _state.update {
            val next = it.collapsed.toMutableSet()
            if (!next.add(key)) next.remove(key)
            it.copy(collapsed = next).withRows()
        }
    }

    fun toggleSelection(id: String) {
        if (id in _state.value.frozen) return
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
            selection = it.items.filterNot { item -> item.id in it.frozen }
                .mapTo(HashSet()) { item -> item.id },
            selectionBytes = it.items.filterNot { item -> item.id in it.frozen }.sumOf { it.size },
        )
    }

    /** Pins or unpins an item so drag selection and select-all leave it alone. */
    fun toggleFrozen(id: String) {
        val now = _state.value.frozen
        val next = if (id in now) now - id else now + id
        settings.frozenIds = next
        _state.update { state ->
            val selection = if (id in next) state.selection - id else state.selection
            state.copy(frozen = next, selection = selection, selectionBytes = bytesOf(selection))
        }
    }

    /**
     * Starts a drag selection at a grid row.
     *
     * Rows, not item indices: a collapsed group leaves its items out of
 * [GalleryUiState.displayRows] while they keep their positions in the items list,
 * so a range over item indices would sweep up everything folded away in between.
 */
    fun beginDrag(row: Int) {
        val state = _state.value
        val entry = state.displayRows.getOrNull(row) as? GalleryRow.Item ?: return
        val item = state.items.getOrNull(entry.index) ?: return
        if (item.id in state.frozen) return
        dragPinned = state.selection
        dragAnchorRow = row
        updateDragSelection(dragPinned + item.id)
    }

    fun extendDrag(row: Int) {
        if (dragAnchorRow < 0) return
        val state = _state.value
        if (row !in state.displayRows.indices) return
        val range = if (dragAnchorRow <= row) dragAnchorRow..row else row..dragAnchorRow
        val ids = range
            .mapNotNull { state.displayRows[it] as? GalleryRow.Item }
            .mapNotNull { entry -> state.items.getOrNull(entry.index)?.id }
            .toHashSet()
        updateDragSelection(dragPinned + ids)
    }

    private fun updateDragSelection(selection: Set<String>) {
        _state.update { state ->
            val picked = selection - state.frozen
            state.copy(selection = picked, selectionBytes = bytesOf(picked))
        }
    }

    fun endDrag() {
        dragAnchorRow = -1
        dragPinned = emptySet()
    }

    fun deleteSelected() {
        val snapshot = _state.value
        val selected = snapshot.items.filter { it.id in snapshot.selection }
        if (selected.isEmpty()) return
        val source = snapshot.source ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { repository.delete(selected) }
            recordCleanup(result)
            val removed = selected.mapTo(HashSet()) { it.id }
            rawItems = rawItems.filterNot { it.id in removed }
            byId = rawItems.associateBy { it.id }
            withContext(Dispatchers.IO) { repository.save(source, rawItems) }
            _state.update {
                it.copy(
                    items = sort(rawItems, it.sortMode),
                    totalBytes = rawItems.sumOf { item -> item.size },
                    selection = emptySet(),
                    selectionBytes = 0L,
                    busy = false,
                    message = "已删除 ${result.filesRemoved} 张图片，释放约 " +
                        formatBytes(result.bytesReclaimed),
                ).withRows()
            }
        }
    }

    /** Deletes one item, as triggered from the viewer. */
    fun deleteOne(item: MediaItem) {
        if (item.id !in byId) return
        val source = _state.value.source ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { repository.delete(listOf(item)) }
            recordCleanup(result)
            rawItems = rawItems.filterNot { it.id == item.id }
            byId = rawItems.associateBy { it.id }
            withContext(Dispatchers.IO) { repository.save(source, rawItems) }
            _state.update {
                it.copy(
                    items = sort(rawItems, it.sortMode),
                    totalBytes = rawItems.sumOf { entry -> entry.size },
                    selection = it.selection - item.id,
                    selectionBytes = bytesOf(it.selection - item.id),
                    busy = false,
                    message = "已删除 ${result.filesRemoved} 张图片，释放约 " +
                        formatBytes(result.bytesReclaimed),
                ).withRows()
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun bytesOf(selection: Set<String>): Long =
        selection.sumOf { byId[it]?.size ?: 0L }

    /** Folds a finished deletion into the lifetime tally the about page shows. */
    private fun recordCleanup(result: com.geno1024.ai.qqfm.data.MediaRepository.DeleteResult) {
        if (result.filesRemoved <= 0L && result.bytesReclaimed <= 0L) return
        settings.recordCleanup(result.filesRemoved, result.bytesReclaimed)
        _state.update {
            it.copy(
                cleanedFiles = it.cleanedFiles + result.filesRemoved,
                cleanedBytes = it.cleanedBytes + result.bytesReclaimed,
            )
        }
    }

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

    /** Refills [GalleryUiState.displayRows] after a change to items, sort or collapse. */
    private fun GalleryUiState.withRows(): GalleryUiState = copy(
        displayRows = buildRows(items, sortMode, collapsed),
    )

    private fun buildRows(items: List<MediaItem>, mode: SortMode, collapsed: Set<String>): List<GalleryRow> {
        val byTime = mode == SortMode.TIME_DESC || mode == SortMode.TIME_ASC
        val bySize = mode == SortMode.SIZE_DESC || mode == SortMode.SIZE_ASC
        if (!byTime && !bySize) {
            return items.indices.map { GalleryRow.Item(it) }
        }

        val rows = ArrayList<GalleryRow>(items.size + 16)
        var start = 0
        while (start < items.size) {
            val bucket = if (byTime) bucketTime(items[start].mtime) else bucketSize(items[start].size)
            var end = start
            var bytes = 0L
            while (end < items.size) {
                val entry = items[end]
                val other = if (byTime) bucketTime(entry.mtime) else bucketSize(entry.size)
                if (other.key != bucket.key) break
                bytes += entry.size
                end++
            }
            rows += GalleryRow.Header(bucket.key, bucket.label, end - start, bytes)
            if (bucket.key !in collapsed) {
                for (index in start until end) rows += GalleryRow.Item(index)
            }
            start = end
        }
        return rows
    }

    /** One grouping: [key] identifies the group, [label] is what the header shows. */
    private data class Bucket(val key: String, val label: String)

    /**
     * Buckets are ordered newest first to match the descending sorts they are grouped
     * for; an ascending sort naturally walks the same buckets in reverse. Anything
     * older than the current month falls into one group per month, because a single
     * "older" bucket would hide which years the backlog actually came from.
     */
    private fun bucketTime(epochMillis: Long): Bucket {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        val today = LocalDate.now(zone)
        return when {
            !date.isBefore(today) -> Bucket("t0", "今天")
            !date.isBefore(today.minusDays(1)) -> Bucket("t1", "昨天")
            !date.isBefore(today.with(DayOfWeek.MONDAY)) -> Bucket("t2", "本周")
            !date.isBefore(today.withDayOfMonth(1)) -> Bucket("t3", "本月")
            else -> Bucket("m:${date.year}-${date.monthValue}", "${date.year} 年 ${date.monthValue} 月")
        }
    }

    /**
     * Size groups double from a 64 KiB floor: 64–128 KiB, 128–256 KiB, and so on up
     * to 64 MiB, with everything above that in one open bucket.
     *
     * The steps are exact powers of two rather than hand-picked round numbers, so
     * the boundaries line up with what the unit means and adding a step later cannot
     * silently drop a range.
     */
    private fun bucketSize(bytes: Long): Bucket {
        if (bytes < SIZE_STEP) return Bucket("s0", "< ${plainSize(SIZE_STEP)}")
        if (bytes >= SIZE_TOP) return Bucket("stop", "≥ ${plainSize(SIZE_TOP)}")
        var index = 0
        var steps = bytes / SIZE_STEP
        while (steps >= 2L) {
            steps /= 2L
            index++
        }
        val low = SIZE_STEP shl index
        return Bucket("s${index + 1}", "${plainSize(low)} – ${plainSize(low * 2)}")
    }

    /** Sizes without the decimal fraction, for labels that are always round. */
    private fun plainSize(bytes: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return String.format(Locale.ROOT, "%.0f %s", value, units[unit])
    }
}

fun sortLabel(mode: SortMode): String = when (mode) {
    SortMode.TIME_DESC -> "时间（新 → 旧）"
    SortMode.TIME_ASC -> "时间（旧 → 新）"
    SortMode.SIZE_DESC -> "大小（大 → 小）"
    SortMode.SIZE_ASC -> "大小（小 → 大）"
    SortMode.NAME -> "名称"
}

private const val SIZE_STEP = 64L * 1024
private const val SIZE_TOP = 64L * 1024 * 1024
