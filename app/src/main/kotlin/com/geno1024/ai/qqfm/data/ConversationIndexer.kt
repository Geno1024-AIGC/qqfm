package com.geno1024.ai.qqfm.data

import android.content.Context

/**
 * Builds "which conversation is this picture from" out of the two things that can
 * be read on the device: the bytes of the pictures and the message database.
 *
 * The chain has five links, each a separate slow pass:
 *
 *  1. hash `chatraw` — its files keep the bytes that were sent, so their md5 is the
 *     md5 the message carries (measured: 55.6% of the tree, the rest are pictures
 *     the user never sent or that QQ re-encoded on the way in);
 *  2. scan `chatimg` and `chatthumb` for their file names, which are derived from
 *     the picture's md5 rather than from its bytes;
 *  3. stream every media element out of `nt_msg.db` and keep the rows that lead to
 *     a file the device has, by md5 or by derived name;
 *  4. read `files_in_chat.db`, QQ's own record of files it handled, which is a second
 *     md5 → conversation map covering the pictures the message scan does not;
 *  5. read conversation names out of QQ's own group and profile databases.
 *
 * Only the pictures that exist locally are stored, so the index stays at a few
 * megabytes instead of the two million plus message rows.
 *
 * A whole build reads ~17 GB and ~16 GB, so it is also *skipped* when nothing it
 * depends on moved: the sizes and timestamps of QQ's two databases and of the
 * app's own listings and hash cache are folded into one token, stored next to the
 * index, and compared before any work starts.
 */
