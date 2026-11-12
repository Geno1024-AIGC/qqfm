package com.geno1024.ai.qqfm.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * Runs the bundled `ntdbread` reader against QQ's message database.
 *
 * `nt_msg.db` is a SQLCipher database that is larger than the phone's free space,
 * so it is never copied: the reader decrypts pages in place through root and
 * streams rows back as TSV. Everything here is read-only — the reader opens the
 * file with SQLite's read-only flag and its VFS refuses every write.
 *
 * The binary is shipped in the APK's assets and executed from the app's own files
 * directory: `assets` is not executable, and `/data/local/tmp` is shared with
 * anything else on the device.
 */
object NtdbTool {

    /** QQ's private data directory; root reaches it, no app API does. */
    private const val QQ_DATA = "/data/user/0/com.tencent.mobileqq"
    private const val DB_ROOT = "$QQ_DATA/databases/nt_db"
    private const val UID_DIR = "$QQ_DATA/files/uid"

    private const val ASSET_NAME = "ntdbread-arm64"
    private const val BINARY_NAME = "ntdbread"

    /** Where the reader and the key it needs live, once located. */
    data class Target(val dbPath: String, val dbDir: String, val md5Key: String)

    /**
     * Finds the message database and derives the key.
     *
     * The key is `md5(md5(nt_uid) + rand)`: `nt_uid` names a file under `files/uid`
     * as `<uin>###<nt_uid>`, and `rand` sits in the 1 KiB vendor header right after
     * the `QQ_NT DB` marker. Both are read from the live device rather than
     * remembered, because QQ rotates `rand` when it re-keys the database.
     */
    fun target(): Target? {
        val dbPath = RootShell.exec("ls -t -- $DB_ROOT/nt_qq_*/nt_msg.db 2>/dev/null")
            .output.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?: return null

        val ntUid = RootShell.exec("ls -1 -- $UID_DIR 2>/dev/null")
            .output.lineSequence().map(String::trim).firstOrNull(String::isNotEmpty)
            ?.substringAfter("###", "")
            ?.takeIf { it.isNotEmpty() }
            ?: return null

        val head = RootShell.readBytesAt(dbPath, 0L, 1024) ?: return null
        val rand = randomOf(head) ?: return null

        return Target(dbPath, dbPath.substringBeforeLast('/'), md5Hex(md5Hex(ntUid) + rand))
    }

    /**
     * Picks `rand` out of the vendor header.
     *
     * The marker is followed by a small protobuf: `12 <len> <rand> 1a <len>
     * <version> 22 <len> <hmac>`. Reading it as "the length-prefixed string right
     * after the marker" is exact, while "any printable run in the header" also
     * matches the version and the HMAC name.
     */
    private fun randomOf(head: ByteArray): String? {
        val marker = indexOf(head, "QQ_NT DB".toByteArray(Charsets.US_ASCII))
        if (marker < 0) return null
        var i = marker + "QQ_NT DB".length
        while (i + 1 < head.size) {
            val tag = head[i].toInt() and 0xFF
            val len = head[i + 1].toInt() and 0xFF
            if ((tag == 0x12 || tag == 0x0a) && len in 4..24 && i + 2 + len <= head.size) {
                val bytes = head.copyOfRange(i + 2, i + 2 + len)
                if (bytes.all { it in 0x20..0x7e }) {
                    return String(bytes, Charsets.US_ASCII)
                }
            }
            i++
        }
        return null
    }

    /** Streams SQL rows (TSV, first line is the column list) for [sql]. */
    fun forEachRow(
        context: Context,
        target: Target,
        dbPath: String,
        sql: String,
        action: (List<String>) -> Unit,
    ) {
        val bin = binary(context) ?: return
        RootShell.forEachLine(
            "${q(bin.absolutePath)} --md5key ${target.md5Key} ${q(dbPath)} ${q(sql)} " +
                "2>/dev/null",
        ) { line ->
            // The reader reports "ntdbread: rows=N" on stderr, which root merges
            // into stdout; 2>/dev/null keeps that out and this stays a plain row.
            if (line.startsWith("ntdbread:")) return@forEachLine
            action(line.split('\t'))
        }
    }

