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
        /** Links stored by either route. */
        val indexed: Int,
        /** Links stored from a message that named its own cache file. */
        val named: Int,
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

        // Every cache name on the device, so a message's own path can be trusted.
        // The other trees are only read if they were already scanned: making the
        // user wait through two more walks to use this route would cost more than
        // it gives back.
        val localBases = HashSet<String>(200_000)
        items.mapTo(localBases) { it.base }
        listOf(MediaSource.IMG, MediaSource.THUMB).forEach { source ->
            repository.loadCached(source)?.mapTo(localBases) { it.base }
        }

        val matched = intArrayOf(0)
        conversations.rebuildFrom(target, needed, localBases, context) { scanned, found ->
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
                named = conversations.namedCount(),
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

        // The name route covers pictures sent from this phone (any tree); the md5
        // route covers everything else that `chatraw` kept. Where both know a base,
        // the named one is the direct statement, so it wins.
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
