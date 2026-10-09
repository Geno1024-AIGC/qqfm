package com.geno1024.ai.qqfm.data

/**
 * One entry of the chat picture cache. QQ stores three parallel trees keyed by
 * the same base id: `chatimg` (displayable), `chatraw` (originals) and
 * `chatthumb` (thumbnails, with an additional `_hd` variant). The gallery is
 * built from `chatimg`; the other trees are only touched when deleting.
 */
data class MediaItem(
    val path: String,
    val base: String,
    val name: String,
    val size: Long,
    val mtime: Long,
) {
    val id: String get() = base

    /** Thumbnail candidate inside `chatthumb`, tried first when rendering. */
    val thumbnailPath: String get() = "${MediaPaths.THUMB_DIR}/Cache_${base}_hd"
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
