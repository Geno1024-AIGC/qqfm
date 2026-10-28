package com.geno1024.ai.qqfm.data

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class OcrScanStatus { IDLE, RUNNING, PAUSED, DONE }

/**
 * What the full-library OCR pass is doing, as both the service and the UI see it.
 *
 * Elapsed time is kept as accumulated active milliseconds plus the wall clock of the
 * current run, so pausing stops the clock instead of leaving it counting. A process
 * that dies mid-run comes back as [OcrScanStatus.PAUSED]: the cursor survived, the
 * running clock did not, and pretending otherwise would report time that was never
 * spent.
 */
data class OcrScanProgress(
    val status: OcrScanStatus = OcrScanStatus.IDLE,
    val done: Int = 0,
    val total: Int = 0,
    val failures: Int = 0,
    val activeMillis: Long = 0L,
    val runningSince: Long? = null,
    /** True while this pass only reads pictures that have no index entry yet. */
    val backfill: Boolean = false,
) {
    fun elapsedMillis(now: Long = SystemClock.elapsedRealtime()): Long =
        activeMillis + if (status == OcrScanStatus.RUNNING && runningSince != null) {
            (now - runningSince).coerceAtLeast(0L)
        } else {
            0L
        }
}

/**
 * Persisted state of the OCR pass, plus the live flow the UI collects.
 *
 * Everything mutating is synchronized because the service updates from a worker while
 * the activity reads the flow from the main thread. `SharedPreferences.apply` keeps
 * the in-memory copy synchronous, so a crash loses at most the last unsaved batch.
 */
object OcrScanBus {

    private const val PREFS = "ocr.scan"
    private const val KEY_STATUS = "status"
    private const val KEY_DONE = "done"
    private const val KEY_TOTAL = "total"
    private const val KEY_FAILURES = "failures"
    private const val KEY_ACTIVE = "activeMillis"
    private const val KEY_CURSOR = "cursor"
    private const val KEY_SOURCES = "sources"
    private const val KEY_BACKFILL = "backfill"

    private val _state = MutableStateFlow(OcrScanProgress())
    val state: StateFlow<OcrScanProgress> = _state.asStateFlow()

    private var prefs: SharedPreferences? = null
    private var cursorValue = 0
    private var sourcesValue: Set<MediaSource> = emptySet()
    private var sincePersist = 0

    @Synchronized
    fun attach(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = store
        cursorValue = store.getInt(KEY_CURSOR, 0)
        sourcesValue = store.getStringSet(KEY_SOURCES, null)
            ?.mapNotNullTo(LinkedHashSet()) { MediaSource.from(it) }
            ?: emptySet()
        val saved = runCatching {
            OcrScanStatus.valueOf(store.getString(KEY_STATUS, null) ?: OcrScanStatus.IDLE.name)
        }.getOrDefault(OcrScanStatus.IDLE)
        _state.value = OcrScanProgress(
            status = when (saved) {
                OcrScanStatus.RUNNING, OcrScanStatus.PAUSED -> OcrScanStatus.PAUSED
                OcrScanStatus.DONE -> OcrScanStatus.DONE
                OcrScanStatus.IDLE -> OcrScanStatus.IDLE
            },
            done = store.getInt(KEY_DONE, 0),
            total = store.getInt(KEY_TOTAL, 0),
            failures = store.getInt(KEY_FAILURES, 0),
            activeMillis = store.getLong(KEY_ACTIVE, 0L),
            runningSince = null,
            backfill = store.getBoolean(KEY_BACKFILL, false),
        )
    }

    @Synchronized
    fun cursor(): Int = cursorValue

    @Synchronized
    fun setCursor(value: Int) {
        cursorValue = value
    }

    /** A rescan can find a different number of pictures than the interrupted pass. */
    @Synchronized
    fun setTotal(total: Int) {
        if (_state.value.total == total) return
        _state.value = _state.value.copy(total = total)
        persistLocked()
    }

    @Synchronized
    fun sources(): Set<MediaSource> = sourcesValue

    @Synchronized
    fun begin(total: Int, sources: Set<MediaSource>, backfill: Boolean = false) {
        cursorValue = 0
        sourcesValue = sources
        sincePersist = 0
        _state.value = OcrScanProgress(
            status = OcrScanStatus.RUNNING,
            done = 0,
            total = total,
            failures = 0,
            activeMillis = 0L,
            runningSince = SystemClock.elapsedRealtime(),
            backfill = backfill,
        )
        persistLocked()
    }

    /** Keeps the counters of a resumed run, only restarting the clock. */
    @Synchronized
    fun resume() {
        val now = _state.value
        if (now.status == OcrScanStatus.RUNNING) return
        _state.value = now.copy(
            status = OcrScanStatus.RUNNING,
            runningSince = SystemClock.elapsedRealtime(),
        )
        persistLocked()
    }

    @Synchronized
    fun pause() {
        val now = _state.value
        if (now.status != OcrScanStatus.RUNNING) return
        _state.value = now.copy(
            status = OcrScanStatus.PAUSED,
            activeMillis = now.elapsedMillis(),
            runningSince = null,
        )
        persistLocked()
    }

    @Synchronized
    fun advance(done: Int, failures: Int) {
        _state.update { it.copy(done = done, failures = failures) }
        if (++sincePersist >= 16) {
            sincePersist = 0
            persistLocked()
        }
    }

    @Synchronized
    fun finish() {
        val now = _state.value
        _state.value = now.copy(
            status = OcrScanStatus.DONE,
            activeMillis = now.elapsedMillis(),
            runningSince = null,
        )
        persistLocked()
    }

    @Synchronized
    fun reset() {
        cursorValue = 0
        sincePersist = 0
        sourcesValue = emptySet()
        _state.value = OcrScanProgress()
        persistLocked()
    }

    /** Forces the cursor to disk, so a batch boundary survives a process death. */
    @Synchronized
    fun checkpoint() {
        sincePersist = 0
        persistLocked()
    }

    private fun persistLocked() {
        val store = prefs ?: return
        val now = _state.value
        store.edit()
            .putString(KEY_STATUS, now.status.name)
            .putInt(KEY_DONE, now.done)
            .putInt(KEY_TOTAL, now.total)
            .putInt(KEY_FAILURES, now.failures)
            .putLong(KEY_ACTIVE, now.elapsedMillis())
            .putInt(KEY_CURSOR, cursorValue)
            .putStringSet(KEY_SOURCES, sourcesValue.mapTo(HashSet()) { it.id })
            .putBoolean(KEY_BACKFILL, now.backfill)
            .apply()
    }
}
