package com.geno1024.ai.qqfm.data

import android.app.Application
import com.geno1024.ai.qqfm.update.Updater

/**
 * The handful of preferences the updater keeps.
 */
class AppSettings(private val settings: Settings) {

    /** Where builds are downloaded from; the feed itself is always GitHub's. */
    var updateSource: Updater.Source
        get() = Updater.sourceFrom(settings.string(KEY_SOURCE))
        set(value) = settings.write(strings = mapOf(KEY_SOURCE to value.id))

    /**
     * The newest build the user has been told about.
     *
     * Remembering it means an update notice appears once for a build rather than on
     * every visit, and it is what makes "you are up to date" mean something on a
     * channel that never stops moving.
     */
    var dismissedVersion: String?
        get() = settings.string(KEY_DISMISSED)
        set(value) = settings.write(
            strings = if (value == null) emptyMap() else mapOf(KEY_DISMISSED to value),
            removed = if (value == null) setOf(KEY_DISMISSED) else emptySet(),
        )

    /**
     * Base ids the user pinned so accidental multi-select never grabs them.
     *
     * Stored as one comma-joined string; base ids are hex so the separator is safe.
     */
    var frozenIds: Set<String>
        get() = settings.string(KEY_FROZEN)
            ?.split(',')
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        set(value) = settings.write(strings = mapOf(KEY_FROZEN to value.joinToString(",")))

        /**
     * Running tally of what this app has removed, for the about page.
     *
     * Kept as a lifetime counter rather than a per-session one: the number is only
     * interesting across installs of the app, not across launches of a screen.
     */
    var cleanedFiles: Long
        get() = settings.string(KEY_CLEANED_FILES)?.toLongOrNull() ?: 0L
        set(value) = settings.write(strings = mapOf(KEY_CLEANED_FILES to value.toString()))

    var cleanedBytes: Long
        get() = settings.string(KEY_CLEANED_BYTES)?.toLongOrNull() ?: 0L
        set(value) = settings.write(strings = mapOf(KEY_CLEANED_BYTES to value.toString()))

    /** Adds one deletion to the tally. */
    fun recordCleanup(files: Long, bytes: Long) {
        val nextFiles = cleanedFiles + files
        val nextBytes = cleanedBytes + bytes
        settings.write(
            strings = mapOf(
                KEY_CLEANED_FILES to nextFiles.toString(),
                KEY_CLEANED_BYTES to nextBytes.toString(),
            ),
        )
    }

    companion object {
        fun of(application: Application) = AppSettings(
            SharedPreferencesSettings(application.getSharedPreferences("qqfm", Application.MODE_PRIVATE)),
        )

        const val KEY_SOURCE = "update.source"
        const val KEY_DISMISSED = "update.dismissed"
        const val KEY_FROZEN = "gallery.frozen"
        const val KEY_CLEANED_FILES = "cleanup.files"
        const val KEY_CLEANED_BYTES = "cleanup.bytes"
    }
}
