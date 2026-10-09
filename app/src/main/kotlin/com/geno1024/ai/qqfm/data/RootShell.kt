package com.geno1024.ai.qqfm.data

import java.io.Closeable
import java.io.InputStream
import java.util.concurrent.TimeUnit

/** Result of a one-shot root command. `output` merges stdout and stderr. */
data class ShellResult(val output: String, val exitCode: Int) {
    val ok: Boolean get() = exitCode == 0
}

/**
 * A thin wrapper around `su -c`. QQ's private cache lives under
 * `/storage/emulated/0/Android/data/...`, which no app can read directly on
 * Android 11+, so every filesystem operation goes through a root shell.
 */
object RootShell {

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Runs a command to completion and returns its merged output. */
    fun exec(command: String, timeoutSeconds: Long = 0): ShellResult {
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

    /** Streams a command's output line by line without buffering the whole thing. */
    fun forEachLine(command: String, action: (String) -> Unit) {
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

    fun isRootAvailable(): Boolean = runCatching {
        val result = exec("id")
        result.ok && result.output.contains("uid=0")
    }.getOrDefault(false)

    /**
     * Opens a root-owned file for reading. The returned stream owns the `su`
     * process; closing it releases both. stderr is discarded inside the shell so
     * the binary payload on stdout stays clean.
     */
    fun openRead(path: String): CloseableStream {
        val process = ProcessBuilder("su", "-c", "cat -- ${shellQuote(path)} 2>/dev/null")
            .redirectErrorStream(false)
            .start()
        return CloseableStream(process.inputStream, process)
    }

    class CloseableStream(
        val stream: InputStream,
        private val process: Process,
    ) : Closeable {
        override fun close() {
            runCatching { stream.close() }
            runCatching { process.waitFor(2, TimeUnit.SECONDS) }
            runCatching { process.destroy() }
        }
    }
}
