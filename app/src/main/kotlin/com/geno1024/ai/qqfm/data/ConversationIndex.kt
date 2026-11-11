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
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    /**
     * Rebuilds the picture index from a live scan of the message database.
     *
     * [needed] is the set of md5s the device can actually show; rows for anything
     * else are dropped as they stream past, so the index never outgrows the cache.
     * Only element type 2 is a picture — the same md5 appears on videos, files and
     * voice notes, and grouping those with photos would be wrong.
     */
    fun rebuildFrom(
        target: NtdbTool.Target,
        needed: Set<String>,
        context: Context,
        onProgress: (scanned: Long, matched: Int) -> Unit = { _, _ -> },
    ) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM pic")
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
        // The same picture is often forwarded into several conversations. The first
        // message that carried it is the one the picture is "from", so a later row
        // only replaces an earlier one when its msgid is older. Upsert syntax needs
        // SQLite 3.24 and this app runs on 26, so the update is spelled out.
        val replace = db.compileStatement(
            "UPDATE pic SET peer = ?, msgid = ?, sender = ? WHERE md5 = ? AND msgid > ?",
        )
        try {
            db.beginTransaction()
            try {
                NtdbTool.forEachPic(context, target) { row ->
                    scanned++
                    if (row.elem == 2 && row.md5 in needed) {
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
                        // Commit every so often so a long scan survives the app
                        // being stopped, and so the gallery can read meanwhile.
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

    /** The conversation a picture belongs to, or null when it is not indexed. */
    fun peerOf(md5: String): String? =
        helper.readableDatabase.rawQuery("SELECT peer FROM pic WHERE md5 = ?", arrayOf(md5))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    /**
     * Every indexed md5 and its conversation, read in one pass.
     *
     * Grouping a tree asks about a hundred thousand pictures at once; one query per
     * picture would spend more time in SQLite than the grouping does.
     */
    fun allPeers(): Map<String, String> {
        val out = HashMap<String, String>(100_000)
        helper.readableDatabase.rawQuery("SELECT md5, peer FROM pic", null).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getString(1)
        }
        return out
    }

    fun indexed(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM pic", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** Conversations that own at least one indexed picture, largest first. */
    fun peers(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        helper.readableDatabase.rawQuery(
            "SELECT peer, COUNT(*) AS n FROM pic GROUP BY peer ORDER BY n DESC",
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

    private class Helper(context: Context) : SQLiteOpenHelper(context, "conversation.db", null, 1) {
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
            db.execSQL("CREATE TABLE peer_name(peer TEXT PRIMARY KEY, name TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // The index is expensive to build, so upgrades never drop it.
        }
    }
}
