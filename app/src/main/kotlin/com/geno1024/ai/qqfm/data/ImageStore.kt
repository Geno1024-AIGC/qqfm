package com.geno1024.ai.qqfm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Loads downsampled bitmaps out of QQ's root-only cache. Reads are serialized by
 * a small semaphore because every read spawns an `su` process.
 */
class ImageStore(private val context: Context) {

    private val semaphore = Semaphore(4)

    private val memory = object : LruCache<String, Bitmap>(memoryCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val diskDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    suspend fun thumbnail(item: MediaItem, targetPx: Int): Bitmap? = load(item, targetPx, "t$targetPx")

    suspend fun full(item: MediaItem, targetPx: Int): Bitmap? = load(item, targetPx, "f$targetPx")

    private suspend fun load(item: MediaItem, targetPx: Int, bucket: String): Bitmap? {
        val key = "${item.id}@$bucket"
        memory.get(key)?.let { return it }

        val disk = File(diskDir, "$key.jpg")
        if (disk.isFile && disk.length() > 0L) {
            decodeFile(disk, targetPx)?.let {
                memory.put(key, it)
                return it
            }
        }

        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                val bytes = readCandidates(item) ?: return@withPermit null
                val bitmap = decodeSampled(bytes, targetPx) ?: return@withPermit null
                runCatching {
                    FileOutputStream(disk).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out)
                    }
                }
                memory.put(key, bitmap)
                bitmap
            }
        }
    }

    /** Thumbnail first, then the full file, so a missing thumb still renders. */
    private fun readCandidates(item: MediaItem): ByteArray? {
        val candidates = listOf(
            item.thumbnailPath,
            "${MediaPaths.THUMB_DIR}/Cache_${item.base}",
            item.path,
        )
        for (path in candidates) {
            val bytes = readAll(path)
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    private fun readAll(path: String): ByteArray? = runCatching {
        RootShell.openRead(path).use { holder -> holder.stream.readBytes() }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    private fun decodeSampled(bytes: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        }.getOrNull()
    }

    private fun decodeFile(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun memoryCacheSize(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
