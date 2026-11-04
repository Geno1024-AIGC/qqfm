package com.geno1024.ai.qqfm.data

/**
 * One entry of QQ's media cache.
 *
 * The user browses one tree at a time: NTQQ names a picture after
 * `crc64("<tree>:<md5>")`, so the same picture carries a different id in every
 * tree and the trees cannot be matched by name.
 */
data class MediaItem(
    val source: MediaSource,
    val path: String,
    val base: String,
    val name: String,
    val size: Long,
    val mtime: Long,
    /** A video's matching thumbnail, resolved once while scanning. */
    val previewPath: String? = null,
) {
    /** Unique across trees, so a selection or a frozen pin never leaks into another. */
    val id: String get() = "${source.id}/$base"

    /**
     * Where rendering may look for a preview, best first.
     *
     * Pictures have a separate `chatthumb` tree. Videos may have a sibling thumbnail
     * tree, but its naming rule is not stable across QQ versions, so a few common
     * shapes are tried and a missing thumbnail simply falls back to a placeholder.
     */
    val thumbnailCandidates: List<String>
        get() = when {
            source == MediaSource.THUMB || source == MediaSource.VIDEO_THUMB -> listOf(path)
            source == MediaSource.VIDEO -> listOfNotNull(previewPath)
            else -> listOf(
                MediaPaths.pathIn(MediaPaths.THUMB_DIR, "Cache_${base}_hd"),
                MediaPaths.pathIn(MediaPaths.THUMB_DIR, "Cache_${base}"),
                path,
            )
        }
}

/** The trees under QQ's media root that the gallery can browse. */
enum class MediaSource(val id: String, val dir: String) {
    IMG("chatimg", MediaPaths.IMG_DIR),
    RAW("chatraw", MediaPaths.RAW_DIR),
    THUMB("chatthumb", MediaPaths.THUMB_DIR),
    VIDEO("shortvideo", MediaPaths.VIDEO_DIR),
    VIDEO_THUMB("shortvideo_thumb", MediaPaths.VIDEO_THUMB_DIR);

    val isVideo: Boolean get() = this == VIDEO

    /**
     * A video's own bytes are not a picture, so they never go to the text model.
     * Its thumbnail is a real frame though, and text on it is worth searching.
     */
    val supportsOcr: Boolean get() = this != VIDEO

    /** One source may live in more than one legacy directory name. */
    val scanDirs: List<String>
        get() = if (this == VIDEO_THUMB) {
            listOf(MediaPaths.VIDEO_THUMB_DIR, MediaPaths.VIDEO_THUMBS_DIR)
        } else {
            listOf(dir)
        }

    fun accepts(name: String): Boolean = when (this) {
        IMG, RAW, THUMB -> name.startsWith("Cache_")
        VIDEO -> MediaPaths.isVideoName(name)
        VIDEO_THUMB -> !name.startsWith(".")
    }

    /** The stable key inside this tree, used for ids and thumbnails. */
    fun baseOf(name: String): String =
        if (this == IMG || this == RAW || this == THUMB) name.removePrefix("Cache_") else name

    companion object {
        fun from(id: String?): MediaSource? = entries.firstOrNull { it.id == id }
    }
}

object MediaPaths {
    /** Root of QQ's private cache on Android, as observed on the target device. */
    const val TENCENT_ROOT =
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ"

    /** Root of QQ's picture cache. */
    const val ROOT = "$TENCENT_ROOT/chatpic"

    const val IMG_DIR = "$ROOT/chatimg"
    const val RAW_DIR = "$ROOT/chatraw"
    const val THUMB_DIR = "$ROOT/chatthumb"
    const val TEMP_DIR = "$ROOT/Temp"

    /** Videos played in QQ, and the two names QQ has used for their thumbnails. */
    const val VIDEO_DIR = "$TENCENT_ROOT/shortvideo"
    const val VIDEO_THUMB_DIR = "$VIDEO_DIR/thumb"
    const val VIDEO_THUMBS_DIR = "$VIDEO_DIR/thumbs"

    /**
     * Builds the real path of [name] inside [dir].
     *
     * QQ files do not sit directly in the tree: `chatimg/000/Cache_-19a3…` lands in
     * the three-character bucket made of the last characters of its own name, which
     * is what spreads 4k directories over the tree. The `_hd` suffix is added after
     * the bucket was decided, so it has to come off again before reading the bucket.
     */
    fun pathIn(dir: String, name: String): String {
        val stem = if (name.endsWith(HD_SUFFIX)) name.dropLast(HD_SUFFIX.length) else name
        return "$dir/${stem.takeLast(3)}/$name"
    }

    /**
     * The id shared by a video and its thumbnail.
     *
     * NT-era caches name the video `<id>.mp4` and the thumbnail `<id>_0.png`; old
     * caches used an unrelated hash in `shortvideo/thumb`, which cannot be matched
     * and simply leaves the video without a preview.
     */
    fun videoKey(name: String): String = name.substringBeforeLast('.', name)

    fun videoThumbKey(name: String): String = videoKey(name).removeSuffix("_0")

    fun isVideoName(name: String): Boolean {
        if (name.startsWith(".")) return false
        val extension = name.substringAfterLast('.', "").lowercase()
        // Some QQ builds store the video without an extension; a thumbnail or a
        // partial download always has one, so the two can still be told apart.
        return extension.isEmpty() || extension in VIDEO_EXTENSIONS
    }

    private const val HD_SUFFIX = "_hd"

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m4v", "mkv", "mov", "avi", "3gp", "3g2", "ts", "webm", "flv", "wmv",
    )
}
