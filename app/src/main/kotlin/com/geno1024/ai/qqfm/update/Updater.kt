package com.geno1024.ai.qqfm.update

import com.geno1024.ai.qqfm.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds newer builds of this app and fetches them.
 *
 * The release feed is always read from `api.github.com`, whatever the user downloads
 * from. The mirror is a network-level convenience for people who cannot reach GitHub's
 * file hosts; a mirror that could edit the metadata could also lie about which build
 * is current, so only the file transfer is mirrored.
 */
object Updater {

    const val OWNER = "Geno1024-AIGC"
    const val REPO = "qqfm"

    /** Where the build to install comes from. */
    class Source(val id: String, val label: String, val downloadPrefix: String)

    val SOURCES = listOf(
        Source("github", "GitHub", "https://github.com"),
        Source("ghproxy", "ghproxy", "https://mirror.ghproxy.com/https://github.com"),
        Source("gh-proxy", "gh-proxy", "https://gh-proxy.com/https://github.com"),
        Source("ghfast", "ghfast.top", "https://ghfast.top/https://github.com"),
    )

    fun sourceFrom(id: String?): Source = SOURCES.firstOrNull { it.id == id } ?: SOURCES.first()

    /**
     * A build stamp: `0.1.<env>.<pack>.<sha>`.
     *
     * [env] is the CI run number and [pack] the number of packaging runs, both
     * monotonic within a stream, so ordering is by those two. [sha] identifies the
     * commit and is deliberately not part of the comparison: it is a name, not a number.
     */
    class Version(val env: Int, val pack: Int, val sha: String) : Comparable<Version> {
        override fun compareTo(other: Version): Int =
            compareValuesBy(this, other, Version::env, Version::pack)

        override fun equals(other: Any?): Boolean =
            other is Version && env == other.env && pack == other.pack && sha == other.sha

        override fun hashCode(): Int = (env * 31 + pack) * 31 + sha.hashCode()

        override fun toString(): String = "0.1.$env.$pack.$sha"
    }

    fun parseVersion(text: String): Version? {
        val match = Regex("""0\.1\.(\d+)\.(\d+)\.([0-9a-fA-F]{8})""").find(text) ?: return null
        return Version(
            env = match.groupValues[1].toInt(),
            pack = match.groupValues[2].toInt(),
            sha = match.groupValues[3].lowercase(),
        )
    }

    class Release(
        val tag: String,
        val name: String,
        val prerelease: Boolean,
        val version: Version?,
        val apkName: String?,
        val apkSize: Long,
    )

    /**
     * Reads the releases newest first, skipping any that carry no APK.
     *
     * A release with no asset cannot be installed, so offering one would only produce a
     * download that cannot finish.
     */
    fun fetchReleases(): List<Release> {
        val body = get("https://api.github.com/repos/$OWNER/$REPO/releases?per_page=30")
        val list = Json.parse(body) as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val apk = (map["assets"] as? List<*>).orEmpty()
                .mapNotNull { it as? Map<*, *> }
                .firstOrNull { (it["name"] as? String)?.endsWith(".apk", ignoreCase = true) == true }
            val name = map["name"] as? String ?: map["tag_name"] as? String ?: return@mapNotNull null
            Release(
                tag = map["tag_name"] as? String ?: return@mapNotNull null,
                name = name,
                prerelease = map["prerelease"] == true,
                version = parseVersion(name) ?: parseVersion(map["tag_name"] as? String ?: ""),
                apkName = apk?.get("name") as? String,
                apkSize = ((apk?.get("size") as? Number)?.toDouble() ?: 0.0).toLong(),
            )
        }
    }

    /**
     * The newest build worth installing, or null when what is installed is current.
     *
     * Equal stamps mean the same build, which happens on a re-run of one commit, and
     * offering that as an update would be noise.
     */
    fun newer(current: Version?, available: List<Release>): Release? {
        val candidates = available.mapNotNull { release ->
            release.version?.let { it to release }
        }
        return candidates
            .filter { (version, _) -> current == null || version > current }
            .maxByOrNull { (version, _) -> version }
            ?.second
    }

    fun downloadUrl(source: Source, release: Release): String =
        "${source.downloadPrefix}/$OWNER/$REPO/releases/download/${release.tag}/${release.apkName}"

    /**
     * Fetches [url] into [target], reporting bytes written against the total.
     *
     * The body is streamed rather than read whole: a build is tens of megabytes and
     * holding one in memory to write it to disk is a way to be killed by the system on
     * a large one.
     */
    fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/vnd.android.package-archive")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from ${URL(url).host}")
            val total = connection.contentLengthLong
            target.parentFile?.mkdirs()
            // Written beside the target and moved into place, so an interrupted
            // download is never mistaken for a finished one.
            val partial = File(target.parentFile, target.name + ".part")
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "could not put the download in place" }
        } finally {
            connection.disconnect()
        }
    }

    private fun get(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code from ${URL(url).host}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private const val USER_AGENT = "qqfm"
    private const val BUFFER_BYTES = 64 * 1024
}
