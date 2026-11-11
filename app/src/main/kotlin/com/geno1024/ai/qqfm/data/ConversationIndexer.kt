package com.geno1024.ai.qqfm.data

import android.content.Context

/**
 * Builds "which conversation is this picture from" out of the two things that can
 * be read on the device: the bytes of `chatraw` and the message database.
 *
 * The chain has three links, and each one is a separate slow pass:
 *
 *  1. hash `chatraw` — its files keep the bytes that were sent, so their md5 is the
 *     md5 the message carries (measured: 55% of the tree, the rest are pictures the
 *     user never sent or that QQ re-encoded on the way in);
 *  2. stream every picture element out of `nt_msg.db` and keep the rows whose md5
 *     the device actually has;
 *  3. read conversation names out of QQ's own group and profile databases.
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
        data class ReadingMessages(val scanned: Long, val matched: Int) : Phase
        data object Naming : Phase
    }

    data class Result(
        /** Pictures in `chatraw`. */
        val files: Int,
        /** Pictures whose md5 is now known. */
        val hashed: Int,
        /** Conversation links stored. */
        val indexed: Int,
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

        // The tree the messages can be matched against; if it was never scanned the
        // scan writes the cache itself, which costs seconds against 116k files.
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

        val matched = intArrayOf(0)
        conversations.rebuildFrom(target, needed, context) { scanned, found ->
            matched[0] = found
            onPhase(Phase.ReadingMessages(scanned, found))
        }

        onPhase(Phase.Naming)
        val peers = conversations.peers().map { it.first }
        conversations.namePeers(target, context, peers)

        return Outcome.Done(
            Result(
                files = items.size,
                hashed = needed.size,
                indexed = matched[0],
                conversations = peers.size,
            ),
        )
    }

    /** md5 → conversation for every indexed picture. */
    fun peersByMd5(): Map<String, String> = conversations.allPeers()

    /** base (file name without `Cache_`) → md5, from the hash cache. */
    fun md5ByBase(): Map<String, String> =
        hashes.load(MediaSource.RAW).mapValues { (_, entry) -> entry.md5 }

    fun conversations(): List<Pair<String, Int>> = conversations.peers()

    fun nameOf(peer: String): String? = conversations.nameOf(peer)

    fun indexed(): Int = conversations.indexed()
}
