// QQ lives under /data/user/0/... and root reaches it by absolute path —
// Context.getFilesDir() would point at *our* data, which is never what this
// file wants, so the hard-coded paths are the point. When this shell cannot
// see a path a root shell can, the probe reports the break instead of guessing.
@file:SuppressLint("SdCardPath")

package com.geno1024.ai.qqfm.data

import android.annotation.SuppressLint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 0 of "classify gallery items by conversation": a read-only probe that
 * proves QQ's message database (nt_msg.db) can be located, keyed and decrypted
 * **in place** — no copy of the multi-gigabyte file, no write to anything QQ owns.
 *
 * What the community has established for QQ NT (9.0.65–9.1.60):
 *   path  = /data/user/0/com.tencent.mobileqq/databases/nt_db/nt_qq_<h>/nt_msg.db
 *           where h = md5(md5(nt_uid) + "nt_kernel"), nt_uid names a file in
 *           com.tencent.mobileqq/files/uid/ as `<uin>###<nt_uid>`
 *   key   = md5(md5(nt_uid) + rand), where `rand` is a short printable string in
 *           the file's 1024-byte custom header, right after the "QQ_NT DB" marker
 *   codec = SQLCipher: PBKDF2-HMAC-SHA512 over the ASCII key and a 16-byte salt →
 *           AES-256-CBC; each page ends in [IV 16][HMAC][pad] = round_up(16+hmac,16)
 *           reserve bytes (48 for HMAC-SHA1/256, 80 for SHA-512), kdf_iter 4000
 *           (SQLCipher 3's default 64000 and 4's 256000 kept as fallbacks).
 *
 * Two file shapes fit "custom header then cipher stream", so both are candidates:
 *   OFFSET_VFS — QQ's 1024-byte header lives outside SQLite's view (the Phase-1
 *           VFS skips it): salt = file[1024..1040), page 1 cipher starts at 1040,
 *           page N sits at 1024 + (N-1)*page_size
 *   PLAIN_HEAD — cipher_plaintext_header_size 1024: the header *is* page 1's
 *           plaintext part, salt = file[0..16), cipher region = file[1024 ..),
 *           page N sits at (N-1)*page_size
 *
 * None of that is verified against this device's QQ version, so the probe treats
 * every piece as a candidate: printable header strings are tried as `rand`, the
 * uid hash and the bare uin are tried as the key prefix, salt source × file shape
 * × page geometry is a small grid, and SQLCipher's own default KDF counts are a
 * last-resort pass. Java's PBKDF2 password encoding has differed between
 * providers, so a known-answer test first picks whichever implementation on this
 * device matches SQLCipher's ASCII semantics. A candidate is accepted only when
 * the decrypted bytes look like a real SQLite header — the magic itself sits
 * *behind* the salt and never reaches the file, so page size, the two format
 * version bytes and the text encoding stand in for it (odds of a wrong key
 * passing: roughly 1e-10).
 *
 * On success the schema is walked straight off the decrypted pages: every table
 * in sqlite_master gets named, which is the evidence Phase 1 — a full reader with
 * a 1024-offset VFS, built as a native tool and run under root — needs before any
 * NDK work starts.
 */
object NtdbProbe {

    private fun q(value: String) = RootShell.shellQuote(value)

