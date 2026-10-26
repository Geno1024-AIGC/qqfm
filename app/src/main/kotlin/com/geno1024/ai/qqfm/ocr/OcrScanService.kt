package com.geno1024.ai.qqfm.ocr

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.geno1024.ai.qqfm.MainActivity
import com.geno1024.ai.qqfm.R
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * Reads text out of every picture in the chosen trees, in the background.
 *
 * A plain coroutine in the activity would die the moment the screen is left, and the
 * whole point of a pass over hundreds of thousands of pictures is that it outlives
 * the screen. The service is therefore a foreground one, with a progress notification
 * carrying pause/resume and stop, and a cursor in shared preferences so a killed
 * process resumes where it left off instead of starting over.
 */
class OcrScanService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var imageStore: ImageStore
    private lateinit var index: OcrIndex
    private var engine: OcrEngine? = null
    private var job: Job? = null

    @Volatile
    private var paused = false

    @Volatile
    private var stopped = false

    private var lastNotifyAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var failureLogs = 0

    override fun onCreate() {
        super.onCreate()
        OcrScanBus.attach(this)
        imageStore = ImageStore(this)
        index = OcrIndex(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        OcrScanBus.attach(this)
        when (intent?.action) {
            ACTION_PAUSE -> pauseTask()
            ACTION_RESUME -> resumeTask()
            ACTION_STOP -> stopTask()
            ACTION_RESTART -> restartTask()
            ACTION_BACKFILL -> backfillTask()
            else -> startTask()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Android 15+ gives a dataSync foreground service a bounded number of hours.
     * When that runs out the service has to go quietly, or the system kills the
     * process; the cursor is already on disk, so the next start picks up the rest.
     */
    override fun onTimeout(startId: Int) {
        interruptForTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        interruptForTimeout()
    }

    private fun interruptForTimeout() {
        paused = true
        OcrScanBus.pause()
        job?.cancel()
        releaseWakeLock()
        stopForegroundNow(remove = true)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        if (OcrScanBus.state.value.status == OcrScanStatus.RUNNING) OcrScanBus.pause()
        job?.cancel()
        scope.cancel()
        releaseWakeLock()
        engine?.close()
        super.onDestroy()
    }

    // ---- intents from the UI and from the notification ----

    private fun startTask() {
        startForegroundWith()
        if (job?.isActive == true) return
        val resuming = OcrScanBus.state.value.status == OcrScanStatus.PAUSED
        paused = false
        stopped = false
        acquireWakeLock()
        job = scope.launch { runScan(resuming, onlyMissing = false) }
    }

    private fun resumeTask() {
        startForegroundWith()
        if (job?.isActive == true) {
            paused = false
            acquireWakeLock()
            OcrScanBus.resume()
            notifyProgress(force = true)
            return
        }
        paused = false
        stopped = false
        acquireWakeLock()
        val onlyMissing = OcrScanBus.state.value.backfill
        job = scope.launch { runScan(resuming = true, onlyMissing) }
    }

    private fun pauseTask() {
        paused = true
        OcrScanBus.pause()
        releaseWakeLock()
        notifyProgress(force = true)
    }

    /**
     * Throws the saved cursor away and starts over.
     *
     * The old job is joined first: both would otherwise share the same batch
     * directory, and one would delete the copies the other was still decoding.
     */
    private fun restartTask() {
        startForegroundWith()
        val previous = job
        previous?.cancel()
        paused = false
        stopped = false
        OcrScanBus.reset()
        acquireWakeLock()
        job = scope.launch {
            previous?.join()
            runScan(resuming = false, onlyMissing = false)
        }
    }

    /**
     * Reads every picture that has no text index yet, which is how a pass that
     * failed on most files gets retried after the cause is fixed. Pictures already
     * indexed are filtered out up front, so the progress total is the work left.
     */
    private fun backfillTask() {
        startForegroundWith()
        val previous = job
        previous?.cancel()
        paused = false
        stopped = false
        OcrScanBus.reset()
        acquireWakeLock()
        job = scope.launch {
            previous?.join()
            runScan(resuming = false, onlyMissing = true)
        }
    }

    private fun stopTask() {
        stopped = true
        paused = false
        OcrScanBus.pause()
        job?.cancel()
        releaseWakeLock()
        stopForegroundNow(remove = true)
        stopSelf()
    }

    // ---- the pass itself ----

    private suspend fun runScan(resuming: Boolean, onlyMissing: Boolean) {
        try {
            runScanBody(resuming, onlyMissing)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            if (!stopped) {
                OcrScanBus.pause()
                releaseWakeLock()
                notifyProgress(force = true)
            }
        }
    }

    private suspend fun runScanBody(resuming: Boolean, onlyMissing: Boolean) {
        val settings = AppSettings.of(application)
        val sources = (
            OcrScanBus.sources()
                .ifEmpty { settings.selectedSources }
                .ifEmpty { MediaSource.entries.toSet() }
            )
        // Enter the running state before walking the trees. Enumeration is root work
        // that can take a minute, and without this the notification would offer no
        // way to pause or stop until the first picture was read.
        // A backfill list is rebuilt from the index each time, so its cursor would
        // point into a different list after a pause; always start it from zero.
        val resumingRun = resuming && !onlyMissing && OcrScanBus.state.value.total > 0
        if (resumingRun) OcrScanBus.resume() else OcrScanBus.begin(0, sources, onlyMissing)
        notifyProgress(force = true, scanning = true)

        val repository = MediaRepository(application) { settings.persistScanIndex }
        val all = ArrayList<MediaItem>()
        for (source in sources) {
            if (stopped) return
            awaitResume()
            val cached = repository.loadCached(source)
            val items = cached ?: repository.scan(source).items
            all += items
            notifyProgress(force = true, scanning = true)
        }
        awaitResume()
        if (stopped) return

        if (onlyMissing) {
            val indexed = runCatching { index.ids() }.getOrDefault(emptySet())
            all.retainAll { it.id !in indexed }
            notifyProgress(force = true, scanning = true)
        }

        // Newest first: search becomes useful immediately instead of after the whole
        // backlog, which is the difference between a feature and a chore.
        all.sortWith(compareByDescending<MediaItem> { it.mtime }.thenBy { it.base })

        val total = all.size
        OcrScanBus.setTotal(total)
        if (total == 0) {
            OcrScanBus.finish()
            notifyProgress(force = true)
            stopForegroundNow(remove = false)
            stopSelf()
            return
        }

        var done = if (resumingRun) OcrScanBus.state.value.done.coerceAtMost(total) else 0
        var failures = if (resumingRun) OcrScanBus.state.value.failures else 0
        var cursor = if (resumingRun) OcrScanBus.cursor().coerceIn(0, total) else 0

        val batchDir = File(cacheDir, "ocr-batch").apply { mkdirs() }
        while (cursor < total) {
            currentCoroutineContext().ensureActive()
            if (stopped) break
            awaitResume()
            if (stopped) break

            val end = minOf(cursor + BATCH, total)
            val batch = all.subList(cursor, end)
            val local = copyBatch(batch, batchDir)

            var advanced = 0
            for ((offset, item) in batch.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (stopped) break
                awaitResume()
                if (stopped) break

                if (!runCatching { index.contains(item.id) }.getOrDefault(false)) {
                    val bitmap = decode(local[offset])
                    if (bitmap != null) {
                        val attempt = runCatching { engine().recognize(bitmap) }
                        val text = attempt.getOrNull()
                        if (text == null) logFailure("recognize", item, attempt.exceptionOrNull())
                        val stored = text != null && runCatching { index.put(item.id, text) }.isSuccess
                        if (!stored) {
                            failures++
                            if (text != null) logFailure("store", item, null)
                        }
                        bitmap.recycle()
                    } else {
                        failures++
                        logFailure("decode", item, null)
                    }
                }
                done++
                advanced++
                OcrScanBus.setCursor(cursor + offset + 1)
                // The flow feeds Compose; publishing every picture would recompose the
                // about page hundreds of thousands of times for no visible gain.
                if (offset and 7 == 7 || offset == batch.lastIndex) {
                    OcrScanBus.advance(done, failures)
                }
                notifyProgress(force = false)
            }

            cursor += advanced
            local.forEach { runCatching { it.delete() } }
            OcrScanBus.setCursor(cursor)
            OcrScanBus.advance(done, failures)
            OcrScanBus.checkpoint()
            if (stopped) break
        }

        localCleanup(batchDir)
        if (stopped) return
        if (cursor >= total) {
            OcrScanBus.finish()
            notifyProgress(force = true)
            stopForegroundNow(remove = false)
            stopSelf()
        }
    }

    private suspend fun awaitResume() {
        while (paused && !stopped) {
            delay(200)
        }
    }

    /**
     * Copies one batch out of the root-only cache in a single `su` invocation.
     *
     * One `su` per picture would cost more than the recognition itself, so the shell
     * does the whole batch, and the app decodes the plain files it now owns.
     */
    private fun copyBatch(items: List<MediaItem>, dir: File): List<File> {
        dir.deleteRecursively()
        dir.mkdirs()
        // Create the destinations as the app first. `cp` truncates an existing file
        // instead of replacing it, so the copies stay owned by the app; the explicit
        // chmod covers the implementations that unlink and recreate. Without this the
        // files are root-owned with QQ's 0600 mode and BitmapFactory sees nothing,
        // which is what made almost every picture count as a read failure.
        val targets = items.indices.map { index ->
            File(dir, index.toString()).apply { runCatching { createNewFile() } }
        }
        val command = buildString {
            for (i in items.indices) {
                val target = targets[i].absolutePath
                // `cat > existing` truncates in place. `cp -f` is allowed to unlink
                // and recreate on some implementations, which would hand the file
                // back to root along with its SELinux label.
                append("cat -- ")
                append(RootShell.shellQuote(items[i].path))
                append(" > ")
                append(RootShell.shellQuote(target))
                append(" 2>/dev/null && chmod 0644 ")
                append(RootShell.shellQuote(target))
                append(" 2>/dev/null; ")
            }
        }
        runCatching { RootShell.exec(command) }
        return targets
    }

    /** The first few failures are worth a log line; a million of them are not. */
    private fun logFailure(kind: String, item: MediaItem, error: Throwable?) {
        if (failureLogs >= 8) return
        failureLogs++
        Log.w(TAG, "OCR $kind failed: ${item.path}", error)
    }

    private fun decode(file: File): Bitmap? = runCatching {
        if (!file.isFile || file.length() == 0L) null else imageStore.decodeLocalForOcr(file)
    }.getOrNull()

    private fun engine(): OcrEngine = engine ?: OcrEngine().also { engine = it }

    /**
     * Recognition needs the CPU, and a full pass runs for hours with the screen off.
     * Held only while the pass is actually running, and released on pause/stop, so a
     * paused job costs no more than a notification.
     */
    @Suppress("WakelockTimeout")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "qqfm:ocr-scan")
            .apply {
                setReferenceCounted(false)
                acquire()
            }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    private fun localCleanup(dir: File) {
        runCatching { dir.deleteRecursively() }
    }

    // ---- notification ----

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "文字识别",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "全库图片的文字识别进度"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundWith() {
        val notification = buildNotification(scanning = OcrScanBus.state.value.total == 0)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundNow(remove: Boolean) {
        ServiceCompat.stopForeground(
            this,
            if (remove) ServiceCompat.STOP_FOREGROUND_REMOVE else ServiceCompat.STOP_FOREGROUND_DETACH,
        )
    }

    private fun notifyProgress(force: Boolean, scanning: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < NOTIFY_INTERVAL_MS) return
        lastNotifyAt = now
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(scanning))
    }

    private fun buildNotification(scanning: Boolean = false): Notification {
        val progress = OcrScanBus.state.value
        val elapsed = formatDuration(progress.elapsedMillis())
        val text = when {
            scanning && progress.backfill -> "正在查找未识别的图片…"
            scanning -> "正在读取图片索引…"
            progress.status == OcrScanStatus.PAUSED ->
                (if (progress.backfill) "补齐已暂停" else "已暂停") +
                    " · ${progress.done} / ${progress.total} · 用时 $elapsed"
            progress.status == OcrScanStatus.DONE ->
                (if (progress.backfill) "补齐完成" else "识别完成") +
                    " · ${progress.done} 张 · 用时 $elapsed"
            progress.backfill ->
                "补齐中 ${progress.done} / ${progress.total} · 用时 $elapsed"
            else ->
                "已处理 ${progress.done} / ${progress.total} · 用时 $elapsed"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ocr)
            .setContentTitle("文字识别")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent())

        if (progress.total > 0) {
            builder.setProgress(progress.total, progress.done, scanning)
        } else {
            builder.setProgress(0, 0, true)
        }

        when (progress.status) {
            OcrScanStatus.RUNNING -> {
                builder.setOngoing(true)
                builder.addAction(0, "暂停", serviceIntent(ACTION_PAUSE, 1))
                builder.addAction(0, "停止", serviceIntent(ACTION_STOP, 2))
            }
            OcrScanStatus.PAUSED -> {
                builder.setOngoing(true)
                builder.addAction(0, "继续", serviceIntent(ACTION_RESUME, 3))
                builder.addAction(0, "停止", serviceIntent(ACTION_STOP, 4))
            }
            OcrScanStatus.DONE -> {
                builder.setAutoCancel(true)
                builder.setOngoing(false)
            }
            OcrScanStatus.IDLE -> {
                builder.setOngoing(true)
            }
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, intent, PENDING_FLAGS)
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, OcrScanService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PENDING_FLAGS)
    }

    companion object {
        private const val CHANNEL_ID = "ocr-scan"
        private const val NOTIFICATION_ID = 0x0C2
        private const val BATCH = 48
        private const val NOTIFY_INTERVAL_MS = 500L
        private const val PENDING_FLAGS =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        private const val ACTION_START = "com.geno1024.ai.qqfm.ocr.START"
        private const val ACTION_PAUSE = "com.geno1024.ai.qqfm.ocr.PAUSE"
        private const val ACTION_RESUME = "com.geno1024.ai.qqfm.ocr.RESUME"
        private const val ACTION_STOP = "com.geno1024.ai.qqfm.ocr.STOP"
        private const val ACTION_RESTART = "com.geno1024.ai.qqfm.ocr.RESTART"
        private const val ACTION_BACKFILL = "com.geno1024.ai.qqfm.ocr.BACKFILL"
        private const val TAG = "OcrScan"

        /** Starts, or resumes a paused pass, from wherever the cursor is. */
        fun start(context: Context) {
            val action = if (OcrScanBus.state.value.status == OcrScanStatus.PAUSED) {
                ACTION_RESUME
            } else {
                ACTION_START
            }
            startForegroundService(context, action)
        }

        /** Discards the cursor and starts a new pass from the newest picture. */
        fun restart(context: Context) {
            startForegroundService(context, ACTION_RESTART)
        }

        /** Reads only pictures that have no OCR text yet, including earlier failures. */
        fun backfill(context: Context) {
            startForegroundService(context, ACTION_BACKFILL)
        }

        fun pause(context: Context) {
            if (OcrScanBus.state.value.status != OcrScanStatus.RUNNING) return
            context.startService(Intent(context, OcrScanService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OcrScanService::class.java))
            if (OcrScanBus.state.value.status == OcrScanStatus.RUNNING) OcrScanBus.pause()
        }

        private fun startForegroundService(context: Context, action: String) {
            val intent = Intent(context, OcrScanService::class.java).setAction(action)
            context.startForegroundService(intent)
        }
    }
}

/** `1:02:03` for long passes, `12:34` for the common case. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
