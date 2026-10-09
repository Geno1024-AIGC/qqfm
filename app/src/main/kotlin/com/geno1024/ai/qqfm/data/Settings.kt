package com.geno1024.ai.qqfm.data

import android.content.SharedPreferences

/**
 * The small part of key-value storage this app uses.
 *
 * Everything the app persists is a handful of strings, so this stays narrower than
 * SharedPreferences on purpose.
 */
interface Settings {

    fun string(key: String): String?

    fun write(
        strings: Map<String, String> = emptyMap(),
        removed: Set<String> = emptySet(),
    )
}

/** [Settings] over Android's [SharedPreferences]. */
class SharedPreferencesSettings(private val prefs: SharedPreferences) : Settings {

    override fun string(key: String): String? = prefs.getString(key, null)

    override fun write(strings: Map<String, String>, removed: Set<String>) {
        val editor = prefs.edit()
        strings.forEach { (key, value) -> editor.putString(key, value) }
        removed.forEach(editor::remove)
        editor.apply()
    }
}
