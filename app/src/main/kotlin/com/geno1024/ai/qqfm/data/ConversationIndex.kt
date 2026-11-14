package com.geno1024.ai.qqfm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Which conversation each picture md5 was sent in, and how those conversations
 * are called.
 *
 * Only the pictures the device actually has are kept: the message database holds
 * two million plus media rows, while the cache holds a sixth of that, and the
 * gallery only ever asks about a file it can see. That keeps the index at a few
 * megabytes instead of a few hundred.
 *
 * `peer` is QQ's own conversation id — the group number for a group chat, the
 * friend's `u_…` uid for a one-to-one chat — exactly as `40021` stores it.
 */
class ConversationIndex(context: Context) {

    private val helper = Helper(context.applicationContext)

    fun clear() {
        helper.writableDatabase.apply {
            beginTransaction()
            try {
                execSQL("DELETE FROM pic")
                execSQL("DELETE FROM named")
                execSQL("DELETE FROM filepic")
                execSQL("DELETE FROM meta")
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    /** What the two name routes filed, for the report on the about page. */
    data class Filed(
        val fromPath: Int,
        val fromMd5: Int,
        /** Rows each message table held, so the next build's bar knows their weight. */
        val rowsByTable: Map<String, Long>,
        /**
         * The last message id read from each table that was scanned, to continue
         * from next time. A table whose scan found nothing new keeps its old value
         * because it is simply absent here.
         */
        val watermark: Map<String, Long>,
    )

    /**
     * Rebuilds the index from a live scan of the message database.
     *
     * Three routes, because QQ records three different things:
     *
     *  - [needed] md5s: the bytes of `chatraw` are the bytes that were sent, so a
     *    picture there matches the message's own md5. Every element type is accepted
     *    rather than only pictures: the md5 is of the file's bytes, and `needed` only
     *    holds files that exist in `chatraw`, so a video or voice row can never match
     *    one, while an image QQ filed under another type still can.
     *  - [localBases] by path: a message the user sent from this phone carries the
     *    local cache path it wrote (`f45403`, `::NTOSFull::…/Cache_<id>`). That name
     *    is only usable when the file exists here — another sender's path names a
     *    file on their phone — so it is checked against the cache before storing.
     *  - [localBases] by derived name: every tree names its files after the
     *    picture's original md5 ([CacheName]), so `chatimg` and `chatthumb` copies
     *    are reachable even though QQ re-encoded them and their bytes no longer
     *    hash to that md5. This is the wide route: on a real library it links 81,997
     *    `chatimg` and 86,754 `chatthumb` files that the md5 route cannot see at
     *    all, because those pictures were never downloaded as originals.
     *  - [localBases] by video name: `shortvideo/<md5>.mp4` and
     *    `shortvideo/thumb/<md5>_0.png` carry the message's md5 as their name, so a
     *    video is filed under that md5 ([MediaSource.indexKey]). Measured over the
     *    message database, 93.7% of the video elements name their own file
     *    `<md5>.mp4`; the rest keep whatever name the sender's client wrote.
     *
     * Pairs are filed under the earliest message that carried them: a picture is
     * "from" the conversation it first appeared in, not the latest forward.
     *
     * [resume] turns the pass incremental: each entry continues a table at the
     * message id an earlier build stopped at, and the rows already filed are kept
     * rather than re-stated. Message ids increase with time, so a watermark never
     * hides a message that was there before — but a picture that only *now* became
     * known (QQ downloaded its original later) sits behind a watermark that already
     * passed it. That case is what the "force rebuild" button is for.
     */
    fun rebuildFrom(
        target: NtdbTool.Target,
        needed: Set<String>,
        localBases: Set<String>,
        context: Context,
        resume: Map<String, Long> = emptyMap(),
        onProgress: (scanned: Long, matched: Int, table: String, msgid: Long) -> Unit =
            { _, _, _, _ -> },
    ): Filed {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (resume.isEmpty()) {
                db.execSQL("DELETE FROM pic")
                db.execSQL("DELETE FROM named")
                db.execSQL("DELETE FROM filepic")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        var scanned = 0L
        var matched = 0
        var batch = 0
        val insert = db.compileStatement(
            "INSERT OR IGNORE INTO pic(md5, peer, msgid, sender) VALUES(?, ?, ?, ?)",
        )
        // Upsert syntax needs SQLite 3.24 and this app runs on 26, so the "only when
        // older" update is spelled out instead.
        val replace = db.compileStatement(
            "UPDATE pic SET peer = ?, msgid = ?, sender = ? WHERE md5 = ? AND msgid > ?",
        )
        val insertNamed = db.compileStatement(
            "INSERT OR IGNORE INTO named(base, peer, msgid, sender) VALUES(?, ?, ?, ?)",
        )
        val replaceNamed = db.compileStatement(
            "UPDATE named SET peer = ?, msgid = ?, sender = ? WHERE base = ? AND msgid > ?",
        )

        fun bumpBatch() {
            if (++batch >= 4000) {
                db.setTransactionSuccessful()
                db.endTransaction()
                db.beginTransaction()
                batch = 0
            }
        }

        /**
         * Files [base] under [peer], sent by [sender]; true when that is a name the
         * index did not have.
         *
         * The sender rides along on the same row because it is the message that says
         * both, and the gallery shows them together.
         */
        fun name(base: String, peer: String, msgid: Long, sender: String): Boolean {
            insertNamed.bindString(1, base)
            insertNamed.bindString(2, peer)
            insertNamed.bindLong(3, msgid)
            insertNamed.bindString(4, sender)
            // INSERT OR IGNORE reports -1 when the name is already there.
            if (insertNamed.executeInsert() >= 0) {
                bumpBatch()
                return true
            }
            replaceNamed.bindString(1, peer)
            replaceNamed.bindLong(2, msgid)
            replaceNamed.bindString(3, sender)
            replaceNamed.bindString(4, base)
            replaceNamed.bindLong(5, msgid)
            replaceNamed.execute()
            bumpBatch()
            return false
        }

        var fromPath = 0
        var fromMd5 = 0
        val rowsByTable = HashMap<String, Long>()
        val watermark = HashMap<String, Long>(4)

        try {
            db.beginTransaction()
            try {
                NtdbTool.forEachPic(context, target, resume) { row ->
                    scanned++
                    rowsByTable.merge(row.table, 1L, Long::plus)
                    watermark[row.table] = row.msgid
                    if (row.md5 in needed) {
                        insert.bindString(1, row.md5)
                        insert.bindString(2, row.peer)
                        insert.bindLong(3, row.msgid)
                        insert.bindString(4, row.sender)
                        // INSERT OR IGNORE reports -1 when the md5 is already there.
                        if (insert.executeInsert() < 0) {
                            replace.bindString(1, row.peer)
                            replace.bindLong(2, row.msgid)
                            replace.bindString(3, row.sender)
                            replace.bindString(4, row.md5)
                            replace.bindLong(5, row.msgid)
                            replace.execute()
                        }
                        matched++
                        bumpBatch()
                    }

                    // Sent-from-here pictures name their own cache file. Element type
                    // is 3 on these, so this is deliberately outside the check above.
                    val base = row.path?.substringAfterLast('/', "")?.removePrefix("Cache_")
                    if (!base.isNullOrEmpty() && base in localBases) {
                        if (name(base, row.peer, row.msgid, row.sender)) fromPath++
                        matched++
                    }

                    // The copy in `chatimg`/`chatthumb` is a re-encoding, so its bytes
                    // no longer hash to the message's md5 — but QQ named it after that
                    // md5 anyway, so the name still leads back to the conversation.
                    // Only names that exist on this device are filed, which keeps the
                    // table at what the gallery can actually show.
                    for (tree in CacheName.TREES) {
                        val derived = CacheName.of(tree, row.md5) ?: continue
                        if (derived in localBases) {
                            if (name(derived, row.peer, row.msgid, row.sender)) fromMd5++
                            matched++
                        }
                        // Most thumbnails are written with an `_hd` suffix, and that
                        // is a separate file with its own name, so it needs its own
                        // row. `chatimg` never uses the suffix.
                        if (tree == MediaSource.THUMB) {
                            val hd = derived + "_hd"
                            if (hd in localBases) {
                                if (name(hd, row.peer, row.msgid, row.sender)) fromMd5++
                                matched++
                            }
                        }
                    }

                    // A video keeps the message's md5 as its own name — that is what
                    // `shortvideo/<md5>.mp4` and `shortvideo/thumb/<md5>_0.png` are —
                    // so the md5 is the key, not a derived one. Element type 5 is the
                    // video element; type 3 is a file attachment that may also be an
                    // mp4, but those live elsewhere and are left alone.
                    if (row.elem == 5) {
                        val key = row.md5.lowercase()
                        if (key in localBases) {
                            name(key, row.peer, row.msgid, row.sender)
                            matched++
                        }
                    }

                    if (scanned % 20_000 == 0L) onProgress(scanned, matched, row.table, row.msgid)
                }
                db.setTransactionSuccessful()
            } finally {
                runCatching { db.endTransaction() }
            }
        } finally {
            insert.close()
            replace.close()
            insertNamed.close()
            replaceNamed.close()
        }
        onProgress(scanned, matched, "", 0L)
        return Filed(fromPath, fromMd5, rowsByTable, watermark)
    }

    /** One row of `files_in_chat_table`'s picture records. */
    private val filePicturesSql =
        "SELECT \"45406\", \"40021\", \"40050\" FROM files_in_chat_table WHERE \"45002\" = 2"

    /**
     * Files the pictures `files_in_chat.db` knows, as a second md5 → conversation map.
     *
     * That database is QQ's own record of every file it handled, and its `45406` is
     * the content md5 written out as base64 — the same value we compute over
     * `chatraw`. Measured on a real library: 93,237 md5s appear in both it and
     * `nt_msg.db`, and 92% of them name the same conversation; of the rest almost all
     * are the same picture forwarded into several chats, where the two tables pick
     * different "first" messages. It covers 99,262 of the 116,544 `chatraw` files
     * against the message scan's 65,948, and the union is 104,361.
     *
     * Only [needed] md5s are stored, so a stray row whose base64 is a file name rather
     * than a content hash (QQ does that too) is dropped by the lookup.
     */
    fun addFilePictures(
        target: NtdbTool.Target,
        needed: Set<String>,
        context: Context,
        onProgress: (scanned: Long, matched: Int) -> Unit = { _, _ -> },
    ) {
        val db = helper.writableDatabase
        var scanned = 0L
        var matched = 0
        var batch = 0
        val insert = db.compileStatement(
            "INSERT OR IGNORE INTO filepic(md5, peer, time) VALUES(?, ?, ?)",
        )
        val replace = db.compileStatement(
            "UPDATE filepic SET peer = ?, time = ? WHERE md5 = ? AND time > ?",
        )
        try {
            db.beginTransaction()
            try {
                NtdbTool.forEachRow(
                    context, target, "${target.dbDir}/files_in_chat.db", filePicturesSql,
                ) { f ->
                    scanned++
                    val md5 = f.getOrNull(0)?.let(::base64Md5)
                    val peer = f.getOrNull(1).orEmpty()
                    if (md5 != null && peer.isNotEmpty() && md5 in needed) {
                        val time = f.getOrNull(2)?.toLongOrNull() ?: 0L
                        insert.bindString(1, md5)
                        insert.bindString(2, peer)
                        insert.bindLong(3, time)
                        if (insert.executeInsert() < 0) {
                            replace.bindString(1, peer)
                            replace.bindLong(2, time)
                            replace.bindString(3, md5)
                            replace.bindLong(4, time)
                            replace.execute()
                        }
                        matched++
                        // Commit in chunks so a long scan survives the app being
                        // stopped, and so the gallery can read meanwhile.
                        if (++batch >= 4000) {
                            db.setTransactionSuccessful()
                            db.endTransaction()
                            db.beginTransaction()
                            batch = 0
                        }
                    }
                    if (scanned % 20_000 == 0L) onProgress(scanned, matched)
                }
                db.setTransactionSuccessful()
            } finally {
                runCatching { db.endTransaction() }
            }
        } finally {
            insert.close()
            replace.close()
        }
        onProgress(scanned, matched)
    }

    /**
     * A 16-byte base64 value as hex, or null when it is not a bare 16-byte digest.
     *
     * QQ writes image digests as `45406` without padding in some rows, and file-name
     * blobs in others, so length is the only filter that is both cheap and safe.
     */
    private fun base64Md5(text: String): String? {
        if (text.isBlank()) return null
        val bytes = runCatching {
            android.util.Base64.decode(text, android.util.Base64.DEFAULT)
        }.getOrNull() ?: return null
        if (bytes.size != 16) return null
        return NtdbTool.hex(bytes)
    }

    /** The conversation a picture belongs to, or null when it is not indexed. */
    fun peerOf(md5: String): String? =
        helper.readableDatabase.rawQuery("SELECT peer FROM pic WHERE md5 = ?", arrayOf(md5))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * One picture → conversation link: who sent it and which message carried it.
     *
     * [sender] is empty for the file table, which records a conversation but not a
     * person. [at] is the message id, except on that same route where it is the time
     * QQ recorded for the file; both exist to keep the earliest statement of a link.
     */
    data class Link(val peer: String, val sender: String, val at: Long)

    /**
     * Every indexed md5 and its conversation, read in one pass.
     *
     * Grouping a tree asks about a hundred thousand pictures at once; one query per
     * picture would spend more time in SQLite than the grouping does.
     *
     * The message scan is read last so it wins where both routes know a md5: it
     * carries the sender and a real message id, which the file table lacks.
     */
    fun allPeers(): Map<String, Link> {
        val out = HashMap<String, Link>(120_000)
        helper.readableDatabase.rawQuery(
            "SELECT md5, peer, time FROM filepic", null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] = Link(cursor.getString(1), "", cursor.getLong(2))
            }
        }
        helper.readableDatabase.rawQuery(
            "SELECT md5, peer, sender, msgid FROM pic", null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] =
                    Link(cursor.getString(1), cursor.getString(2), cursor.getLong(3))
            }
        }
        return out
    }