    /**
     * First and last message id of each message table, to drive a progress bar.
     *
     * `MIN`/`MAX` of the id is an index lookup — measured at 0.1 s on a 15.9 GB
     * database — while `COUNT(*)` has to decrypt every row (a 74 s scan, which would
     * double the build). Message ids are time-ordered, so the id is the thing worth
     * measuring progress against. Keep the four bounds as separate sub-selects: the
     * same query written as one `UNION ALL` loses SQLite's min/max shortcut and falls
     * back to that full scan.
     */
    fun messageBounds(context: Context, target: Target): Map<String, LongRange> {
        val sql = "SELECT (SELECT MIN([40001]) FROM group_msg_table), " +
            "(SELECT MAX([40001]) FROM group_msg_table), " +
            "(SELECT MIN([40001]) FROM c2c_msg_table), " +
            "(SELECT MAX([40001]) FROM c2c_msg_table)"
        val out = HashMap<String, LongRange>(4)
        var header = true
        forEachRow(context, target, target.dbPath, sql) { f ->
            if (header) {
                header = false
                return@forEachRow
            }
            val gMin = f.getOrNull(0)?.toLongOrNull()
            val gMax = f.getOrNull(1)?.toLongOrNull()
            if (gMin != null && gMax != null) out["group_msg_table"] = gMin..gMax
            val cMin = f.getOrNull(2)?.toLongOrNull()
            val cMax = f.getOrNull(3)?.toLongOrNull()
            if (cMin != null && cMax != null) out["c2c_msg_table"] = cMin..cMax
        }
        return out
    }

    /**
     * Streams the picture index: one TSV row per media element in every message.
     *
     * Columns are `table msgid peer sender elem md5 filename size`; `elem` is the
     * element type (2 = picture, 3 = video or file, 4 = voice).
     *
     * [since] starts a table at the given message id instead of at its first row,
     * so a scan that already read up to there does not have to be repeated. The
     * tables are read in id order, which is what makes a watermark safe.
     */
    fun forEachPic(
        context: Context,
        target: Target,
        since: Map<String, Long> = emptyMap(),
        action: (PicRow) -> Unit,
    ) {
        val bin = binary(context) ?: return
        val from = since.entries.joinToString("") { (table, msgid) ->
            " --since ${q("$table=$msgid")}"
        }
        RootShell.forEachLine(
            "${q(bin.absolutePath)} --md5key ${target.md5Key} ${q(target.dbPath)}" +
                " --pics$from 2>/dev/null",
        ) { line ->
            val row = PicRow.parse(line) ?: return@forEachLine
            action(row)
        }
    }

    data class PicRow(
        val table: String,
        val msgid: Long,
        val peer: String,
        val sender: String,
        val elem: Int,
        val md5: String,
        val size: Long,
        /**
         * The local cache path the sending client wrote, when it wrote one:
         * `::NTOSFull::/storage/.../chatimg/0e6/Cache_<id>`. Only trusted when the
         * name it ends in exists in this device's cache.
         */
        val path: String?,
    ) {
        companion object {
            fun parse(line: String): PicRow? {
                if (line.startsWith("ntdbread:") || line.startsWith("table\t")) return null
                val f = line.split('\t')
                if (f.size < 8) return null
                val msgid = f[1].toLongOrNull() ?: return null
                val elem = f[4].toIntOrNull() ?: return null
                if (f[5].length != 32) return null
                return PicRow(
                    table = f[0],
                    msgid = msgid,
                    peer = f[2],
                    sender = f[3],
                    elem = elem,
                    md5 = f[5],
                    size = f[7].toLongOrNull() ?: -1L,
                    path = f.getOrNull(8)?.takeIf { it.isNotEmpty() },
                )
            }
        }
    }

    /**
     * The executable copy of the reader, refreshed whenever the app is updated.
     *
     * Assets cannot be executed, so the file is copied into the app's private
     * directory once. `su` must then be told it is executable, because the copy
     * arrives with the shell's default mode.
     */
    fun binary(context: Context): File? = runCatching {
        val dir = File(context.filesDir, "native").apply { mkdirs() }
        val out = File(dir, BINARY_NAME)
        val stamp = File(dir, "$BINARY_NAME.stamp")
        val version = context.packageManager
            .getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (!out.exists() || out.length() == 0L || stamp.takeIf { it.exists() }?.readText() != version) {
            context.assets.open(ASSET_NAME).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            stamp.writeText(version)
        }
        if (RootShell.exec("chmod 755 -- ${q(out.absolutePath)} 2>/dev/null").exitCode != 0) {
            return null
        }
        out
    }.getOrNull()

    fun md5Hex(text: String): String =
        hex(MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)))

    fun hex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private fun q(value: String): String = RootShell.shellQuote(value)

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private const val HEX = "0123456789abcdef"
}
