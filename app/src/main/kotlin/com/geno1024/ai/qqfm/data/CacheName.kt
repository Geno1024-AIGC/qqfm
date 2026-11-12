package com.geno1024.ai.qqfm.data

/**
 * The cache file name QQ gives a picture, computed from that picture's md5.
 *
 * Every file in `chatraw`, `chatimg` and `chatthumb` is called `Cache_<hex>`, and
 * that hex is
 *
 *     CRC64("<tree>:<md5 as 32 upper-case hex digits>")
 *
 * with the ECMA polynomial `0x9A6C9329AC4BC9B5`, printed as a **signed** 64-bit
 * number (`Cache_-6bf439a6638855bb`) and placed in the sub-directory named by its
 * last three characters. The rule comes from QQ's own export tooling
 * (`QQBackup/QQDecrypt`, `docs/database/message_export.md`).
 *
 * The shift is arithmetic, not logical: QQ's own code keeps the register in a
 * signed integer, so the sign bit is copied down. Doing it the textbook way with a
 * logical shift produces a completely different name for every input, which is why
 * [POLY] is stored as the negative number QQ writes rather than as its unsigned
 * form.
 *
 * The important part is that the name is derived from the *original* md5. `chatimg`
 * and `chatthumb` hold re-encoded copies, so their bytes no longer hash to the
 * message's md5 — but their names still do, which is what makes those two trees
 * reachable at all. Measured on a real library: deriving each `chatraw` file's name
 * from its own md5 reproduces 100,822 of 100,927 exactly, and 100,874 of the derived
 * names exist in the tree; the derived `chatimg` name of a message's md5 hits a real
 * file 81,997 times.
 */
object CacheName {

    /** The trees that name their files this way, in the order the index walks them. */
    val TREES = listOf(MediaSource.IMG, MediaSource.THUMB)

    /** QQ's polynomial, `0x9A6C9329AC4BC9B5`, as the signed long it is written as. */
    private const val POLY = -7661587058870466123L

    private val TABLE = LongArray(256) { index ->
        var value = index.toLong()
        repeat(8) {
            value = if (value and 1L != 0L) (value shr 1) xor POLY else value shr 1
        }
        value
    }

    /** State after `"<tree>:"`, which is the same for every picture in that tree. */
    private fun prefix(tree: String): Long {
        var crc = -1L
        for (ch in "$tree:") {
            crc = TABLE[((crc xor ch.code.toLong()) and 0xFF).toInt()] xor (crc shr 8)
        }
        return crc
    }

    private val IMG = prefix(MediaSource.IMG.id)
    private val THUMB = prefix(MediaSource.THUMB.id)

    /**
     * The name (without `Cache_`) [tree] gives the file for [md5], or null when
     * [md5] is not a 32-digit hex digest or the tree is not named this way.
     *
     * `chatthumb` writes most of its files with an `_hd` suffix, so callers that
     * look names up in that tree have to try `"$name_hd"` as well.
     */
    fun of(tree: MediaSource, md5: String): String? {
        val start = when (tree) {
            MediaSource.IMG -> IMG
            MediaSource.THUMB -> THUMB
            else -> return null
        }
        if (md5.length != 32) return null
        var crc = start
        for (ch in md5) {
            // The digest is written upper-case into the hash; the database stores
            // it lower-case, so fold as we go instead of allocating a copy.
            val c = when {
                ch in 'a'..'f' -> ch - 32
                ch in '0'..'9' -> ch
                ch in 'A'..'F' -> ch
                else -> return null
            }
            crc = TABLE[((crc xor c.code.toLong()) and 0xFF).toInt()] xor (crc shr 8)
        }
        // Printed as a signed number: a negative value keeps its sign and the
        // magnitude is written in plain hex, the way QQ's file names show it.
        if (crc >= 0) return java.lang.Long.toHexString(crc)
        val magnitude = if (crc == Long.MIN_VALUE) Long.MIN_VALUE else -crc
        return "-" + java.lang.Long.toHexString(magnitude)
    }
}
