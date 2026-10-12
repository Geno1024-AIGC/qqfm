package com.geno1024.ai.qqfm.data

import android.content.Context
import java.io.File

/**
 * Scans one of QQ's picture trees through root and keeps a compact on-disk cache
 * so a restart does not have to walk ~165k files again.
 */
class MediaRepository(
    private val context: Context,
    /** Read each time, so turning the setting off takes effect without a restart. */
    private val keepIndex: () -> Boolean = { true },
) {

    data class ScanOutcome(
        val items: List<MediaItem>,
        val totalBytes: Long,
    )

    data class DeleteResult(val filesRemoved: Long, val bytesReclaimed: Long)

    private fun cacheFile(source: MediaSource): File = File(context.cacheDir, "scan-${source.id}.tsv")

    /** Loads the cached index if present; returns null when there is none. */
    fun loadCached(source: MediaSource): List<MediaItem>? {
        if (!keepIndex()) return null
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
            val deleted = deletedIds(source)
            if (deleted.isEmpty()) {
                items
            } else {
                // Deletions are appended rather than written back, so they are only
                // applied here. Once enough of them pile up the index is rewritten
                // without them, which is the one place the slow write belongs.
                val kept = items.filterNot { it.id in deleted }
                if (deleted.size >= COMPACT_THRESHOLD) compact(source, kept)
                kept
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /**
     * Records that [ids] are gone, without rewriting the index.
     *
     * The index is a few megabytes for a large directory, and rewriting all of it to
     * drop one line costs more than the delete itself. Appending the id to a side file
     * costs thirty-odd bytes and is applied the next time the index is read.
     */
    fun forgetInCache(source: MediaSource, ids: Set<String>) {
        if (ids.isEmpty() || !keepIndex()) return
        runCatching {
            val file = removedFile(source)
            file.appendText(ids.joinToString(separator = "\n", postfix = "\n"))
        }
    }

    private fun deletedIds(source: MediaSource): Set<String> = runCatching {
        val file = removedFile(source)
        if (!file.exists()) return emptySet()
        file.readLines().filterTo(HashSet()) { it.isNotBlank() }
    }.getOrDefault(emptySet())

    /** Rewrites the index without the removed entries and drops the side file. */
    private fun compact(source: MediaSource, items: List<MediaItem>) {
        save(source, items)
        runCatching { removedFile(source).delete() }
    }

    private fun removedFile(source: MediaSource): File =
        File(context.cacheDir, "scan-${source.id}.removed")

    /** Throws away the on-disk index for every tree, to give the space back at once. */
    fun discardIndex() {
        MediaSource.entries.forEach { source ->
            runCatching { cacheFile(source).delete() }
            runCatching { removedFile(source).delete() }
            runCatching { File(context.cacheDir, "scan-${source.id}.tsv.tmp").delete() }
        }
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
        if (!keepIndex()) return
        runCatching {
            val tmp = File(context.cacheDir, "scan-${source.id}.tsv.tmp")
            // A fresh scan is the whole truth, so the removals it covers are done with.
            tmp.bufferedWriter().use { writer ->
                items.forEach { item ->
                    writer.append(item.path).append('\t')
                        .append(item.base).append('\t')
                        .append(item.size.toString()).append('\t')
                        .append(item.mtime.toString()).append('\n')
                }
            }
            tmp.renameTo(cacheFile(source))
            runCatching { removedFile(source).delete() }
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
        if (items.isEmpty()) return DeleteResult(0L, 0L)
        // The scanned path is the one file known to exist, so it goes first; the
        // derived variants only add the sibling trees of older QQ layouts.
        val candidates = items.flatMap { item ->
            listOf(item.path) + MediaPaths.variants(item.base)
        }.distinct()
        val batches = candidates.chunked(128)
        val present = HashSet<String>(candidates.size)
        var done = 0
        batches.forEach { batch ->
            val args = batch.joinToString(" ") { RootShell.shellQuote(it) }
            // Existence test and removal share one shell: `su` costs far more than the
            // `rm` does, and a delete that forks twice is a delete that feels slow.
            RootShell.forEachLine(
                "for p in $args; do if [ -f \"\$p\" ]; then printf '%s\\n' \"\$p\"; " +
                    "rm -f -- \"\$p\"; fi; done 2>/dev/null",
            ) { line -> present.add(line) }
            done++
            onProgress(done)
        }
        val reclaimed = items.filter { it.path in present }.sumOf { it.size }
        return DeleteResult(present.size.toLong(), reclaimed)
    }
}

/** How many recorded removals are worth a full rewrite of the index. */
private const val COMPACT_THRESHOLD = 2_000
