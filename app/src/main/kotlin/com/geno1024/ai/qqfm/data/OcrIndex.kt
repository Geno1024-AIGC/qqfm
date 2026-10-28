package com.geno1024.ai.qqfm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * What OCR has read out of each picture, kept in SQLite.
 *
 * A substring match (`LIKE '%词语%'`) rather than FTS: Chinese has no spaces, so
 * the tokenizers FTS ships with cut sentences into unusable pieces, while a plain
 * `LIKE` finds a phrase anywhere in the text. The table only grows as pictures are
 * opened, so the scan stays cheap.
 */
class OcrIndex(context: Context) {

    private val helper = Helper(context.applicationContext)

    fun textOf(id: String): String? =
        helper.readableDatabase.rawQuery(
            "SELECT text FROM ocr WHERE id = ?",
            arrayOf(id),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    fun contains(id: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM ocr WHERE id = ? LIMIT 1",
            arrayOf(id),
        ).use { cursor -> cursor.moveToFirst() }

    fun put(id: String, text: String) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT OR REPLACE INTO ocr(id, text, updated) VALUES(?, ?, ?)",
                arrayOf<Any>(id, text, System.currentTimeMillis()),
            )
            db.execSQL("DELETE FROM ocr_failed WHERE id = ?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Remembers a picture that could not be read, for the failure filter. */
    fun markFailed(id: String) {
        // A picture that already has text is not a failure, even if a concurrent
        // second attempt for it went wrong; the index row is the stronger fact.
        helper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO ocr_failed(id, updated) " +
                "SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM ocr WHERE id = ?)",
            arrayOf<Any>(id, System.currentTimeMillis(), id),
        )
    }

    /** Ids that were attempted but could not be decoded or recognized. */
    fun failedIds(): Set<String> {
        val found = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT id FROM ocr_failed", null).use { cursor ->
            while (cursor.moveToNext()) found.add(cursor.getString(0))
        }
        return found
    }

    /** Ids whose recognized text contains [query], interpreted literally. */
    fun search(query: String): Set<String> {
        val pattern = "%" + escapeLike(query) + "%"
        val found = HashSet<String>()
        helper.readableDatabase.rawQuery(
            "SELECT id FROM ocr WHERE text LIKE ? ESCAPE '\\'",
            arrayOf(pattern),
        ).use { cursor -> while (cursor.moveToNext()) found.add(cursor.getString(0)) }
        return found
    }

    /** Every indexed id, for filtering out what a backfill still has to read. */
    fun ids(): Set<String> {
        val found = HashSet<String>()
        helper.readableDatabase.rawQuery("SELECT id FROM ocr", null).use { cursor ->
            while (cursor.moveToNext()) found.add(cursor.getString(0))
        }
        return found
    }

    /** Pictures that were read successfully but contained no text at all. */
    fun emptyIds(): Set<String> {
        val found = HashSet<String>()
        helper.readableDatabase.rawQuery(
            "SELECT id FROM ocr WHERE TRIM(text) = ''",
            null,
        ).use { cursor -> while (cursor.moveToNext()) found.add(cursor.getString(0)) }
        return found
    }

    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM ocr", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    /** Drops the text of pictures that no longer exist, so search cannot find them. */
    fun remove(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            // A large multi-select would otherwise overflow SQLite's bind limit.
            ids.chunked(500).forEach { chunk ->
                val placeholders = chunk.joinToString(",") { "?" }
                db.execSQL("DELETE FROM ocr WHERE id IN ($placeholders)", chunk.toTypedArray())
                db.execSQL("DELETE FROM ocr_failed WHERE id IN ($placeholders)", chunk.toTypedArray())
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clear() {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM ocr")
            db.execSQL("DELETE FROM ocr_failed")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** `LIKE` treats these as wildcards, so a search for them has to escape them. */
    private fun escapeLike(value: String): String = buildString(value.length + 8) {
        value.forEach { ch ->
            when (ch) {
                '\\', '%', '_' -> {
                    append('\\')
                    append(ch)
                }
                else -> append(ch)
            }
        }
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, "ocr.db", null, 2) {
        init {
            // The scanner writes while the gallery can be searching; WAL lets those
            // readers and the writer coexist instead of one waiting on the other.
            setWriteAheadLoggingEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE ocr(" +
                    "id TEXT PRIMARY KEY, " +
                    "text TEXT NOT NULL, " +
                    "updated INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE TABLE ocr_failed(" +
                    "id TEXT PRIMARY KEY, " +
                    "updated INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Never drop the index on upgrade: it is the expensive part of the app.
            if (oldVersion < 2) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ocr_failed(" +
                        "id TEXT PRIMARY KEY, " +
                        "updated INTEGER NOT NULL)",
                )
            }
        }
    }
}
