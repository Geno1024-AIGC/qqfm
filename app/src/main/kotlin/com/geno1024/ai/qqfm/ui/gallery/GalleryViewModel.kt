package com.geno1024.ai.qqfm.ui.gallery

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.qqfm.data.AppSettings
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaRepository
import com.geno1024.ai.qqfm.data.MediaSource
import com.geno1024.ai.qqfm.data.OcrEngine
import com.geno1024.ai.qqfm.data.OcrIndex
import com.geno1024.ai.qqfm.data.OcrScanBus
import com.geno1024.ai.qqfm.data.OcrScanStatus
import com.geno1024.ai.qqfm.data.RootShell
import com.geno1024.ai.qqfm.ocr.OcrScanService
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
    val sources: Set<MediaSource> = emptySet(),
    val items: List<MediaItem> = emptyList(),
    val totalBytes: Long = 0L,
    val sortMode: SortMode = SortMode.TIME_DESC,
    val collapsed: Set<String> = emptySet(),
    val displayRows: List<GalleryRow> = emptyList(),
    val frozen: Set<String> = emptySet(),
    val cleanedFiles: Long = 0L,
    val cleanedBytes: Long = 0L,
    val persistScanIndex: Boolean = true,
    val hideFrozen: Boolean = false,
    val searchQuery: String = "",
    /** Ids whose OCR text contains [searchQuery]; null when no search is active. */
    val ocrMatches: Set<String>? = null,
    val ocrIndexed: Int = 0,
    val selection: Set<String> = emptySet(),
    val selectionBytes: Long = 0L,
    val busy: Boolean = false,
    val message: String? = null,
) {
    val selectionActive: Boolean get() = selection.isNotEmpty()

    /** Number of selected sources, for the top bar. */
    val sourceLabel: String
        get() = if (sources.isEmpty()) "" else "${sources.size} 个目录" 
}

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = AppSettings.of(application)
    private val repository = MediaRepository(application) { settings.persistScanIndex }
    val imageStore = ImageStore(application)

    private val ocrIndex = OcrIndex(application)
    /** Built on first use: the model and its native libraries are not needed to browse. */
    private var ocrEngine: OcrEngine? = null
    /** Guards against starting the same picture twice while its first pass runs. */
    private val ocrInFlight = HashSet<String>()

    private val _state = MutableStateFlow(GalleryUiState())
    val state: StateFlow<GalleryUiState> = _state.asStateFlow()

    private var rawItems: List<MediaItem> = emptyList()
    private var byId: Map<String, MediaItem> = emptyMap()

    private var dragAnchorRow: Int = -1
    private var dragPinned: Set<String> = emptySet()

    init {
        // Restores a pass paused by a previous process before the first frame, so the
        // about page opens showing the real state instead of a blank idle.
        OcrScanBus.attach(application)
        viewModelScope.launch {
            _state.update {
                it.copy(
                    frozen = settings.frozenIds,
                    cleanedFiles = settings.cleanedFiles,
                    cleanedBytes = settings.cleanedBytes,
                    persistScanIndex = settings.persistScanIndex,
                    hideFrozen = settings.hideFrozen,
                )
            }
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            val indexed = withContext(Dispatchers.IO) { ocrIndex.count() }
            _state.update { it.copy(rootAvailable = root, loading = false, ocrIndexed = indexed) }
        }
    }

    fun retryRoot() {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { RootShell.isRootAvailable() }
            _state.update { it.copy(rootAvailable = root) }
        }
    }

    /** Which trees the picker should tick when it opens. */
    fun rememberedSources(): Set<MediaSource> = settings.selectedSources

    /**
     * Browses exactly [sources], merged into one list.
     *
     * Several trees at once is the point: the same picture can sit in more than one of
     * them, and merged they sort and group together as if they were one directory.
     */
    fun chooseSources(sources: Set<MediaSource>) {
        if (sources.isEmpty()) return
        val changed = _state.value.sources != sources
        settings.selectedSources = sources
        _state.update {
            it.copy(
                sources = sources,
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
        if (changed) {
            rawItems = emptyList()
            byId = emptyMap()
            if (_state.value.rootAvailable == true) load(forceScan = false)
        } else {
            republish()
        }
    }

    fun refresh() {
        if (_state.value.rootAvailable == true) load(forceScan = true)
    }

    /** Keeps a frozen image out of the gallery, for people who pin to forget. */
    fun setHideFrozen(enabled: Boolean) {
        settings.hideFrozen = enabled
        _state.update { it.copy(hideFrozen = enabled) }
        if (_state.value.sources.isNotEmpty()) republish()
    }

    /**
     * Filters the gallery by the text OCR has read out of the pictures.
     *
     * An empty query means no filter. The match set is computed off the main thread
     * and then the grid is rebuilt in one go, so typing does not stall on sorting
     * 165k entries.
     */
    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        viewModelScope.launch {
            val trimmed = query.trim()
            val matches = withContext(Dispatchers.IO) {
                if (trimmed.isEmpty()) null else ocrIndex.search(trimmed)
            }
            // Typing fires one query per keystroke; a slow early one must not overwrite
            // the results of a later, shorter query.
            var current = false
            _state.update {
                current = it.searchQuery.trim() == trimmed
                if (current) it.copy(ocrMatches = matches) else it
            }
            if (current) publishFiltered()
        }
    }

    private fun publishFiltered() {
        viewModelScope.launch {
            val snapshot = _state.value
            val published = withContext(Dispatchers.Default) {
                val visible = searchFiltered(visibleItemsOf(rawItems), snapshot)
                val sorted = sort(visible, snapshot.sortMode)
                Published(
                    items = sorted,
                    displayRows = buildRows(sorted, snapshot.sortMode, snapshot.collapsed),
                    totalBytes = visible.sumOf { item -> item.size },
                )
            }
            _state.update { state ->
                val visibleIds = published.items.mapTo(HashSet()) { it.id }
                val selection = state.selection intersect visibleIds
                state.copy(
                    items = published.items,
                    displayRows = published.displayRows,
                    totalBytes = published.totalBytes,
                    selection = selection,
                    selectionBytes = bytesOf(selection),
                )
            }
        }
    }

    /**
     * Reads the text out of one picture and files it away, once.
     *
     * Called whenever an image is opened: the first pass costs a decode plus a model
     * run, later opens hit the index. A picture already being recognized is skipped
     * rather than queued twice.
     */
    private fun engine(): OcrEngine = ocrEngine ?: OcrEngine().also { ocrEngine = it }

    fun ensureOcr(item: MediaItem) {
        if (!ocrInFlight.add(item.id)) return
        viewModelScope.launch {
            try {
                if (withContext(Dispatchers.IO) { ocrIndex.contains(item.id) }) return@launch
                val bitmap = imageStore.ocrBitmap(item) ?: return@launch
                val text = runCatching { engine().recognize(bitmap) }.getOrNull() ?: return@launch
                withContext(Dispatchers.IO) { ocrIndex.put(item.id, text) }
                val total = withContext(Dispatchers.IO) { ocrIndex.count() }
                _state.update { it.copy(ocrIndexed = total) }
            } finally {
                ocrInFlight.remove(item.id)
            }
        }
    }

    /** Hands back the recognized text, running OCR first when it has not been done. */
    fun requestOcrText(item: MediaItem, onReady: (String?) -> Unit) {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { ocrIndex.textOf(item.id) }
            if (!cached.isNullOrBlank()) {
                onReady(cached)
                return@launch
            }
            val bitmap = imageStore.ocrBitmap(item)
            if (bitmap == null) {
                onReady(null)
                return@launch
            }
            val text = runCatching { engine().recognize(bitmap) }.getOrNull()
            if (text != null) {
                withContext(Dispatchers.IO) { ocrIndex.put(item.id, text) }
                val total = withContext(Dispatchers.IO) { ocrIndex.count() }
                _state.update { it.copy(ocrIndexed = total) }
            }
            onReady(text)
        }
    }

    /** Starts the full-library pass, or starts over once the last one finished. */
    fun startOcrScan() {
        if (OcrScanBus.state.value.status == OcrScanStatus.DONE) OcrScanBus.reset()
        OcrScanService.start(getApplication())
    }

    fun pauseOcrScan() = OcrScanService.pause(getApplication())

    fun stopOcrScan() = OcrScanService.stop(getApplication())

    /** Discards the saved cursor, for retrying a pass that mostly failed. */
    fun restartOcrScan() = OcrScanService.restart(getApplication())

    /** Reads only the pictures that still have no OCR text, including past failures. */
    fun backfillOcrScan() = OcrScanService.backfill(getApplication())

    /** The service writes to the same database; refresh the count as it progresses. */
    fun refreshOcrCount() {
        viewModelScope.launch {
            val total = withContext(Dispatchers.IO) { ocrIndex.count() }
            _state.update { if (it.ocrIndexed == total) it else it.copy(ocrIndexed = total) }
        }
    }

    fun clearOcrIndex() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ocrIndex.clear() }
            _state.update { it.copy(ocrIndexed = 0, ocrMatches = if (it.searchQuery.isBlank()) null else emptySet()) }
            publishFiltered()
        }
    }

    override fun onCleared() {
        ocrEngine?.close()
        super.onCleared()
    }

    private fun republish() {
        // [rawItems] stays the whole scan: hiding frozen images filters what is shown,
        // it never forgets them. Otherwise turning the filter back off could not bring
        // them back, and the count would keep shrinking on every republish.
        val visible = searchFiltered(visibleItemsOf(rawItems), _state.value)
        byId = rawItems.associateBy { it.id }
        _state.update { state ->
            val selection = state.selection intersect visible.mapTo(HashSet()) { entry -> entry.id }
            state.copy(
                items = sort(visible, state.sortMode),
                totalBytes = visible.sumOf { item -> item.size },
                selection = selection,
                selectionBytes = bytesOf(selection),
            ).withRows()
        }
    }

    private fun visibleItemsOf(items: List<MediaItem>): List<MediaItem> {
        val frozen = _state.value.frozen
        val hide = _state.value.hideFrozen
        return if (hide && frozen.isNotEmpty()) items.filterNot { it.id in frozen } else items
    }

    /** The part of an empty search query that still lets tasks run unchanged. */
    private fun searchFiltered(items: List<MediaItem>, state: GalleryUiState): List<MediaItem> {
        if (state.searchQuery.isBlank()) return items
        val matches = state.ocrMatches ?: return emptyList()
        return items.filter { it.id in matches }
    }

    /**
     * Loads every selected tree, from the index when there is one and from disk
     * otherwise. Each tree keeps its own index on disk, so switching the selection
     * back and forth stays cheap.
     */
    private fun load(forceScan: Boolean) {
        val sources = _state.value.sources
        if (sources.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, scanning = false, message = null) }
            if (!forceScan) {
                val cached = withContext(Dispatchers.IO) {
                    sources.mapNotNull { source -> repository.loadCached(source) }
                }
                if (cached.isNotEmpty()) {
                    applyItems(cached.flatten(), fromScan = false)
                    return@launch
                }
            }

            _state.update { it.copy(scanning = true, scannedCount = 0) }
            val collected = ArrayList<MediaItem>(sources.size * 100_000)
            val counted = intArrayOf(0)
            for (source in sources) {
                val outcome = withContext(Dispatchers.IO) {
                    repository.scan(source) { found ->
                        _state.update { it.copy(scannedCount = counted[0] + found) }
                    }
                }
                collected += outcome.items
                counted[0] += outcome.items.size
                _state.update { it.copy(scannedCount = counted[0]) }
            }
            applyItems(collected, fromScan = true)
        }
    }

    private fun applyItems(items: List<MediaItem>, fromScan: Boolean) {
        val visible = searchFiltered(visibleItemsOf(items), _state.value)
        // Keep the unscanned-for-nothing truth: [rawItems] carries every scanned item,
        // and the frozen filter only decides what gets published.
        rawItems = items
        byId = items.associateBy { it.id }
        _state.update {
            it.copy(
                items = sort(visible, it.sortMode),
                totalBytes = visible.sumOf { item -> item.size },
                selection = emptySet(),
                selectionBytes = 0L,
                loading = false,
                scanning = false,
                scannedCount = visible.size,
                message = if (fromScan) "扫描完成：${visible.size} 张图片" else null,
            ).withRows()
        }
    }

    fun setSortMode(mode: SortMode) {
        endDrag()
        _state.update {
            it.copy(
                sortMode = mode,
                items = sort(searchFiltered(visibleItemsOf(rawItems), it), mode),
                selection = emptySet(),
                selectionBytes = 0L,
            ).withRows()
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
        // Frozen items can never enter the selection, but the rule is enforced here
        // too: unlocking is the only way past it, whatever called this.
        val selected = snapshot.items.filter {
            // byId only holds what is still on disk: a file deleted a moment ago is
            // already gone from it even though the grid has not caught up yet.
            it.id in snapshot.selection && it.id !in snapshot.frozen && it.id in byId
        }
        if (selected.isEmpty()) {
            if (snapshot.selection.isNotEmpty()) {
                _state.update { it.copy(message = "冻结的图片要先解锁才能删除") }
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { repository.delete(selected) }
            recordCleanup(result)
            val removed = selected.mapTo(HashSet()) { it.id }
            withContext(Dispatchers.IO) { forgetRemoved(selected, removed) }
            publishItems(
                if (result.errorMessage != null) {
                    result.errorMessage
                } else {
                    "已删除 ${result.filesRemoved} 张图片，释放约 " + formatBytes(result.bytesReclaimed)
                },
                removed,
                emptySet(),
            )
        }
    }

    /** Deletes one item, as triggered from the viewer. */
    fun deleteOne(item: MediaItem) {
        if (item.id !in byId) return
        if (item.id in _state.value.frozen) {
            _state.update { it.copy(message = "冻结的图片要先解锁才能删除") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { repository.delete(listOf(item)) }
            recordCleanup(result)
            withContext(Dispatchers.IO) { forgetRemoved(listOf(item), setOf(item.id)) }
            publishItems(
                if (result.errorMessage != null) {
                    result.errorMessage
                } else {
                    "已删除 ${result.filesRemoved} 张图片，释放约 " + formatBytes(result.bytesReclaimed)
                },
                setOf(item.id),
                _state.value.selection - item.id,
            )
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun bytesOf(selection: Set<String>): Long =
        selection.sumOf { byId[it]?.size ?: 0L }

    /**
     * Publishes a changed item list.
     *
     * Sorting and regrouping 165k entries allocates a row per image, which is tens of
     * milliseconds; run inside a state update it all lands on the main thread and the
     * grid visibly stalls after every deletion. The arithmetic is moved off and only
     * the finished list is published.
     */
    private suspend fun publishItems(message: String, removedIds: Set<String>, keepSelection: Set<String>) {
        val snapshot = _state.value
        // Say it straight away. Regrouping a large directory costs about a tenth of a
        // second on a phone, and holding the answer back for that is what made a
        // deletion feel slow even though the file was already gone.
        _state.update {
            it.copy(
                selection = keepSelection,
                selectionBytes = bytesOf(keepSelection),
                message = message,
            )
        }
        val published = withContext(Dispatchers.Default) {
            val remaining = rawItems.filterNot { it.id in removedIds }
            rawItems = remaining
            byId = remaining.associateBy { it.id }
            val visible = searchFiltered(visibleItemsOf(remaining), snapshot)
            val sorted = sort(visible, snapshot.sortMode)
            Published(
                items = sorted,
                displayRows = buildRows(sorted, snapshot.sortMode, snapshot.collapsed),
                totalBytes = visible.sumOf { item -> item.size },
            )
        }
        _state.update {
            it.copy(
                items = published.items,
                displayRows = published.displayRows,
                totalBytes = published.totalBytes,
                busy = false,
            )
        }
    }

    /** What a rebuild of the item list produced, computed away from the main thread. */
    private data class Published(
        val items: List<MediaItem>,
        val displayRows: List<GalleryRow>,
        val totalBytes: Long,
    )

    /**
     * Turns the on-disk index on or off.
     *
     * Turning it off deletes what is already there rather than waiting for a scan to
     * notice: the point of the setting is the space those files take.
     */
    fun setPersistScanIndex(enabled: Boolean) {
        settings.persistScanIndex = enabled
        _state.update { it.copy(persistScanIndex = enabled) }
        if (!enabled) {
            viewModelScope.launch(Dispatchers.IO) { repository.discardIndex() }
        }
    }

    /** Notes the removals in each tree's own index, since a selection can span trees. */
    private suspend fun forgetInIndex(items: List<MediaItem>, removedIds: Set<String>) {
        items.groupBy { it.source }.forEach { (source, entries) ->
            repository.forgetInCache(source, entries.mapTo(HashSet()) { it.id })
        }
    }

    /** Drops both the scan cache and the OCR text of files that are now gone. */
    private suspend fun forgetRemoved(items: List<MediaItem>, removedIds: Set<String>) {
        forgetInIndex(items, removedIds)
        ocrIndex.remove(removedIds)
        val indexed = ocrIndex.count()
        _state.update {
            it.copy(
                ocrIndexed = indexed,
                ocrMatches = it.ocrMatches?.minus(removedIds),
            )
        }
    }

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
