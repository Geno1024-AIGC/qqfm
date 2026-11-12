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
 */
class ConversationIndexer(
    private val context: Context,
    private val repository: MediaRepository,
) {

    private val hashes = MediaHashIndex(context)
    private val conversations = ConversationIndex(context)

    /** What the build is doing right now, for a progress line. */
    sealed interface Phase {
        data object Locating : Phase
        data class Hashing(val done: Int, val total: Int) : Phase
        data object ReadingTrees : Phase
        data class ReadingMessages(val scanned: Long, val matched: Int) : Phase
        data class ReadingFiles(val scanned: Long, val matched: Int) : Phase
        data object Naming : Phase
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
    )

    sealed interface Outcome {
        data class Done(val result: Result) : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Runs the whole chain. Blocking: call it from an IO thread.
     *
     * The hash cache survives between runs, so a second build only hashes new or
     * changed files. The message pass is always done in full: QQ rewrites rows and
     * the index has to be able to repair itself, and there is no cheap way to notice
     * that a 16 GB database changed.
     */
    fun build(onPhase: (Phase) -> Unit = {}): Outcome {
        onPhase(Phase.Locating)
        val target = NtdbTool.target()
            ?: return Outcome.Failed("找不到 QQ 的消息数据库（需要 root 权限）")

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
        onPhase(Phase.ReadingTrees)
        val localBases = HashSet<String>(400_000)
        items.mapTo(localBases) { it.base }
        CacheName.TREES.forEach { source ->
            val tree = repository.loadCached(source) ?: repository.scan(source).items
            tree.mapTo(localBases) { it.base }
        }
        val matched = intArrayOf(0)
        val filed = conversations.rebuildFrom(target, needed, localBases, context) { scanned, found ->
            matched[0] = found
            onPhase(Phase.ReadingMessages(scanned, found))
        }

        // A second, independent md5 → conversation table. On a real library this one
        // knows 99,262 of the 116,544 `chatraw` files where the message scan knows
        // 65,948, and they agree about the conversation for 92% of the md5s both hold.
        val fromFiles = intArrayOf(0)
        conversations.addFilePictures(target, needed, context) { scanned, found ->
            fromFiles[0] = found
            onPhase(Phase.ReadingFiles(scanned, found))
        }

        onPhase(Phase.Naming)
        val peers = conversations.peers()
        conversations.namePeers(target, context, peers.map { it.first })

        return Outcome.Done(
            Result(
                files = items.size,
                hashed = needed.size,
                indexed = peers.sumOf { it.second },
                named = filed.fromPath,
                fromNames = filed.fromMd5,
                fromFiles = conversations.fileOnlyCount(),
                conversations = peers.size,
            ),
        )
    }

    /** md5 → conversation for every indexed picture. */
    fun peersByMd5(): Map<String, String> = conversations.allPeers()

    /** A conversation a picture belongs to, and how to show it. */
    data class Conversation(val peer: String, val name: String)

    /**
     * Every indexed picture base (`Cache_` stripped) with the conversation it came
     * from.
     *
     * Resolving a name costs a lookup, so it happens once here; grouping a tree then
     * only has to read a map.
     */
    fun conversationsByBase(): Map<String, Conversation> {
        val names = HashMap<String, String>()
        val out = HashMap<String, Conversation>(200_000)

        fun put(base: String, peer: String) {
            val name = names.getOrPut(peer) { nameOf(peer) ?: peer }
            out[base] = Conversation(peer, name)
        }

        // The name routes cover `chatimg`/`chatthumb` (both the copies a message
        // named itself and the ones QQ named after the md5); the md5 route covers
        // what `chatraw` kept. Where both know a base, the named one is the direct
        // statement, so it wins.
        conversations.allNamed().forEach { (base, peer) -> put(base, peer) }
        val peers = conversations.allPeers()
        if (peers.isNotEmpty()) {
            md5ByBase().forEach { (base, md5) ->
                val peer = peers[md5] ?: return@forEach
                if (base !in out) put(base, peer)
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
}
