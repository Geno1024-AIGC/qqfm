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
    val thumbnailPath: String get() = "${MediaPaths.THUMB_DIR}/Cache_${base}_hd"
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

    /** Every on-disk file that belongs to [base], used when deleting. */
    fun variants(base: String): List<String> = listOf(
        "$IMG_DIR/Cache_$base",
        "$RAW_DIR/Cache_$base",
        "$THUMB_DIR/Cache_$base",
        "$THUMB_DIR/Cache_${base}_hd",
    )
}
