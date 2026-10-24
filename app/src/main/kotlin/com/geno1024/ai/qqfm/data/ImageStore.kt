package com.geno1024.ai.qqfm.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.sqrt
import java.nio.ByteBuffer
import kotlin.math.max

/** What the viewer draws: a still bitmap, or something that moves on its own. */
sealed interface LoadedImage {
    data class Still(val bitmap: Bitmap) : LoadedImage
    data class Motion(val drawable: Drawable) : LoadedImage
}

/**
 * Loads downsampled bitmaps out of QQ's root-only cache. Reads are serialized by
 * a small semaphore because every read spawns an `su` process.
 */
class ImageStore(private val context: Context) {

    private val memory = object : LruCache<String, Bitmap>(memoryCacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val semaphore = Semaphore(4)

    private val diskDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    /**
     * Raw bytes of animated images, which the bitmap cache cannot hold: caching one
     * means flattening it to a JPEG, and a flattened JPEG is one frame forever.
     */
    private val rawBytes = object : LruCache<String, ByteArray>(RAW_CACHE_BYTES.toInt()) {
        override fun sizeOf(key: String, value: ByteArray): Int = value.size
    }

    suspend fun thumbnail(item: MediaItem, targetPx: Int): Bitmap? = load(item, targetPx, "t$targetPx")

    /**
     * A still bitmap sized for OCR.
     *
     * Recognition does not need the full picture; 1600 px on the long edge keeps
     * small print legible while cutting the decode and the model's work roughly in
     * half compared with the viewer's size.
     */
    suspend fun ocrBitmap(item: MediaItem, targetPx: Int = 1600): Bitmap? =
        load(item, targetPx, "o$targetPx")

    /**
     * Decodes a file the scanner already copied into app-private storage.
     *
     * The full-library pass batches files out of the root-only cache with one `cp`
     * per batch; decoding those local copies avoids a `su` per picture.
     */
    fun decodeLocalForOcr(file: File, targetPx: Int = 1280): Bitmap? =
        decodeFile(file, targetPx)

    /**
     * Decodes an image for the viewer, keeping motion when there is any.
     *
     * A still goes through the shared bitmap path, which downsamples and caches. An
     * animation cannot: the cache holds flattened JPEGs, and a flattened JPEG is a
     * single frame forever, so those are decoded fresh and left to animate in place.
     */
    suspend fun viewer(item: MediaItem, maxPx: Int): LoadedImage? {
        if (item.size <= MAX_ANIMATION_BYTES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val bytes = readViewerBytes(item)
            if (bytes != null && looksAnimated(bytes)) {
                decodeAnimated(bytes, maxPx)?.let { return LoadedImage.Motion(it) }
            }
        }
        return load(item, maxPx, "f$maxPx")?.let { LoadedImage.Still(it) }
    }

    /** Reads the file itself first; the viewer wants this image, not its thumbnail. */
    private suspend fun readViewerBytes(item: MediaItem): ByteArray? {
        rawBytes.get(item.id)?.let { return it }
        return withContext(Dispatchers.IO) {
            semaphore.withPermit {
                val bytes = readAll(item.path) ?: readAll(item.thumbnailPath)
                if (bytes != null) rawBytes.put(item.id, bytes)
                bytes
            }
        }
    }

    /** True for GIF, animated WebP and APNG; the formats QQ actually receives. */
    private fun looksAnimated(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        if (startsWith(bytes, 0, "GIF8")) return true
        // A WebP animation advertises itself with the extended "VP8X" chunk.
        if (startsWith(bytes, 0, "RIFF") && startsWith(bytes, 8, "WEBP")) {
            return contains(bytes, "VP8X")
        }
        // A PNG only moves if it carries an animation control chunk.
        return startsWith(bytes, 1, "PNG") && contains(bytes, "acTL")
    }

    private fun startsWith(bytes: ByteArray, offset: Int, text: String): Boolean {
        if (offset + text.length > bytes.size) return false
        return text.indices.all { bytes[offset + it].toInt() and 0xFF == text[it].code }
    }

    private fun contains(bytes: ByteArray, text: String): Boolean {
        val limit = minOf(bytes.size, HEADER_SCAN_LIMIT)
        outer@ for (index in 0..limit - text.length) {
            for (offset in text.indices) {
                if (bytes[index + offset].toInt() and 0xFF != text[offset].code) continue@outer
            }
            return true
        }
        return false
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun decodeAnimated(bytes: ByteArray, maxPx: Int): Drawable? = runCatching {
        // The ByteArray overload only exists from API 31; wrapping the same bytes
        // in a ByteBuffer decodes identically from API 28 on.
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeDrawable(source) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val longest = max(width, height)
            if (longest > maxPx) {
                // Tall chat screenshots run to tens of thousands of pixels; every
                // frame at full height would exhaust the heap before the first draw.
                val ratio = maxPx.toFloat() / longest
                decoder.setTargetSize(
                    (width * ratio).toInt().coerceAtLeast(1),
                    (height * ratio).toInt().coerceAtLeast(1),
                )
            }
            // Animated frames have to be held in ordinary memory; the default
            // allocator hands back a hardware image, which cannot be replayed.
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_LOW_RAM
        }
    }.getOrNull()

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
            MediaPaths.pathIn(MediaPaths.THUMB_DIR, "Cache_${item.base}"),
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
        }.getOrNull()?.let(::withinDrawableSize)
    }

    private fun decodeFile(file: File, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }
            .getOrNull()
            ?.let(::withinDrawableSize)
    }

    /**
     * How much to shrink by, counted in pixels rather than in sides.
     *
     * The old rule only halved while both sides stayed above the target, which meant
     * a screenshot 1080 wide never shrank at all however tall it was: QQ's long
     * images are thousands of pixels high and decoding one at full size asks for
     * more memory than any device has, and the canvas refuses to draw it anyway.
     */
    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        val budget = target.toLong() * target * PIXEL_BUDGET_FACTOR
        var sample = 1
        while ((width.toLong() / sample) * (height.toLong() / sample) > budget) sample *= 2
        return sample
    }

    /**
     * Last line of defence against the canvas's size limit.
     *
     * Sampling only ever halves, so a picture just over a limit can still land over
     * it; this scales down whatever is left. A long screenshot ends up small, but it
     * ends up on screen instead of taking the process with it.
     */
    private fun withinDrawableSize(bitmap: Bitmap): Bitmap? {
        var current = bitmap
        while (current.byteCount > MAX_BITMAP_BYTES ||
            current.width > MAX_BITMAP_SIDE ||
            current.height > MAX_BITMAP_SIDE
        ) {
            val bySide = minOf(
                MAX_BITMAP_SIDE.toFloat() / current.width,
                MAX_BITMAP_SIDE.toFloat() / current.height,
            )
            val byBytes = sqrt(MAX_BITMAP_BYTES.toDouble() / current.byteCount).toFloat()
            val ratio = minOf(bySide, byBytes).coerceAtMost(1f)
            if (ratio >= 1f) return current
            val width = (current.width * ratio).toInt().coerceAtLeast(1)
            val height = (current.height * ratio).toInt().coerceAtLeast(1)
            val scaled = runCatching { Bitmap.createScaledBitmap(current, width, height, true) }
                .getOrNull() ?: return current
            if (scaled !== current && !current.isRecycled) current.recycle()
            current = scaled
        }
        return current
    }

    private fun memoryCacheSize(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

/** Magic-number checks only ever look at the head of a file. */
private const val HEADER_SCAN_LIMIT = 256

/**
 * Above this an animation is treated as still. Frames are held decoded and in
 * memory at once, so a very large one can take the process down with it.
 */
private const val MAX_ANIMATION_BYTES = 24L * 1024 * 1024

/** How much raw animation is kept around, in bytes. */
private const val RAW_CACHE_BYTES = 16L * 1024 * 1024

/** How much a decode may exceed its target before it is halved again, in pixels². */
private const val PIXEL_BUDGET_FACTOR = 2L

/** What the canvas refuses to draw: roughly a texture's worth of memory and side. */
private const val MAX_BITMAP_BYTES = 32L * 1024 * 1024
private const val MAX_BITMAP_SIDE = 8192
