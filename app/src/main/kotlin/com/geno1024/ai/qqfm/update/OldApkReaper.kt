package com.geno1024.ai.qqfm.update

import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * Clears away the APKs an earlier update left behind, once one is known to have worked.
 *
 * Installing hands the system a copy of the build, and on Android 10 and later that
 * copy is put in Downloads first. It is what makes the install reachable if the
 * upgrade is refused, but it is a dozen megabytes per attempt, and the app's own
 * cached download stays too. Neither is any use once a newer build is running.
 *
 * "Known to have worked" is read off the version code: the process is replaced by the
 * install, so the next launch is the new build, and comparing it with the one seen
 * last time is what tells a finished upgrade from an attempt the user walked away
 * from. Refused installs never get here, so their copy survives.
 */
object OldApkReaper {

    /**
     * @return how many files were removed, zero when this launch was not an update.
     */
    fun reapIfUpdated(context: Context, currentVersionCode: Int): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getInt(KEY_VERSION, UNSEEN)
        prefs.edit().putInt(KEY_VERSION, currentVersionCode).apply()
        if (last == UNSEEN || last == currentVersionCode) return 0

        val removed = deleteCachedBuilds(context) + deleteDownloadedCopies(context)
        Log.i(TAG, "update from $last to $currentVersionCode left $removed old APK(s) behind")
        return removed
    }

    /** The app's own download, kept until something else clears the cache. */
    private fun deleteCachedBuilds(context: Context): Int {
        val files = File(context.cacheDir, CACHE_DIR)
            .listFiles { file -> file.isFile && file.name.endsWith(APK_SUFFIX) }
            ?: return 0
        return files.count { it.delete() }
    }

    /** The copy handed to the installer, which lives under the user's Downloads. */
    private fun deleteDownloadedCopies(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        return runCatching {
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND " +
                "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("$PREFIX%$APK_SUFFIX", "$DOWNLOADS_DIR%")
            context.contentResolver.delete(collection, selection, args)
        }.onFailure {
            Log.w(TAG, "could not clear the copies in Downloads", it)
        }.getOrDefault(0)
    }

    private const val PREFS = "qqfm"
    private const val KEY_VERSION = "update.lastVersionCode"
    private const val UNSEEN = -1

    /** Matches the name [ApkInstaller] gives the copy it saves. */
    private const val PREFIX = "qqfm-"
    private const val APK_SUFFIX = ".apk"
    private const val DOWNLOADS_DIR = "Download/"
    private const val CACHE_DIR = "update"
    private const val TAG = "OldApkReaper"
}