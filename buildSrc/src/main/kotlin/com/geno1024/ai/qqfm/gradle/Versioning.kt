package com.geno1024.ai.qqfm.gradle

import org.gradle.api.Project
import java.io.File
import java.io.RandomAccessFile

/**
 * A build stamp of the form `<base>.<env>.<pack>.<sha1>`, currently `0.2.…`.
 *
 * [env] is the build's position in the release stream and the only ordered field;
 * [pack] counts packaging builds of the module; [sha1] ties the artifact to its
 * source revision.
 */
data class Stamp(
    val env: String,
    val pack: String,
    val sha1: String,
) {
    val versionName: String get() = "$BASE_VERSION.$env.$pack.$sha1"

    /** Android `versionCode`; strictly monotonic within a release stream. */
    val versionCode: Int get() = (env.toIntOrNull() ?: 0).coerceAtLeast(1)

    companion object {
        const val BASE_VERSION = "0.2"
    }
}

object Versioning {
    private val NO_HEAD = "00000000"

    /**
     * Task-name prefixes that produce a distributable. Matching by prefix rather
     * than by exact name keeps flavour variants (`assembleFreeRelease`) and the
     * `install*` path in the count.
     */
    private val PACKAGING_PREFIXES = listOf(
        "assemble", "bundle", "package", "install",
        "jar", "shadowJar", "dist", "publish",
    )

    /**
     * `build` is an aggregate worth counting, but as a prefix it would also
     * catch `buildEnvironment`.
     */
    private val PACKAGING_EXACT = setOf("build")

    fun stamp(root: File, moduleDir: File): Stamp = Stamp(
        env = env(root),
        pack = packOf(moduleDir),
        sha1 = sha1(root),
    )

    /**
     * The build's position in the stream: the number of commits on the branch.
     *
     * This has to be the same quantity on the developer's machine and on CI, or the
     * two stamps cannot be ordered: the run number restarts with the workflow and sat
     * below the commit count, so every CI build looked *older* than the last local one
     * and `versionCode` went backwards. The run number is only a fallback for a
     * checkout without history (`fetch-depth: 0` keeps it intact on CI).
     */
    fun env(root: File): String =
        git(root, "rev-list", "--count", "HEAD")
            ?: System.getenv("GITHUB_RUN_NUMBER")
            ?: "0"

    fun sha1(root: File): String =
        git(root, "rev-parse", "--short=8", "HEAD") ?: NO_HEAD

    fun packFile(moduleDir: File) = File(moduleDir, "count.pack")

    private fun packOf(moduleDir: File): String {
        val file = packFile(moduleDir)
        return if (file.exists()) file.readText().trim().ifEmpty { "0" } else "0"
    }

    /**
     * Counts this session's packaging build of the module.
     *
     * Call it before [stamp], so the artifact carries its own one-based number and
     * the file equals the number of packaging builds that have happened.
     *
     * The count is taken at configuration time, which is why it records *requests*
     * and not successes: a build that fails to compile, cannot sign or is
     * interrupted after configuration already has its number. Query-only tasks
     * (`tasks`, `help`, …) and `--dry-run` stay out. `-Ppack.bump=true|false`
     * forces the counter on or off; CI skips it by default, because an increment
     * that is never written back would invent a number nobody has seen.
     */
    fun bumpOnPackaging(project: Project, moduleDir: File) {
        val params = project.gradle.startParameter
        if (params.isDryRun) return
        val forced = params.projectProperties["pack.bump"]?.toBoolean()
        if (forced == false) return
        if (forced == null && !System.getenv("CI").isNullOrEmpty()) return
        if (params.taskNames.none(::requestsPackaging)) return
        incrementPack(moduleDir)
    }

    private fun requestsPackaging(taskName: String): Boolean {
        val name = taskName.substringAfterLast(':')
        return name in PACKAGING_EXACT || PACKAGING_PREFIXES.any(name::startsWith)
    }

    /**
     * Serialised through a file lock: two builds of the same checkout would read
     * the same value and the later write would swallow the earlier increment.
     */
    private fun incrementPack(moduleDir: File) {
        val file = packFile(moduleDir)
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.lock().use {
                val bytes = ByteArray(raf.length().toInt())
                raf.seek(0L)
                raf.readFully(bytes)
                val current = String(bytes, Charsets.UTF_8).trim().toIntOrNull() ?: 0
                raf.setLength(0L)
                raf.seek(0L)
                raf.write("${current + 1}\n".toByteArray())
            }
        }
    }

    private fun git(root: File, vararg args: String): String? = try {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + args)
            .redirectErrorStream(false)
            .start()
        val out = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}