    /**
     * The build's small key/value bag: the cache token and the numbers the about page
     * reports. Kept in SQLite rather than a file so it is written in the same
     * transaction as the index it describes.
     */
    fun metaAll(): Map<String, String> {
        val out = HashMap<String, String>(32)
        helper.readableDatabase.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getString(1)
        }
        return out
    }

    fun putMeta(values: Map<String, String>) {
        if (values.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            for ((key, value) in values) {
                db.execSQL(
                    "INSERT OR REPLACE INTO meta(key, value) VALUES(?, ?)",
                    arrayOf<Any>(key, value),
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Every cache name (without `Cache_`) the name routes filed a conversation for:
     * the files a sent-from-here message named itself, the `chatimg`/`chatthumb`
     * copies whose names QQ derived from the picture's md5, and the `shortvideo`
     * files whose md5 is the message's own.
     */
    fun allNamed(): Map<String, Link> {
        val out = HashMap<String, Link>(200_000)
        helper.readableDatabase.rawQuery(
            "SELECT base, peer, sender, msgid FROM named", null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out[cursor.getString(0)] =
                    Link(cursor.getString(1), cursor.getString(2), cursor.getLong(3))
            }
        }
        return out
    }

    /**
     * Every uid the index knows as a *sender*, so the naming pass can resolve them
     * to nicknames alongside the conversations themselves.
     */
    fun senders(): List<String> {
        val out = ArrayList<String>(16_000)
        helper.readableDatabase.rawQuery(
            "SELECT sender FROM pic WHERE sender <> '' " +
                "UNION SELECT sender FROM named WHERE sender <> ''",
            null,
        ).use { cursor -> while (cursor.moveToNext()) out.add(cursor.getString(0)) }
        return out
    }

    fun indexed(): Int {
        val db = helper.readableDatabase
        val byMd5 = db.rawQuery(
            "SELECT COUNT(*) FROM (SELECT md5 FROM pic UNION SELECT md5 FROM filepic)",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
        val byName = db.rawQuery("SELECT COUNT(*) FROM named", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
        return byMd5 + byName
    }

    /** How many indexed pictures know who sent them. */
    fun senderCount(): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM (" +
                "SELECT md5 FROM pic WHERE sender <> '' " +
                "UNION SELECT base FROM named WHERE sender <> ''" +
                ")",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** md5s the file table supplied that the message scan did not. */
    fun fileOnlyCount(): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM filepic WHERE md5 NOT IN (SELECT md5 FROM pic)",
            null,
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    /** How many cache names were filed by the path route. */
    fun namedCount(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM named", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** Conversations that own at least one indexed picture, largest first. */
    fun peers(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        helper.readableDatabase.rawQuery(
            "SELECT peer, COUNT(*) AS n FROM (" +
                "SELECT md5, peer FROM pic " +
                "UNION SELECT md5, peer FROM filepic " +
                "UNION SELECT base, peer FROM named" +
                ") GROUP BY peer ORDER BY n DESC",
            null,
        ).use { cursor -> while (cursor.moveToNext()) out.add(cursor.getString(0) to cursor.getInt(1)) }
        return out
    }

    /** Display names for [peers], read from QQ's own group and profile databases. */
    fun namePeers(target: NtdbTool.Target, context: Context, peers: Collection<String>) {
        if (peers.isEmpty()) return
        val groups = peers.filter { it.all(Char::isDigit) }.toHashSet()
        val uids = peers.filterNot { it.all(Char::isDigit) }.toHashSet()
        val db = helper.writableDatabase

        fun store(peer: String, name: String) {
            if (name.isBlank()) return
            db.execSQL(
                "INSERT OR REPLACE INTO peer_name(peer, name) VALUES(?, ?)",
                arrayOf<Any>(peer, name),
            )
        }

        // One query per database, filtered here rather than with `IN (...)`: the
        // reader makes SQLite decrypt the whole table for every call, and a peer
        // list is long enough that chunking it costs more than reading the names.
        if (groups.isNotEmpty()) {
            NtdbTool.forEachRow(
                context, target, "${target.dbDir}/group_info.db",
                "SELECT \"60001\", \"60007\" FROM group_list",
            ) { f ->
                if (f.size >= 2 && f[0] in groups) store(f[0], f[1])
            }
        }

        if (uids.isNotEmpty()) {
            NtdbTool.forEachRow(
                context, target, "${target.dbDir}/profile_info.db",
                // 1000 = uid, 20002 = nickname, 20009 = remark; the remark is what
                // the user named the friend themselves, so it wins when present.
                "SELECT \"1000\", \"20002\", \"20009\" FROM profile_info_v6",
            ) { f ->
                if (f.size >= 3 && f[0] in uids) {
                    store(f[0], f[2].ifBlank { f[1] })
                }
            }
        }
    }

    fun nameOf(peer: String): String? =
        helper.readableDatabase.rawQuery("SELECT name FROM peer_name WHERE peer = ?", arrayOf(peer))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "conversation.db", null, 5) {
        init {
            // The scan writes while the gallery reads; WAL keeps them apart.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE pic(" +
                    "md5 TEXT PRIMARY KEY, " +
                    "peer TEXT NOT NULL, " +
                    "msgid INTEGER NOT NULL, " +
                    "sender TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX pic_peer ON pic(peer)")
            db.execSQL(
                "CREATE TABLE named(" +
                    "base TEXT PRIMARY KEY, " +
                    "peer TEXT NOT NULL, " +
                    "msgid INTEGER NOT NULL, " +
                    "sender TEXT NOT NULL DEFAULT '')",
            )
            db.execSQL("CREATE INDEX named_peer ON named(peer)")
            db.execSQL(
                "CREATE TABLE filepic(" +
                    "md5 TEXT PRIMARY KEY, " +
                    "peer TEXT NOT NULL, " +
                    "time INTEGER NOT NULL)",
            )
            db.execSQL("CREATE INDEX filepic_peer ON filepic(peer)")
            db.execSQL("CREATE TABLE peer_name(peer TEXT PRIMARY KEY, name TEXT NOT NULL)")
            db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The index is expensive to build, so upgrades never drop it. Version 2
            // adds the name route, version 3 the file-table route, version 4 the
            // cache token, version 5 the sender on the name route; every new table
            // starts empty and the next build fills it.
            if (oldVersion < 2) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS named(" +
                        "base TEXT PRIMARY KEY, " +
                        "peer TEXT NOT NULL, " +
                        "msgid INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS named_peer ON named(peer)")
            }
            if (oldVersion < 3) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS filepic(" +
                        "md5 TEXT PRIMARY KEY, " +
                        "peer TEXT NOT NULL, " +
                        "time INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS filepic_peer ON filepic(peer)")
            }
            if (oldVersion < 4) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value TEXT NOT NULL)",
                )
            }
            if (oldVersion < 5) {
                // Every existing row keeps an empty sender, so a reader can tell "the
                // message scan filled this" from "only the name route did". Checked
                // rather than assumed: a half-finished upgrade must not fail here for
                // ever, and SQLite has no `ADD COLUMN IF NOT EXISTS`.
                var has = false
                db.rawQuery("PRAGMA table_info(named)", null).use { cursor ->
                    val name = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) {
                        if (cursor.getString(name) == "sender") has = true
                    }
                }
                if (!has) {
                    db.execSQL("ALTER TABLE named ADD COLUMN sender TEXT NOT NULL DEFAULT ''")
                }
            }
        }
    }
}
