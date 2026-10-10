package com.geno1024.ai.qqfm.data

import android.content.Context
import java.io.File

/**
 * Scans one of QQ's picture trees through root and keeps a compact on-disk cache
 * so a restart does not have to walk ~165k files again.
 */
class MediaRepository(private val context: Context) {

    data class ScanOutcome(
        val items: List<MediaItem>,
        val totalBytes: Long,
    )

    data class DeleteResult(val filesRemoved: Int, val bytesReclaimed: Long)

    private fun cacheFile(source: MediaSource): File = File(context.cacheDir, "scan-${source.id}.tsv")

    /** Loads the cached index if present; returns null when there is none. */
    fun loadCached(source: MediaSource): List<MediaItem>? {
        val file = cacheFile(source)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val items = ArrayList<MediaItem>(200_000)
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size == 4) {
                    val path = parts[0]
                    val base = parts[1]
                    val size = parts[2].toLongOrNull() ?: return@forEachLine
                    val mtime = parts[3].toLongOrNull() ?: return@forEachLine
                    items.add(MediaItem(source, path, base, path.substringAfterLast('/'), size, mtime))
                }
            }
            items
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Walks [source] with `find` + `stat`. Missing and unreadable entries are
     * skipped rather than aborting the scan.
     */
    fun scan(source: MediaSource, onProgress: (Int) -> Unit = {}): ScanOutcome {
        val items = ArrayList<MediaItem>(200_000)
        val counted = intArrayOf(0)
        val lastReport = longArrayOf(0L)
        val command = "find ${RootShell.shellQuote(source.dir)}" +
            " -type f -print0 2>/dev/null" +
            " | xargs -0 -r stat -c '%n\t%s\t%Y' 2>/dev/null"
        RootShell.forEachLine(command) { line ->
            val firstTab = line.indexOf('\t')
            val lastTab = line.lastIndexOf('\t')
            if (firstTab <= 0 || lastTab <= firstTab) return@forEachLine
            val path = line.substring(0, firstTab)
            val size = line.substring(firstTab + 1, lastTab).toLongOrNull() ?: return@forEachLine
            // stat reports seconds; the rest of the app thinks in milliseconds.
            val mtime = (line.substring(lastTab + 1).toLongOrNull() ?: return@forEachLine) * 1000L
            val name = path.substringAfterLast('/')
            if (!name.startsWith("Cache_")) return@forEachLine
            val base = name.removePrefix("Cache_")
            items.add(MediaItem(source, path, base, name, size, mtime))
            counted[0]++
            val now = System.currentTimeMillis()
            if (now - lastReport[0] > 250) {
                lastReport[0] = now
                onProgress(counted[0])
            }
        }
        save(source, items)
        return ScanOutcome(items, items.sumOf { it.size })
    }

    /** Rewrites the on-disk index from an in-memory list. */
    fun save(source: MediaSource, items: List<MediaItem>) {
        runCatching {
            val tmp = File(context.cacheDir, "scan-${source.id}.tsv.tmp")
            tmp.bufferedWriter().use { writer ->
                items.forEach { item ->
                    writer.append(item.path).append('\t')
                        .append(item.base).append('\t')
                        .append(item.size.toString()).append('\t')
                        .append(item.mtime.toString()).append('\n')
                }
            }
            tmp.renameTo(cacheFile(source))
        }
    }

    /**
     * Deletes every on-disk variant of the given bases. Callers pass the gallery
     * items; each one expands to `chatimg`/`chatraw`/`chatthumb` files.
     *
     * Only the files that are actually there are counted: NTQQ names a file after
     * `crc64("<tree>:<md5>")`, so three of the four variants normally miss, and
     * reporting the candidate count would tell the user four files went away when
     * one did.
     */
    fun delete(items: List<MediaItem>, onProgress: (Int) -> Unit = {}): DeleteResult {
        if (items.isEmpty()) return DeleteResult(0, 0L)
        val candidates = items.flatMap { MediaPaths.variants(it.base) }
        val batches = candidates.chunked(128)
        val present = HashSet<String>(candidates.size)
        var done = 0
        batches.forEach { batch ->
            val args = batch.joinToString(" ") { RootShell.shellQuote(it) }
            RootShell.forEachLine(
                "for p in $args; do [ -f \"\$p\" ] && printf '%s\\n' \"\$p\"; done 2>/dev/null",
            ) { line -> present.add(line) }
            done++
            onProgress(done)
        }
        if (present.isEmpty()) return DeleteResult(0, 0L)

        present.chunked(128).forEach { batch ->
            val args = batch.joinToString(" ") { RootShell.shellQuote(it) }
            RootShell.exec("rm -f -- $args")
        }
        val reclaimed = items.filter { it.path in present }.sumOf { it.size }
        return DeleteResult(present.size, reclaimed)
    }
}
