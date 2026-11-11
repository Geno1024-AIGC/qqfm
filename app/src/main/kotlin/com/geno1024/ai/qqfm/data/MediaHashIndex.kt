package com.geno1024.ai.qqfm.data

import android.content.Context
import java.io.File

/**
 * Content hashes for one media tree, so a picture can be matched to the message
 * it came from.
 *
 * The trees under `chatpic` are named after a random 64-bit id that QQ makes when
 * it downloads a picture, so a file name says nothing about which message it
 * belongs to. What does travel is the bytes: `chatraw` holds the picture exactly
 * as it was sent, and the message carries that same md5. Hashing the tree once
 * therefore turns "file name" into "message md5".
 *
 * The result is cached next to the app and only recomputed for files whose size
 * or mtime changed — a full pass reads 17 GB, which is not something to repeat on
 * every launch.
 */
class MediaHashIndex(private val context: Context) {

    /** `base` of a file name → md5 of its bytes. */
    fun load(source: MediaSource): MutableMap<String, Entry> {
        val out = HashMap<String, Entry>(200_000)
        cacheFile(source).takeIf { it.exists() }?.forEachLine { line ->
            val f = line.split('\t')
            if (f.size == 4) {
                val size = f[1].toLongOrNull() ?: return@forEachLine
                val mtime = f[2].toLongOrNull() ?: return@forEachLine
                if (f[3].length == 32) out[f[0]] = Entry(size, mtime, f[3])
            }
        }
        return out
    }

    fun save(source: MediaSource, entries: Map<String, Entry>) {
        runCatching {
            cacheFile(source).bufferedWriter().use { writer ->
                entries.forEach { (base, entry) ->
                    writer.append(base).append('\t')
                        .append(entry.size.toString()).append('\t')
                        .append(entry.mtime.toString()).append('\t')
                        .append(entry.md5).append('\n')
                }
            }
        }
    }

    /**
     * Hashes every file of [source] that is new or changed.
     *
     * One `md5sum` per batch rather than one `su` per file: a root round trip costs
     * more than the hashing does at these sizes. The stale entries stay in [cache],
     * and the caller saves it.
     */
    fun refresh(
        source: MediaSource,
        items: List<MediaItem>,
        cache: MutableMap<String, Entry>,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val todo = items.filter { item ->
            val known = cache[item.base]
            known == null || known.size != item.size || known.mtime != item.mtime
        }
        if (todo.isEmpty()) return
        onProgress(0, todo.size)

        // NUL-separated so a file name can never split a record; the list is
        // written once and read by one `xargs` inside a single root shell.
        val list = File(context.cacheDir, "hash-${source.id}.list")
        list.outputStream().buffered().use { out ->
            todo.forEach { item ->
                out.write(item.path.toByteArray(Charsets.UTF_8))
                out.write(0)
            }
        }

        val pending = HashMap<String, MediaItem>(todo.size * 2)
        todo.forEach { pending[it.path] = it }
        var done = 0
        RootShell.forEachLine(
            "xargs -0 -r md5sum < ${RootShell.shellQuote(list.absolutePath)} 2>/dev/null",
        ) { line ->
            val split = line.indexOf(' ')
            if (split != 32) return@forEachLine
            val md5 = line.substring(0, 32).lowercase()
            // md5sum prefixes a backslash for names it had to escape.
            var path = line.substring(split + 1).trimStart()
            if (path.startsWith("\\")) path = path.substring(1)
            val item = pending[path] ?: return@forEachLine
            cache[item.base] = Entry(item.size, item.mtime, md5)
            done++
            if (done % 512 == 0) onProgress(done, todo.size)
        }
        runCatching { list.delete() }
        onProgress(done, todo.size)
    }

    private fun cacheFile(source: MediaSource): File =
        File(context.filesDir, "media-hash-${source.id}.tsv")

    data class Entry(val size: Long, val mtime: Long, val md5: String)
}