    /** Runs the whole probe off the main thread; returns the report as text. */
    suspend fun run(): String = withContext(Dispatchers.IO) {
        val out = StringBuilder()
        val startedAt = System.currentTimeMillis()
        fun say(line: String = "") { out.append(line).append('\n') }
        fun stamp() = "${System.currentTimeMillis() - startedAt} ms"

        say("=== nt_msg.db 原地读取探针 ===")
        say(
            RootShell.exec("dumpsys package com.tencent.mobileqq | grep -m1 versionName 2>/dev/null")
                .output.lineSequence().firstOrNull { "versionName" in it }?.trim()
                ?.let { "QQ：$it" } ?: "QQ：版本未知",
        )

        // The documented layout, hand-confirmed on this device — no scanning.
        // One run's su saw this exact path missing while a root shell did not,
        // so a miss gets diagnosed (which chain element broke, root or not)
        // instead of being papered over by a search.
        val dataDir = "/data/user/0/com.tencent.mobileqq"
        val dbRoot = "$dataDir/databases/nt_db"
        val uidDir = "$dataDir/files/uid"
        fun dbPathFor(ntUid: String): String =
            "$dbRoot/nt_qq_${md5(md5(ntUid) + "nt_kernel")}/nt_msg.db"
        say("root：" + RootShell.exec("id").output.trim())

        // ---- locate the message database (independent of uid) ----
        // Multiple accounts mean multiple nt_msg.db; the docs say to pick by file
        // mtime, so `ls -t` decides.
        val present = RootShell.exec(
            "ls -t -- ${q(dbRoot)}/nt_qq_*/nt_msg.db 2>/dev/null | head -5",
        ).output.lines().filter(String::isNotBlank)
        say("nt_msg.db：${present.joinToString(" ").ifEmpty { "无" }}")
        if (present.isEmpty()) {
            say("❌ 没有 nt_msg.db，停止。")
            // One ls over the path chain: whichever line says "No such file" is
            // the element this shell cannot see. stderr is kept on purpose.
            say(
                "路径链：" +
                    RootShell.exec(
                        "ls -ld -- /data/user /data/user/0 ${q(dataDir)} " +
                            "${q(dataDir)}/databases ${q(dbRoot)}",
                    ).output.trim().ifEmpty { "（读不到）" },
            )
            say(
                "/data/user/0：" +
                    RootShell.exec("ls -1 -- /data/user/0 | head -20")
                        .output.trim().ifEmpty { "（读不到）" },
            )
            say(
                "databases：" +
                    RootShell.exec("ls -1 -- ${q(dataDir)}/databases | head -20")
                        .output.trim().ifEmpty { "（读不到）" },
            )
            return@withContext out.toString()
        }
        val dbPath = present.first()
        if (present.size > 1) say("⚠️ 发现 ${present.size} 个 nt_msg.db，取第一个")
        say("db = $dbPath")
        say(
            RootShell.exec(
                "stat -c '%n %s bytes' -- " +
                    listOf(dbPath, "$dbPath-wal", "$dbPath-shm").joinToString(" ") { q(it) } +
                    " 2>/dev/null",
            ).output.trim(),
        )

        // ---- the head: 1024-byte custom header + as many page-size candidates as fit ----
        val head = RootShell.readBytesAt(dbPath, 0L, HEAD_LIMIT)
        if (head == null || head.size < CUSTOM_HEADER + 64) {
            say("❌ 读取文件头失败（${head?.size ?: 0} 字节），停止。")
            return@withContext out.toString()
        }
        say("读到文件头 ${head.size} 字节（${stamp()}）")
        say(
            "文件起始 16 字节：${hex(head, KDF_SALT)}" +
                (if (indexOf(head, MAGIC) == 0) "（是 SQLite magic → 明文头形态）" else "（非 magic → 被 salt/自定义头覆盖）"),
        )
        val header = head.copyOfRange(0, CUSTOM_HEADER)
        val marker = indexOf(header, MARKER)
        say("QQ_NT DB 标记：${if (marker >= 0) "@$marker" else "未找到"}")
        say("头内算法串：${HMAC_ALGOS.firstOrNull { containsStr(header, it) } ?: "未见"}")
        val runs = printableRuns(header)
        say("头内字符串：${runs.joinToString(" ") { it.text }}")
        // Nearest-after-marker first — that is where the docs put `rand` — then the
        // rest as slack, deduplicated by the set and trimmed to keep the search cheap.
        val ordered = LinkedHashSet<String>()
        if (marker >= 0) runs.filter { it.at > marker }.forEach { ordered += it.text }
        runs.forEach { ordered += it.text }
        val randCandidates = ordered.filter { candidate ->
            candidate.length in 4..24 && HMAC_ALGOS.none { candidate.startsWith(it) }
        }.take(24)
        say("rand 候选 ${randCandidates.size}：${randCandidates.joinToString(" ").ifEmpty { "无" }}")

        // ---- locate nt_uid: three independent sources, each reported verbatim ----
        // The last run stopped at "未找到" with no clue why, so every source keeps
        // its raw output (errors included) and a miss does not abort before the
        // other sources and the header have had their say.
        val uidDirLs = RootShell.exec("ls -1 -- ${q(uidDir)}").output
            .lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        say("uid/ 目录：${uidDirLs.joinToString(" ").ifEmpty { "（空）" }}")
        val fromDirNames = if (uidDirLs.any { "###" in it }) {
            uidDirLs.filter { "###" in it }
        } else {
            RootShell.exec(
                "find ${q(dataDir)}/files -maxdepth 4 " +
                    "-name '*###*' -print 2>/dev/null | head -5",
            ).output.lineSequence().map { it.substringAfterLast('/') }
                .filter { "###" in it }.toList()
        }
        // The channel database is named gpro_v1-6_<nt_uid>.db — uid in plain sight.
        val fromGpro = RootShell.exec(
            "ls -1 -- ${q(dbRoot)}/nt_qq_*/gpro_v1-6_*.db 2>/dev/null | head -5",
        ).output.lineSequence().map(String::trim)
            .mapNotNull { GPRO_UID.find(it)?.groupValues?.get(1) }
            .map { uid -> Account("", uid, dbPathFor(uid)) }.toList()
        // beacon_db carries "home_uin":…,"uid":… pairs as plain text.
        val beaconPath = "$dataDir/databases/beacon_db_com.tencent.mobileqq"
        val beaconHits = RootShell.exec(
            "grep -oE '\"home_uin\": *\"[0-9]+\"|\"uid\": *\"u_[A-Za-z0-9_-]+\"' " +
                "${q(beaconPath)} | head -6",
        ).output.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        say("beacon_db：${beaconHits.joinToString(" ").ifEmpty { "未见 uid（文件可能不存在）" }}")

        val fromBeacon = ArrayList<Account>()
        var pendingUin: String? = null
        for (hit in beaconHits) {
            val uin = BEACON_UIN.find(hit)?.groupValues?.get(1)
            if (uin != null) {
                pendingUin = uin
                continue
            }
            val uid = BEACON_UID.find(hit)?.groupValues?.get(1)
            val previousUin = pendingUin
            if (uid != null && previousUin != null) {
                fromBeacon += Account(previousUin, uid, dbPathFor(uid))
                pendingUin = null
            }
        }

        val accounts = (fromDirNames.mapNotNull { name ->
            val parts = name.split("###", limit = 2)
            if (parts.size == 2 && parts[0].all { it.isDigit() }) {
                Account(parts[0], parts[1], dbPathFor(parts[1]))
            } else {
                null
            }
        } + fromGpro + fromBeacon).distinctBy { it.ntUid }

        if (accounts.isEmpty() || randCandidates.isEmpty()) {
            if (accounts.isEmpty()) {
                say("❌ 三路来源都没拿到 nt_uid（uid 目录 / gpro 文件名 / beacon_db，见上）。")
                say("   rand 已解析：${randCandidates.firstOrNull() ?: "无"}——补上任一来源即可继续。")
            }
            if (randCandidates.isEmpty()) say("❌ 头里没有可用的 rand 候选，停止。")
            return@withContext out.toString()
        }

        // The directory name is derived from the account, so a uid whose predicted
        // path is the file we found is the one whose key we want.
        val matched = accounts.firstOrNull { it.path in present }
        if (matched == null) {
            say("⚠️ 没有 uid 推导出的路径与实际 db 吻合（公式/uid 不匹配），仍按发现的文件继续")
        }
        accounts.forEach { acc ->
            say(
                "nt_uid = ${acc.ntUid}（uin ${acc.uin.ifEmpty { "?" }}） " +
                    "md5=${md5(acc.ntUid)} ${if (acc.path in present) "→ ✓ 就是该 db" else ""}",
            )
        }

        // ---- derive and try: every assumption is a loop until the header agrees ----
        // SQLCipher feeds PBKDF2 the ASCII bytes of the key string; Java's provider
        // has not always encoded passwords that way, so a known-answer test picks
        // the matching implementation before anything real is attempted.
        val nativeKdf = runCatching {
            hex(pbkdf2Native(KAT_PASS, KAT_SALT, KDF_NORMAL), AES_BITS / 8) == KAT_DK
        }.getOrDefault(false)
        say("PBKDF2 自检：${if (nativeKdf) "通过（系统提供者）" else "不符 → 改用手动 RFC 2898 实现"}")
        fun derive(keyHex: String, salt: ByteArray, iterations: Int): ByteArray =
            if (nativeKdf) pbkdf2Native(keyHex, salt, iterations)
            else pbkdf2Manual(keyHex.toByteArray(Charsets.US_ASCII), salt, iterations, AES_BITS / 8)

        // Every account contributes both key-prefix shapes the docs mention:
        // md5(nt_uid) (the documented one) and the bare uin.
        val prefixes = accounts.flatMap { listOf(md5(it.ntUid), it.uin) }
            .filter { it.isNotEmpty() }.distinct()
        // reserve = round_up(16 + hmac, 16): 48 for HMAC-SHA1/SHA256, 80 for
        // SHA-512, 16 when HMAC is off — the impossible 36 is deliberately absent.
        val geometries = listOf(
            4096 to 48, 4096 to 80, 4096 to 16,
            8192 to 48, 8192 to 80, 8192 to 16,
            2048 to 48, 2048 to 80, 2048 to 16,
            1024 to 48, 1024 to 80, 1024 to 16,
        )
        // Salt source × file shape: the two standard pairings first, then the
        // cross pairings in case QQ overrides the salt out of band.
        val saltStream = head.copyOfRange(CUSTOM_HEADER, CUSTOM_HEADER + KDF_SALT)
        val saltHead = header.copyOfRange(0, KDF_SALT)
        val combos = buildList {
            add(Combo("流起点", saltStream, Layout.OFFSET_VFS))
            add(Combo("文件头", saltHead, Layout.PLAIN_HEAD))
            if (indexOf(head, MAGIC) == 0) add(Combo("magic", MAGIC.copyOf(), Layout.PLAIN_HEAD))
            add(Combo("文件头", saltHead, Layout.OFFSET_VFS))
            add(Combo("流起点", saltStream, Layout.PLAIN_HEAD))
        }
        val derived = HashMap<String, ByteArray>()
        var tries = 0
        fun attempt(combo: Combo, prefix: String, rand: String, pageSize: Int, reserve: Int, kdf: Int): Pair<Hit, ByteArray>? {
            val key = md5(prefix + rand)
            val aes = derived.getOrPut("${combo.saltTag}@$key@$kdf") { derive(key, combo.salt, kdf) }
            tries++
            val page = tryPage1(head, combo.layout, pageSize, reserve, aes) ?: return null
            return Hit(key, rand, combo.saltTag, combo.layout, pageSize, reserve, kdf) to page
        }

        fun pass(
            kdf: Int,
            rands: List<String>,
            tryPrefixes: List<String>,
            tryCombos: List<Combo> = combos,
            tryGeometries: List<Pair<Int, Int>> = geometries,
        ): Pair<Hit, ByteArray>? {
            for (combo in tryCombos) {
                for ((pageSize, reserve) in tryGeometries) {
                    for (prefix in tryPrefixes) {
                        for (rand in rands) {
                            attempt(combo, prefix, rand, pageSize, reserve, kdf)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        var found = pass(KDF_NORMAL, randCandidates, prefixes)
        // SQLCipher's own default KDF counts, in case this build never set kdf_iter
        // to 4000 — narrowed so a fallback cannot turn into minutes of hashing.
        if (found == null) found = pass(KDF_SQL3, randCandidates.take(8), prefixes.take(1))
        if (found == null) found = pass(KDF_LEGACY, randCandidates.take(8), prefixes.take(1))
        if (found == null) {
            say("❌ 尝试 $tries 次、${derived.size} 个派生密钥都没得到合法的 SQLite 头。")
            combos.firstOrNull()?.let { combo ->
                decryptPage1Body(head, combo.layout, 4096, 48, derived.values.firstOrNull() ?: ByteArray(32))?.let {
                    say("首候选（${combo.saltTag}/${combo.layout.tag}，4096/48）解密前 32 字节：${hex(it, 32)}")
                }
            }
            say("结论：此 QQ 版本的密钥推导或头布局与已知不符，Phase 1 前需换取密钥方式。耗时 ${stamp()}")
            return@withContext out.toString()
        }
        val (hit, page1) = found
        say("✅ 第 1 页解密成功（${stamp()}）：salt=${hit.saltTag} 形态=${hit.layout.tag} " +
            "rand=${hit.rand} page=${hit.pageSize} reserve=${hit.reserve} kdf=${hit.kdf}")
        say("key = ${hit.key}")

        // ---- what the plaintext header says ----
        val declared = u16(page1, 16).let { if (it == 1) 65536 else it }
        val reserved = page1[20].toInt() and 0xFF
        val writeVersion = page1[19].toInt() and 0xFF
        say(
            "SQLite 头：page=$declared reserved=$reserved " +
                (if (writeVersion == 2) "WAL" else "journal") +
                " 页数=${u32(page1, 28)} 文本编码=${u32(page1, 56)}",
        )
        if (writeVersion == 2) {
            say("WAL 模式：新写入先落 -wal；探针只读主文件（schema 足够），Phase 1 要连 -wal 一起读。")
        }
        if (declared != hit.pageSize) {
            say("⚠️ 声明页大小 $declared 与解密假设 ${hit.pageSize} 不一致")
        }
        if (reserved != hit.reserve) say("⚠️ 头里 reserved=$reserved，与假设 ${hit.reserve} 不同")

        // ---- walk sqlite_master off the decrypted pages ----
        val aes = derived.getValue("${hit.saltTag}@${hit.key}@${hit.kdf}")
        val tables = LinkedHashMap<String, Long>()
        var others = 0
        var pagesRead = 0
        try {
            val queue = ArrayDeque<Int>()
            queue.addLast(1)
            val seen = HashSet<Int>()
            while (queue.isNotEmpty() && pagesRead < MAX_SCHEMA_PAGES) {
                val pgno = queue.removeFirst()
                if (!seen.add(pgno)) continue
                val page = if (pgno == 1) {
                    page1
                } else {
                    fetchPage(dbPath, hit.layout, pgno, hit.pageSize, hit.reserve, aes)
                }
                if (page == null) {
                    say("  第 $pgno 页读取/解密失败")
                    continue
                }
                pagesRead++
                val off = if (pgno == 1) 100 else 0
                if (page.size < off + 8) continue
                when (page[off].toInt() and 0xFF) {
                    LEAF_TABLE -> parseLeafTables(page, off).forEach { row ->
                        if (row.type == "table") {
                            tables.putIfAbsent(row.name, row.rootpage)
                        } else {
                            others++
                        }
                    }
                    INTERIOR_TABLE -> interiorChildren(page, off).forEach { queue.addLast(it) }
                    else -> say("  第 $pgno 页类型 ${page[off].toInt() and 0xFF}（非表页），跳过")
                }
            }
            if (queue.isNotEmpty()) say("  （schema 超过 $MAX_SCHEMA_PAGES 页，余下未读）")
        } catch (e: Exception) {
            say("  schema 解析中断：${e.javaClass.simpleName}: ${e.message}")
        }
        say("sqlite_master 表 ${tables.size} 张（读了 $pagesRead 页${if (others > 0) "，另 $others 条索引/其他" else ""}）：")
        tables.forEach { (name, root) ->
            say("  $name  root=${if (root >= 0) root else "?"}")
        }
        if (tables.isEmpty()) {
            say("❌ 第 1 页解开了，但没能解析出任何表——报告此结果以便修正解析。")
        } else {
            say("✅ 探针通过：密钥与原地解密路径可用，Phase 1（native 全量读取器）可开工。总耗时 ${stamp()}")
        }
        out.toString()
    }

    // ---- locating ----

    private data class Account(val uin: String, val ntUid: String, val path: String)

    // ---- crypto ----

    /**
     * Where SQLCipher's view of the file begins. QQ is known in two shapes and
     * neither has been confirmed on this device, so the probe tries both.
     */
    private enum class Layout(val tag: String) {
        /** A custom VFS skips QQ's 1024-byte header; the cipher stream starts at 1024. */
        OFFSET_VFS("流@1024"),

        /** cipher_plaintext_header_size 1024: the header is page 1's plaintext part. */
        PLAIN_HEAD("明文头@0"),
    }

    private data class Combo(val saltTag: String, val salt: ByteArray, val layout: Layout)

    private data class Hit(
        val key: String,
        val rand: String,
        val saltTag: String,
        val layout: Layout,
        val pageSize: Int,
        val reserve: Int,
        val kdf: Int,
    )

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** SQLCipher's KDF over the ASCII key string and the file salt. */
    private fun pbkdf2Native(keyHex: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(keyHex.toCharArray(), salt, iterations, AES_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /**
     * RFC 2898 PBKDF2-HMAC-SHA512 over raw bytes — the reference semantics SQLCipher
     * (OpenSSL) uses, with no provider-specific password encoding in the way.
     */
    private fun pbkdf2Manual(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val hLen = 64
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (block in 1..blocks) {
            mac.update(salt)
            var u = mac.doFinal(
                byteArrayOf(
                    (block ushr 24).toByte(), (block ushr 16).toByte(),
                    (block ushr 8).toByte(), block.toByte(),
                ),
            )
            val t = u.copyOf()
            for (i in 1 until iterations) {
                u = mac.doFinal(u)
                for (j in t.indices) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (block - 1) * hLen, t.size)
        }
        return out.copyOf(dkLen)
    }

    private fun aesCbcDecrypt(buf: ByteArray, from: Int, to: Int, ivAt: Int, aes: ByteArray): ByteArray? =
        runCatching {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), IvParameterSpec(buf, ivAt, 16))
            cipher.doFinal(buf, from, to - from)
        }.getOrNull()

    /**
     * Decrypts page 1's body and rebuilds the usable page.
     *
     * The magic "SQLite format 3" is what the salt overwrote (OFFSET_VFS) or what
     * QQ's own header replaced (PLAIN_HEAD), so it cannot be read back and cannot
     * be checked; what survives — page size, write/read format versions, text
     * encoding — pins the header down well enough that a wrong key essentially
     * never passes.
     */
    private fun tryPage1(head: ByteArray, layout: Layout, pageSize: Int, reserve: Int, aes: ByteArray): ByteArray? {
        val body = decryptPage1Body(head, layout, pageSize, reserve, aes) ?: return null
        if (body.size < 60) return null
        val page = when (layout) {
            Layout.OFFSET_VFS -> MAGIC + body
            Layout.PLAIN_HEAD -> head.copyOfRange(0, CUSTOM_HEADER) + body
        }
        val declared = u16(page, 16).let { if (it == 1) 65536 else it }
        if (declared != pageSize) return null
        if (page[18].toInt() !in 1..2 || page[19].toInt() !in 1..2) return null
        if (u32(page, 56) !in 1L..3L) return null
        return page
    }

    private fun decryptPage1Body(head: ByteArray, layout: Layout, pageSize: Int, reserve: Int, aes: ByteArray): ByteArray? {
        // Cipher region of page 1: after the salt (OFFSET_VFS) or after QQ's
        // plaintext header (PLAIN_HEAD), ending where the IV trailer begins.
        val from = when (layout) {
            Layout.OFFSET_VFS -> CUSTOM_HEADER + SALT
            Layout.PLAIN_HEAD -> CUSTOM_HEADER
        }
        val to = when (layout) {
            Layout.OFFSET_VFS -> CUSTOM_HEADER + pageSize - reserve
            Layout.PLAIN_HEAD -> pageSize - reserve
        }
        if (to - from < 60 || to > head.size) return null
        return aesCbcDecrypt(head, from, to, to, aes)
    }

    /**
     * Reads and decrypts a later page straight from the file: one `su`, one read.
     *
     * Pages past the first carry no salt — the whole page minus the reserve is
     * one CBC region with its IV at the end. Only the file offset of the page
     * differs between the two shapes.
     */
    private fun fetchPage(dbPath: String, layout: Layout, pgno: Int, pageSize: Int, reserve: Int, aes: ByteArray): ByteArray? {
        val offset = when (layout) {
            Layout.OFFSET_VFS -> CUSTOM_HEADER.toLong() + (pgno - 1L) * pageSize
            Layout.PLAIN_HEAD -> (pgno - 1L) * pageSize
        }
        val raw = RootShell.readBytesAt(dbPath, offset, pageSize) ?: return null
        if (raw.size < pageSize) return null
        return aesCbcDecrypt(raw, 0, pageSize - reserve, pageSize - reserve, aes)
    }

    // ---- sqlite page / record parsing (schema tables only) ----

    private data class SchemaRow(val type: String, val name: String, val rootpage: Long)

    private fun parseLeafTables(page: ByteArray, off: Int): List<SchemaRow> {
        val count = u16(page, off + 3)
        val rows = ArrayList<SchemaRow>(count)
        for (i in 0 until count) {
            runCatching {
                var cursor = u16(page, off + 8 + i * 2)
                val payloadLen = varint(page, cursor).first
                cursor = varint(page, cursor).second
                cursor = varint(page, cursor).second // rowid, consumed but unused
                // A table leaf keeps the payload local unless it overflows; when it
                // does, the first columns (type, name, rootpage) still fit locally,
                // which is all this walk needs.
                val usable = page.size
                val x = usable - 35
                val local: Long = if (payloadLen <= x) {
                    payloadLen
                } else {
                    val m = ((usable - 12) * 32 / 255) - 23
                    val k = m + ((payloadLen - m) % (usable - 4))
                    if (k <= x) k else m.toLong()
                }
                if (local <= 0 || cursor + local > page.size) return@runCatching
                val payload = page.copyOfRange(cursor, cursor + local.toInt())
                parseRecord(payload)?.let { rows += it }
            }
        }
        return rows
    }

    private fun interiorChildren(page: ByteArray, off: Int): List<Int> {
        if (page.size < off + 12) return emptyList()
        val count = u16(page, off + 3)
        val children = ArrayList<Int>(count + 1)
        for (i in 0 until count) {
            runCatching {
                val cell = u16(page, off + 12 + i * 2)
                if (cell + 4 <= page.size) children += u32(page, cell).toInt()
            }
        }
        runCatching {
            val rightmost = u32(page, off + 8).toInt()
            if (rightmost > 0 && rightmost !in children) children += rightmost
        }
        return children
    }

    /** Reads the first columns of one sqlite_master record; overflow tails are skipped. */
    private fun parseRecord(payload: ByteArray): SchemaRow? {
        var step = varint(payload, 0)
        val headerLen = step.first.toInt()
        if (headerLen < 1 || headerLen > payload.size) return null
        var at = step.second
        val types = ArrayList<Int>(5)
        while (at < headerLen) {
            step = varint(payload, at)
            at = step.second
            types += step.first.toInt()
        }
        if (types.size < 4) return null
        var data = headerLen
        var type: String? = null
        var name: String? = null
        var rootpage = -1L
        for ((index, serial) in types.withIndex()) {
            val size = serialSize(serial)
            if (data + size > payload.size) break
            when {
                index == 0 && isText(serial) -> type = String(payload, data, size, Charsets.UTF_8)
                index == 1 && isText(serial) -> name = String(payload, data, size, Charsets.UTF_8)
                index == 3 && serial in 1..6 -> rootpage = readInt(payload, data, size)
            }
            data += size
        }
        if (type == null || name == null) return null
        return SchemaRow(type, name, rootpage)
    }

    private fun isText(serial: Int) = serial >= 13 && serial % 2 == 1

    private fun serialSize(serial: Int): Int = when {
        serial == 0 || serial == 8 || serial == 9 || serial == 10 || serial == 11 -> 0
        serial in 1..4 -> serial
        serial == 5 -> 6
        serial == 6 -> 8
        serial == 7 -> 8
        serial >= 12 -> (serial - 12) / 2
        else -> 0
    }

    private fun readInt(buf: ByteArray, at: Int, size: Int): Long {
        // Big-endian, sign-extended from the first byte — SQLite's serial ints.
        var value = if (size > 0 && buf[at] < 0) -1L else 0L
        for (i in 0 until size) value = (value shl 8) or (buf[at + i].toLong() and 0xFF)
        return value
    }

    private fun varint(buf: ByteArray, at: Int): Pair<Long, Int> {
        var value = 0L
        var i = 0
        while (i < 8) {
            val b = buf[at + i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F).toLong()
            if (b and 0x80 == 0) return value to (at + i + 1)
            i++
        }
        value = (value shl 8) or (buf[at + 8].toLong() and 0xFF)
        return value to (at + 9)
    }

    private fun u16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun u32(buf: ByteArray, at: Int): Long =
        ((buf[at].toInt() and 0xFF).toLong() shl 24) or
            ((buf[at + 1].toInt() and 0xFF).toLong() shl 16) or
            ((buf[at + 2].toInt() and 0xFF).toLong() shl 8) or
            (buf[at + 3].toInt() and 0xFF).toLong()

    // ---- header scanning ----

    private data class Run(val at: Int, val text: String)

    private fun printableRuns(buf: ByteArray): List<Run> {
        val runs = ArrayList<Run>()
        var at = 0
        while (at < buf.size) {
            if (!isRunByte(buf[at])) {
                at++
                continue
            }
            val start = at
            while (at < buf.size && isRunByte(buf[at])) at++
            if (at - start >= 4) runs += Run(start, String(buf, start, at - start, Charsets.US_ASCII))
        }
        return runs
    }

    /** Letters, digits and the punctuation that has shown up in keys and versions. */
    private fun isRunByte(b: Byte): Boolean {
        val c = b.toInt() and 0xFF
        return c in '0'.code..'9'.code || c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code ||
            c == '.'.code || c == '_'.code || c == '-'.code ||
            c == '+'.code || c == '/'.code || c == '='.code
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..hay.size - needle.size) {
            for (offset in needle.indices) {
                if (hay[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    private fun containsStr(buf: ByteArray, text: String): Boolean =
        indexOf(buf, text.toByteArray(Charsets.US_ASCII)) >= 0

    private fun hex(bytes: ByteArray, limit: Int): String =
        bytes.take(limit).joinToString("") { "%02x".format(it) }
}

/** The custom header every NT message database wears before the cipher stream. */
private const val CUSTOM_HEADER = 1024
private val MARKER = "QQ_NT DB".toByteArray(Charsets.US_ASCII)
private val MAGIC = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

/** First page of the cipher stream: [salt 16][cipher …]. */
private const val SALT = 16
private const val KDF_SALT = 16

/** Head read: the header plus the largest page-size candidate, in whole KiB. */
private const val HEAD_LIMIT = CUSTOM_HEADER + 8192

/** Documented for nt_msg.db; SQLCipher 3's and 4's own defaults, kept as fallbacks. */
private const val KDF_NORMAL = 4_000
private const val KDF_SQL3 = 64_000
private const val KDF_LEGACY = 256_000
private const val AES_BITS = 256

/**
 * Known-answer test for the device's PBKDF2: password and salt are plain ASCII,
 * so any provider that encodes passwords differently (UTF-16 etc.) fails here and
 * the probe switches to its byte-exact manual implementation. Value computed with
 * an independent RFC 2898 implementation (Python hashlib).
 */
private const val KAT_PASS = "0123456789abcdef0123456789abcdef"
private val KAT_SALT = "saltsaltsaltsalt".toByteArray(Charsets.US_ASCII)
private const val KAT_DK = "f0c0ad822517732ef37081f0f992ef4be6dc325438a60df184b1011e397f6052"

private val HMAC_ALGOS = listOf("HMAC_SHA1", "HMAC_SHA256", "HMAC_SHA512")

/** `gpro_v1-6_<nt_uid>.db` — the channel database carries nt_uid in its name. */
private val GPRO_UID = Regex("gpro_v1-6_(u_[A-Za-z0-9_-]+)\\.db")

/** `beacon_db` text pairs: `"home_uin": "390251789"` … `"uid": "u_…"`. */
private val BEACON_UIN = Regex("\"home_uin\": *\"([0-9]+)\"")
private val BEACON_UID = Regex("\"uid\": *\"(u_[A-Za-z0-9_-]+)\"")

/** Table b-tree page types. */
private const val INTERIOR_TABLE = 5
private const val LEAF_TABLE = 13

/** Enough pages for QQ's schema of hundreds of tables; a walk, not a crawl. */
private const val MAX_SCHEMA_PAGES = 64
