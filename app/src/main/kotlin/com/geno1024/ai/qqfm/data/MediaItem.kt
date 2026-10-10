package com.geno1024.ai.qqfm.data

/**
 * One entry of QQ's picture cache.
 *
 * The user browses one tree at a time: NTQQ names a file after
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
) {
    /** Unique across trees, so a selection or a frozen pin never leaks into another. */
    val id: String get() = "${source.id}/$base"

    /** Thumbnail candidate inside `chatthumb`, tried first when rendering. */
    val thumbnailPath: String get() = MediaPaths.pathIn(MediaPaths.THUMB_DIR, "Cache_${base}_hd")
}

/** The three trees under [MediaPaths.ROOT] that hold chat pictures. */
enum class MediaSource(val id: String, val dir: String) {
    IMG("chatimg", MediaPaths.IMG_DIR),
    RAW("chatraw", MediaPaths.RAW_DIR),
    THUMB("chatthumb", MediaPaths.THUMB_DIR);

    companion object {
        fun from(id: String?): MediaSource? = entries.firstOrNull { it.id == id }
    }
}

object MediaPaths {
    /** Root of QQ's picture cache on Android, as observed on the target device. */
    const val ROOT =
        "/storage/emulated/0/Android/data/com.tencent.mobileqq/Tencent/MobileQQ/chatpic"

    const val IMG_DIR = "$ROOT/chatimg"
    const val RAW_DIR = "$ROOT/chatraw"
    const val THUMB_DIR = "$ROOT/chatthumb"
    const val TEMP_DIR = "$ROOT/Temp"

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
     * Every on-disk file that belongs to [base], used when deleting.
     *
     * Under NTQQ the same picture gets a different name in each tree
     * (`crc64("<tree>:<md5>")`), so only the browsed tree normally matches; this
     * still sweeps the others for the older layout that named all three alike.
     */
    fun variants(base: String): List<String> =
        listOf("Cache_$base", "Cache_${base}$HD_SUFFIX").flatMap { name ->
            listOf(IMG_DIR, RAW_DIR, THUMB_DIR).map { dir -> pathIn(dir, name) }
        }

    private const val HD_SUFFIX = "_hd"
}