class ConversationIndexer(
    private val context: Context,
    private val repository: MediaRepository,
) {

    private val hashes = MediaHashIndex(context)
    private val conversations = ConversationIndex(context)

    /**
     * What the build is doing right now.
     *
     * Each state carries enough for the about page to fill a progress bar without
     * knowing anything about the database: [Hashing] and [ReadingTrees] count files,
     * [ReadingMessages] reports how far through the message id space it is (the
     * tables are read in id order, and a row total would cost a full 74 s scan),
     * [ReadingFiles] counts rows of a table whose size the last build remembered.
     */
    sealed interface Phase {
        data object Locating : Phase
        data class Hashing(val done: Int, val total: Int) : Phase
        data class ReadingTrees(
            val tree: MediaSource,
            val done: Int,
            /** From the last build; 0 while it is still unknown. */
            val total: Int,
        ) : Phase
        data class ReadingMessages(
            val table: String,
            val scanned: Long,
            val matched: Int,
            /** 0..1 across the whole message pass. */
            val fraction: Float,
        ) : Phase
        data class ReadingFiles(val scanned: Long, val matched: Int, val total: Long) : Phase
        data object Naming : Phase
        /** The cache token matched: nothing was read at all. */
        data object Unchanged : Phase
    }

    data class Result(
        /** Pictures in `chatraw`. */
        val files: Int,
        /** Pictures whose md5 is now known. */
        val hashed: Int,
        /** Links stored by any route. */
        val indexed: Int,
        /** Links stored from a message that named its own cache file. */
        val named: Int,
        /** Links stored because QQ named an `chatimg`/`chatthumb` copy after the md5. */
        val fromNames: Int,
        /** Links only the file table knew. */
        val fromFiles: Int,
        val conversations: Int,
        /** True when the build was skipped because the token still matched. */
        val cached: Boolean = false,
        /** True when the message pass continued from a stored watermark. */
        val resumed: Boolean = false,
    )

    sealed interface Outcome {
        data class Done(val result: Result) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Runs the whole chain. Blocking: call it from an IO thread.
     *
     * Pass [force] to rebuild even when nothing changed; otherwise a matching token
     * returns the numbers the last build reported without touching the device.
     */
    fun build(force: Boolean = false, onPhase: (Phase) -> Unit = {}): Outcome {
        onPhase(Phase.Locating)
        val target = NtdbTool.target()
            ?: return Outcome.Failed("找不到 QQ 的消息数据库（需要 root 权限）")

        val token = token(target)
        if (!force) {
            stored(token)?.let { result ->
                onPhase(Phase.Unchanged)
                return Outcome.Done(result)
            }
        }

        // What the last build measured. The message tables are 2.4 million rows apart
        // in weight, and the progress bar has to know that before this build can
        // count them again.
        val previous = conversations.metaAll()

        // Where the last build stopped in each message table, so this one can
        // continue instead of re-reading years of rows. Empty on a first build (or
        // after a forced one), which is what makes the pass a full scan.
        val resume = previous.mapNotNull { (key, value) ->
            val table = key.removePrefix(WATERMARK_PREFIX)
            if (table == key) null else value.toLongOrNull()?.let { table to it }
        }.toMap()

        /** A number the last build stored, or 0 when this is the first one. */
        fun prior(key: String): Long = previous[key]?.toLongOrNull() ?: 0L

        val items = repository.loadCached(MediaSource.RAW)
            ?: repository.scan(MediaSource.RAW).items
        if (items.isEmpty()) return Outcome.Failed("chatraw 里没有图片")

        val cache = hashes.load(MediaSource.RAW)
        onPhase(Phase.Hashing(0, 0))
        hashes.refresh(MediaSource.RAW, items, cache) { done, total ->
            onPhase(Phase.Hashing(done, total))
        }
        hashes.save(MediaSource.RAW, cache)

        val needed = items.mapNotNullTo(HashSet()) { item -> cache[item.base]?.md5 }
        if (needed.isEmpty()) return Outcome.Failed("还没有任何图片的 md5 可对上消息")

        // Every cache name on the device. `chatraw` is needed for the path route and
        // the other two trees for the derived-name route, so all three are scanned
        // once and cached; a picture the gallery cannot show is a link not worth
        // storing.
        val localBases = HashSet<String>(400_000)
        items.mapTo(localBases) { it.base }
        val treeSizes = HashMap<String, Int>(4)
        CacheName.TREES.forEach { source ->
            val prior = previous["tree.${source.id}"]?.toIntOrNull() ?: 0
            val tree = repository.loadCached(source) ?: repository.scan(source) { done ->
                onPhase(Phase.ReadingTrees(source, done, prior))
            }.items
            tree.mapTo(localBases) { it.base }
            treeSizes[source.id] = tree.size
        }

        val bounds = NtdbTool.messageBounds(context, target)
        val order = listOf("group_msg_table", "c2c_msg_table").filter { it in bounds }
        // Where each table's scan starts: its first row, or the message id an earlier
        // build stopped at.
        val starts = order.associateWith { table ->
            val span = bounds.getValue(table)
            (resume[table] ?: span.first).coerceIn(span.first, span.last)
        }
        val weights = tableWeights(previous, bounds, starts)
        val weightSum = weights.values.sum().takeIf { it > 0 } ?: 1.0
        val weightBefore = HashMap<String, Double>(4)
        run {
            var accumulated = 0.0
            for (table in order) {
                weightBefore[table] = accumulated
                accumulated += weights[table] ?: 0.0
            }
        }

        val filed = conversations.rebuildFrom(
            target, needed, localBases, context, resume,
        ) { scanned, found, table, msgid ->
            // The reader ends the stream with an empty table name: the pass is over.
            if (table.isEmpty()) {
                onPhase(Phase.ReadingMessages("", scanned, found, 1f))
            } else {
                val span = bounds[table]
                val start = starts[table]
                val within = if (span != null && start != null && span.last > start) {
                    ((msgid - start).toDouble() / (span.last - start)).coerceIn(0.0, 1.0)
                } else {
                    1.0
                }
                val fraction = (weightBefore[table] ?: 0.0) + within * (weights[table] ?: 0.0)
                onPhase(
                    Phase.ReadingMessages(
                        table = table,
                        scanned = scanned,
                        matched = found,
                        fraction = (fraction / weightSum).toFloat().coerceIn(0f, 1f),
                    ),
                )
            }
        }

        // A second, independent md5 → conversation table. On a real library this one
        // knows 84,632 of the 100,875 `chatraw` files where the message scan knows
        // 56,112, and they agree about the conversation for 92% of the md5s both hold.
        val priorFileRows = previous["scan.files"]?.toLongOrNull() ?: 0L
        var filesScanned = 0L
        conversations.addFilePictures(target, needed, context) { scanned, found ->
            filesScanned = scanned
            onPhase(Phase.ReadingFiles(scanned, found, priorFileRows))
        }

        onPhase(Phase.Naming)
        val peers = conversations.peers()
        // Senders share the name table with the conversations: both are uids, and the
        // gallery shows them side by side ("from <group> · <nickname>").
        conversations.namePeers(target, context, peers.map { it.first } + conversations.senders())

        val result = Result(
            files = items.size,
            hashed = needed.size,
            indexed = peers.sumOf { it.second },
            // `named` only knows the names this run discovered; the rows filed by
            // earlier runs are still in the table, so their counts carry over.
            named = filed.fromPath + (if (resume.isEmpty()) 0 else prior("result.named").toInt()),
            fromNames = filed.fromMd5 + (if (resume.isEmpty()) 0 else prior("result.fromNames").toInt()),
            fromFiles = conversations.fileOnlyCount(),
            conversations = peers.size,
            resumed = resume.isNotEmpty(),
        )
        conversations.putMeta(
            buildMap {
                put("build.token", token)
                put("result.files", result.files.toString())
                put("result.hashed", result.hashed.toString())
                put("result.indexed", result.indexed.toString())
                put("result.named", result.named.toString())
                put("result.fromNames", result.fromNames.toString())
                put("result.fromFiles", result.fromFiles.toString())
                put("result.conversations", result.conversations.toString())
                put("scan.files", filesScanned.toString())
                // Row counts accumulate for the same reason, and only ever grow: they
                // exist to weigh one table against the other on the bar.
                filed.rowsByTable.forEach { (table, rows) ->
                    put("rows.$table", ((if (resume.isEmpty()) 0 else prior("rows.$table")) + rows).toString())
                }
                filed.watermark.forEach { (table, at) ->
                    put("$WATERMARK_PREFIX$table", at.toString())
                }
                treeSizes.forEach { (id, size) -> put("tree.$id", size.toString()) }
            },
        )
        return Outcome.Done(result)
    }

    /**
     * How the message tables weigh against each other for the progress bar.
     *
     * Row counts from the last build are exact. On the first build they are unknown —
     * counting them costs a full decrypt-and-scan — so the split measured on a real
     * library stands in, and is replaced by the real numbers as soon as this build
     * finishes. Only the stretch still to be read weighs in, so a table that is
     * already caught up does not hold a share of the bar it will never fill.
     */
    private fun tableWeights(
        previous: Map<String, String>,
        bounds: Map<String, LongRange>,
        starts: Map<String, Long>,
    ): Map<String, Double> {
        val rows = starts.keys.associateWith { previous["rows.$it"]?.toLongOrNull() ?: 0L }
        if (rows.values.none { it > 0 }) {
            return mapOf("group_msg_table" to 0.96, "c2c_msg_table" to 0.04)
        }
        return rows.mapValues { (table, count) ->
            val span = bounds[table] ?: return@mapValues count.toDouble()
            val full = (span.last - span.first).coerceAtLeast(1).toDouble()
            val left = (span.last - (starts[table] ?: span.first)).coerceAtLeast(0).toDouble()
            count.toDouble() * (left / full)
        }
    }

    /**
     * One string that changes whenever something the index is built from changes:
     * QQ's two databases (and their write-ahead logs, which move first), this app's
     * cached tree listings, and the md5 cache.
     */
    private fun token(target: NtdbTool.Target): String {
        val db = target.dbPath
        val paths = listOf(
            db,
            "$db-wal",
            "${target.dbDir}/files_in_chat.db",
            "${target.dbDir}/files_in_chat.db-wal",
        )
        val sizes = RootShell.exec(
            "stat -c '%s:%Y' -- " + paths.joinToString(" ") { RootShell.shellQuote(it) } + " 2>/dev/null",
        ).output.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(",")
        return buildString {
            append("v$INDEX_VERSION|").append(sizes)
            append("|raw:").append(repository.stamp(MediaSource.RAW))
            CacheName.TREES.forEach { append('|').append(it.id).append(':').append(repository.stamp(it)) }
            append("|hash:").append(hashes.stamp(MediaSource.RAW))
        }
    }

    /** The result a build with [token] produced, or null when that was another build. */
    private fun stored(token: String): Result? {
        val meta = conversations.metaAll()
        if (meta["build.token"] != token) return null
        // A cleared index leaves the token behind; the counts are what say whether
        // there is anything to reuse.
        val indexed = meta["result.indexed"]?.toIntOrNull() ?: return null
        if (indexed <= 0) return null
        return Result(
            files = meta["result.files"]?.toIntOrNull() ?: 0,
            hashed = meta["result.hashed"]?.toIntOrNull() ?: 0,
            indexed = indexed,
            named = meta["result.named"]?.toIntOrNull() ?: 0,
            fromNames = meta["result.fromNames"]?.toIntOrNull() ?: 0,
            fromFiles = meta["result.fromFiles"]?.toIntOrNull() ?: 0,
            conversations = meta["result.conversations"]?.toIntOrNull() ?: 0,
            cached = true,
        )
    }

    /** md5 → conversation for every indexed picture. */
    fun peersByMd5(): Map<String, String> =
        conversations.allPeers().mapValues { (_, link) -> link.peer }

    /**
     * A conversation a picture belongs to, and how to show it.
     *
     * [sender] is the uid that sent the message this link came from, `senderName` the
     * nickname for it. Both are empty when only the file table knew the picture, and
     * `senderName` is empty when no nickname for that uid is on the device.
     */
    data class Conversation(
        val peer: String,
        val name: String,
        val sender: String = "",
        val senderName: String = "",
    )

    /**
     * Every indexed picture base (`Cache_` stripped) with the conversation it came
     * from.
     *
     * Resolving a name costs a lookup, so it happens once here; grouping a tree then
     * only has to read a map.
     */
    fun conversationsByBase(): Map<String, Conversation> {
        val names = HashMap<String, String>()
        val out = HashMap<String, Conversation>(400_000)

        fun put(base: String, link: ConversationIndex.Link) {
            val peer = link.peer
            val name = names.getOrPut(peer) { nameOf(peer) ?: peer }
            val senderName = if (link.sender.isEmpty()) {
                ""
            } else {
                names.getOrPut(link.sender) { nameOf(link.sender) ?: "" }
            }
            out[base] = Conversation(peer, name, link.sender, senderName)
        }

        // The name routes cover `chatimg`/`chatthumb` (both the copies a message
        // named itself and the ones QQ named after the md5); the md5 route covers
        // what `chatraw` kept. Where both know a base, the named one is the direct
        // statement, so it wins.
        conversations.allNamed().forEach { (base, link) -> put(base, link) }
        val peers = conversations.allPeers()
        if (peers.isNotEmpty()) {
            md5ByBase().forEach { (base, md5) ->
                val link = peers[md5] ?: return@forEach
                if (base !in out) put(base, link)
            }
        }
        return out
    }

    /** base (file name without `Cache_`) → md5, from the hash cache. */
    fun md5ByBase(): Map<String, String> =
        hashes.load(MediaSource.RAW).mapValues { (_, entry) -> entry.md5 }

    fun conversations(): List<Pair<String, Int>> = conversations.peers()

    fun nameOf(peer: String): String? = conversations.nameOf(peer)

    fun indexed(): Int = conversations.indexed()

    private companion object {
        /**
         * Bumped when the meaning of a stored link changes, so an old index is
         * rebuilt instead of being trusted by the cache token. Version 2 adds the
         * sender to the name route.
         */
        const val INDEX_VERSION = 2

        /**
         * Meta key prefix for "this table was read up to this message id". The suffix
         * is the table name, so `wm.group_msg_table` sorts next to `rows.…`.
         */
        const val WATERMARK_PREFIX = "wm."
    }
}
