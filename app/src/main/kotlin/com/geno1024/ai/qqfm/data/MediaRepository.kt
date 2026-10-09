package com.geno1024.ai.qqfm.data

import android.content.Context
import java.io.File

/**
 * Scans [MediaPaths.IMG_DIR] through root and keeps a compact on-disk cache so a
 * restart does not have to walk ~165k files again.
 */
class MediaRepository(private val context: Context) {

    data class ScanOutcome(
        val items: List<MediaItem>,
        val totalBytes: Long,
    )

    data class DeleteResult(val filesRemoved: Int, val bytesReclaimed: Long)

    private val cacheFile: File get() = File(context.cacheDir, "scan.tsv")

    /** Loads the cached index if present; returns null when there is none. */
    fun loadCached(): List<MediaItem>? {
        val file = cacheFile
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val items = ArrayList<MediaItem>(200_000)
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 3) {
                    val base = parts[0]
                    val size = parts[1].toLongOrNull() ?: return@forEachLine
                    val mtime = parts[2].toLongOrNull() ?: return@forEachLine
                    val name = "Cache_$base"
                    items.add(MediaItem("${MediaPaths.IMG_DIR}/$name", base, name, size, mtime))
                }
            }
            items
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Walks `chatimg` with `find` + `stat`. Missing and unreadable entries are
     * skipped rather than aborting the scan.
     */
    fun scan(onProgress: (Int) -> Unit = {}): ScanOutcome {
        val items = ArrayList<MediaItem>(200_000)
        val counted = intArrayOf(0)
        val lastReport = longArrayOf(0L)
        val command = "find ${RootShell.shellQuote(MediaPaths.IMG_DIR)}" +
            " -type f -print0 2>/dev/null" +
            " | xargs -0 -r stat -c '%n\t%s\t%Y' 2>/dev/null"
        RootShell.forEachLine(command) { line ->
            val firstTab = line.indexOf('\t')
            val lastTab = line.lastIndexOf('\t')
            if (firstTab <= 0 || lastTab <= firstTab) return@forEachLine
            val path = line.substring(0, firstTab)
            val size = line.substring(firstTab + 1, lastTab).toLongOrNull() ?: return@forEachLine
            val mtime = line.substring(lastTab + 1).toLongOrNull() ?: return@forEachLine
            val name = path.substringAfterLast('/')
            if (!name.startsWith("Cache_")) return@forEachLine
            val base = name.removePrefix("Cache_")
            items.add(MediaItem(path, base, name, size, mtime))
            counted[0]++
            val now = System.currentTimeMillis()
            if (now - lastReport[0] > 250) {
                lastReport[0] = now
                onProgress(counted[0])
            }
        }
        save(items)
        return ScanOutcome(items, items.sumOf { it.size })
    }

    /** Rewrites the on-disk index from an in-memory list. */
    fun save(items: List<MediaItem>) {
        runCatching {
            val tmp = File(context.cacheDir, "scan.tsv.tmp")
            tmp.bufferedWriter().use { writer ->
                items.forEach { item ->
                    writer.append(item.base).append('\t')
                        .append(item.size.toString()).append('\t')
                        .append(item.mtime.toString()).append('\n')
                }
            }
            tmp.renameTo(cacheFile)
        }
    }

    /**
     * Deletes every on-disk variant of the given bases. Callers pass the gallery
     * items; each one expands to `chatimg`/`chatraw`/`chatthumb` files.
     */
    fun delete(items: List<MediaItem>, onProgress: (Int) -> Unit = {}): DeleteResult {
        if (items.isEmpty()) return DeleteResult(0, 0L)
        val allPaths = items.flatMap { MediaPaths.variants(it.base) }
        val batches = allPaths.chunked(128)
        var removed = 0
        batches.forEachIndexed { index, batch ->
            val args = batch.joinToString(" ") { RootShell.shellQuote(it) }
            if (RootShell.exec("rm -f -- $args").ok) removed += batch.size
            onProgress(index + 1)
        }
        return DeleteResult(removed, items.sumOf { it.size })
    }
}
