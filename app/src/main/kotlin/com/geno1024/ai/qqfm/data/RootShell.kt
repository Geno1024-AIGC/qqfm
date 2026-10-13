package com.geno1024.ai.qqfm.data

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of a one-shot root command. `output` merges stdout and stderr. */
data class ShellResult(val output: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A thin wrapper around root. QQ's private cache lives under
 * `/storage/emulated/0/Android/data/...`, which no app can read directly on
 * Android 11+, so every filesystem operation goes through root.
 *
 * Commands go through one long-lived `su` process rather than a fresh one each
 * time: `su` costs on the order of a tenth of a second before it runs anything,
 * which a gallery reading hundreds of files would pay over and over. The shell is
 * driven as a pipe — a command, then a line carrying a unique token and its exit
 * code — and anything that goes wrong with it falls back to one-shot `su -c`, so a
 * device whose `su` refuses an interactive shell still works, just slower.
 */
object RootShell {

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private val session = Session()

    /** Runs a command to completion and returns its merged output. */
    fun exec(command: String, timeoutSeconds: Long = 0): ShellResult {
        val outcome = session.capture(command, timeoutMillis(timeoutSeconds))
        if (outcome != null) {
            return ShellResult(String(outcome.output, StandardCharsets.UTF_8), outcome.exitCode)
        }
        return oneShot(command, timeoutSeconds)
    }

    /** Streams a command's output line by line without buffering the whole thing. */
    fun forEachLine(command: String, action: (String) -> Unit) {
        val streamed = session.stream(command, STREAM_TIMEOUT_MILLIS, action)
        if (streamed) return
        oneShotStream(command, action)
    }

    /**
     * Reads a whole file as bytes.
     *
     * Binary, so it does not go through the line reader: the bytes are collected up
     * to the marker as they arrive.
     */
    fun readBytes(path: String): ByteArray? {
        // cat complains on stderr, and the wrapper merges stderr into stdout, so the
        // complaint is turned off here and the exit code is what decides success.
        val command = "cat -- ${shellQuote(path)} 2>/dev/null"
        session.capture(command, READ_TIMEOUT_MILLIS)?.let { outcome ->
            return if (outcome.exitCode == 0) outcome.output else null
        }
        return oneShotRead(command)
    }

    fun isRootAvailable(): Boolean = runCatching {
        val result = exec("id")
        result.ok && result.output.contains("uid=0")
    }.getOrDefault(false)

    /** Closes the shared shell so no root process outlives the app. */
    fun shutdown() = session.close()

    private fun timeoutMillis(timeoutSeconds: Long): Long =
        if (timeoutSeconds > 0) timeoutSeconds * 1000 else DEFAULT_TIMEOUT_MILLIS

    // ---- one-shot fallbacks, used when the shared shell is unavailable ----

    private fun oneShot(command: String, timeoutSeconds: Long): ShellResult {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val output = try {
            process.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            ""
        }
        val exit = if (timeoutSeconds > 0) {
            if (process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) process.exitValue()
            else {
                process.destroy()
                -1
            }
        } else {
            process.waitFor()
        }
        return ShellResult(output, exit)
    }

    private fun oneShotStream(command: String, action: (String) -> Unit) {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        try {
            process.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    action(line)
                }
            }
        } finally {
            runCatching { process.waitFor() }
        }
    }

    /** Raw bytes, never through a reader: pictures are not text and UTF-8 would ruin them. */
    private fun oneShotRead(command: String): ByteArray? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(false)
            .start()
        try {
            process.inputStream.readBytes()
        } finally {
            runCatching { process.waitFor(5, TimeUnit.SECONDS) }
            runCatching { process.destroy() }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * One `su`, kept open, with every command taking turns.
     *
     * A pipe is serial by nature, so the lock is not an optimisation here but the
     * only way two callers can share the shell. It is held for the length of a
     * command, and a command that overruns its deadline kills the shell rather than
     * blocking whatever queued behind it.
     */
    private class Session : Closeable {

        private val lock = Any()
        private var process: Process? = null
        private var writer: OutputStream? = null
        private var input: InputStream? = null
        private var sequence = 0L

        /** After a start-up failure, one-shot calls carry the load for a while. */
        private var fallbackUntil = 0L

        private val watchdog: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "qqfm-root-watchdog").apply { isDaemon = true }
            }

        class Outcome(val output: ByteArray, val exitCode: Int)

        /** Streams stdout line by line; returns false when the shell could not be used. */
        fun stream(command: String, timeoutMillis: Long, action: (String) -> Unit): Boolean {
            synchronized(lock) {
                val connection = connect() ?: return false
                val token = nextToken()
                val deadline = run(command, token, timeoutMillis) ?: return false
                try {
                    val reader = BufferedReader(
                        InputStreamReader(connection.input, StandardCharsets.UTF_8),
                    )
                    while (true) {
                        val line = reader.readLine() ?: return false
                        val code = exitCodeOf(line, token)
                        if (code != null) {
                            deadline.cancel()
                            return true
                        }
                        action(line)
                    }
                } catch (_: Exception) {
                    kill()
                    return false
                }
            }
        }

        /** Collects everything printed before the marker; binary safe. */
        fun capture(command: String, timeoutMillis: Long): Outcome? = synchronized(lock) {
            val connection = connect() ?: return null
            val token = nextToken()
            val deadline = run(command, token, timeoutMillis) ?: return null
            try {
                val collected = ByteArrayOutputStream()
                val stream = connection.input
                val marker = "$token:".toByteArray(StandardCharsets.UTF_8)
                var tail = ByteArray(0)
                var trailer = ""
                var finished = false
                while (!finished) {
                    val buffer = ByteArray(16 * 1024)
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    val combined = tail + buffer.copyOf(read)
                    val index = indexOf(combined, marker)
                    if (index >= 0) {
                        collected.write(combined, 0, index)
                        trailer = String(combined, index, combined.size - index, StandardCharsets.UTF_8)
                        finished = true
                    } else {
                        // Hold back just enough of the end to recognise a marker
                        // that was split across two reads.
                        val keep = (marker.size - 1).coerceAtMost(combined.size)
                        val boundary = combined.size - keep
                        collected.write(combined, 0, boundary)
                        tail = combined.copyOfRange(boundary, combined.size)
                    }
                }
                deadline.cancel()
                if (!finished) {
                    // No marker: the shell went away mid-command, so nothing that
                    // follows on this pipe can be trusted.
                    kill()
                    return null
                }
                val code = trailer.substringAfter(':', "")
                    .substringBefore('\n')
                    .trim()
                    .toIntOrNull() ?: -1
                Outcome(collected.toByteArray(), code)
            } catch (_: Exception) {
                kill()
                null
            }
        }

        override fun close() {
            synchronized(lock) {
                runCatching { writer?.close() }
                kill()
            }
        }

        // ---- plumbing ----

        private class Connection(val writer: OutputStream, val input: InputStream)

        /** Stops the watchdog once the marker turns up, so it does not kill a good shell. */
        private class Deadline(
            private val task: java.util.concurrent.ScheduledFuture<*>,
            private val finished: AtomicBoolean,
        ) {
            fun cancel() {
                finished.set(true)
                task.cancel(false)
            }
        }

        private fun connect(): Connection? {
            if (System.currentTimeMillis() < fallbackUntil) return null
            process?.let { existing ->
                if (existing.isAlive && writer != null && input != null) {
                    return Connection(writer!!, input!!)
                }
            }
            return runCatching {
                // No command, so su hands back a shell to talk to rather than
                // running something once and exiting.
                val started = ProcessBuilder("su")
                    .redirectErrorStream(false)
                    .start()
                process = started
                writer = started.outputStream
                input = started.inputStream
                Connection(started.outputStream, started.inputStream)
            }.getOrElse {
                // Root denied, or this su has no interactive mode. Back off so the
                // next call does not pay for another doomed start.
                fallbackUntil = System.currentTimeMillis() + FALLBACK_BACKOFF_MILLIS
                kill()
                null
            }
        }

        /**
         * Writes the command and arms the watchdog.
         *
         * @return a handle to cancel once the marker has been seen, or null if the
         *   shell is not in a state to accept work.
         */
        private fun run(command: String, token: String, timeoutMillis: Long): Deadline? {
            val finished = AtomicBoolean(false)
            val target = process
            val watchdogTask = watchdog.schedule(
                {
                    if (finished.compareAndSet(false, true)) target?.destroyForcibly()
                },
                timeoutMillis,
                TimeUnit.MILLISECONDS,
            )
            return runCatching {
                val payload = "{ $command ; } 2>&1; printf '%s:%s\\n' '${token}' \"\$?\"\n"
                writer?.write(payload.toByteArray(StandardCharsets.UTF_8)) ?: error("no writer")
                writer?.flush() ?: error("no flush")
                Deadline(watchdogTask, finished)
            }.getOrElse {
                watchdogTask.cancel(false)
                kill()
                null
            }
        }

        private fun nextToken(): String {
            val id = ++sequence
            return "__qqfm_${id}_${java.lang.Long.toHexString(System.nanoTime())}__"
        }

        private fun exitCodeOf(line: String, token: String): Int? =
            if (line.startsWith(token)) line.substringAfter(':', "").toIntOrNull() else null

        private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
            outer@ for (start in 0..haystack.size - needle.size) {
                for (offset in needle.indices) {
                    if (haystack[start + offset] != needle[offset]) continue@outer
                }
                return start
            }
            return -1
        }

        private fun kill() {
            runCatching { process?.destroy() }
            runCatching { process?.waitFor(2, TimeUnit.SECONDS) }
            process = null
            writer = null
            input = null
        }
    }

    private const val DEFAULT_TIMEOUT_MILLIS = 60_000L
    private const val READ_TIMEOUT_MILLIS = 60_000L

    /** A directory walk of 165k files is allowed to take minutes, but not forever. */
    private const val STREAM_TIMEOUT_MILLIS = 10 * 60_000L
    private const val FALLBACK_BACKOFF_MILLIS = 60_000L
}