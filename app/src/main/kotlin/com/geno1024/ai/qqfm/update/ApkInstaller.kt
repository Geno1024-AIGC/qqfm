package com.geno1024.ai.qqfm.update

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.geno1024.ai.qqfm.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands a downloaded build to the system to install.
 *
 * An app cannot replace itself, so this goes through the platform: a copy in Downloads
 * handed to the ordinary installer, or a package installer session. The copy is the one
 * that gets used, because it is the route the system draws a confirmation for every time.
 *
 * Anything that would leave the tap unanswered is answered here: a device that has not
 * been allowed to install this app is sent to the one setting that changes that, and a
 * failure to open an installer falls back to the file provider rather than dying.
 */
object ApkInstaller {

    /**
     * @param onResult a message for the person, already resolved to display text.
     */
    suspend fun install(activity: Activity, apk: File, onResult: (String) -> Unit) {
        runCatching {
            if (!canInstallPackages(activity)) {
                onResult(activity.getString(R.string.update_install_permission_needed))
                runCatching { openInstallPermission(activity) }
                    .onFailure { Log.w(TAG, "could not open the install permission setting", it) }
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                installViaDownloads(activity, apk, onResult)
            } else {
                sessionInstall(activity, apk)
                onResult(activity.getString(R.string.update_install_handed_off))
            }
        }.onFailure {
            Log.w(TAG, "could not start the install", it)
            onResult(
                activity.getString(R.string.update_install_not_started, it.message ?: UNKNOWN_REASON),
            )
        }
    }

    /**
     * Whether the user has already allowed this app to install packages.
     *
     * Asked before any work is done, because finding out afterwards would mean
     * downloading tens of megabytes only to discover it cannot be used.
     */
    fun canInstallPackages(activity: Activity): Boolean =
        activity.packageManager.canRequestPackageInstalls()

    /**
     * The one setting that allows it, opened rather than described.
     */
    private fun openInstallPermission(activity: Activity) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${activity.packageName}"),
        )
        activity.startActivity(intent)
    }

    private suspend fun sessionInstall(activity: Activity, apk: File) {
        withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val installer = activity.packageManager.packageInstaller
            val session = installer.createSession(params)
            installer.openSession(session).use {
                it.openWrite(apk.name, 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    it.fsync(out)
                }
                it.commit(resultIntent(activity).intentSender)
            }
        }
    }

    /**
     * Puts the build in Downloads and opens it there.
     *
     * A copy in the user's Downloads folder is left behind on purpose: it is the only
     * one of these routes where the file remains reachable if the install is refused.
     */
    private suspend fun installViaDownloads(activity: Activity, apk: File, onResult: (String) -> Unit) {
        val saved = runCatching { saveToDownloads(activity, apk) }
            .onFailure { Log.w(TAG, "could not put the build in Downloads", it) }
            .getOrNull()

        if (saved != null) {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(saved, APK_MIME)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { activity.startActivity(open) }
                .onSuccess {
                    onResult(activity.getString(R.string.update_install_saved_to_downloads))
                    return
                }
                .onFailure {
                    Log.w(TAG, "no installer would open the copy in Downloads", it)
                    // A file that could not be installed should not be left behind looking
                    // like a build that could be.
                    activity.contentResolver.delete(saved, null, null)
                }
        }

        // The build is still in the cache, so the tap gets the provider route rather than
        // nothing at all just because Downloads said no.
        openInstaller(activity, apk, onResult)
    }

    private suspend fun saveToDownloads(activity: Activity, apk: File): Uri {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            error("Downloads is a shared collection on Android 10 and later only")
        }
        val resolver = activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "qqfm-${apk.nameWithoutExtension}.apk")
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/")
        }
        val target = resolver.insert(
            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            values,
        ) ?: error("Downloads refused the file")

        runCatching {
            withContext(Dispatchers.IO) {
                resolver.openOutputStream(target)?.use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                } ?: error("Downloads would not open the file for writing")
            }
        }.onFailure { failure ->
            resolver.delete(target, null, null)
            throw failure
        }
        return target
    }

    private fun openInstaller(activity: Activity, apk: File, onResult: (String) -> Unit) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(installerUri(activity, apk), APK_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        activity.startActivity(intent)
        onResult(activity.getString(R.string.update_install_confirm))
    }

    /**
     * The build as something another app is allowed to read.
     *
     * A file:// Uri throws [android.content.FileUriExposedException] on every release this
     * app supports, so the installer has to be handed a grant it was given the authority for.
     */
    private fun installerUri(context: Context, apk: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)

    private fun resultIntent(activity: Activity): PendingIntent = PendingIntent.getBroadcast(
        activity,
        0,
        Intent(InstallReceiver.ACTION).setPackage(activity.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    private const val APK_MIME = "application/vnd.android.package-archive"
    private const val UNKNOWN_REASON = "unknown reason"
    private const val TAG = "ApkInstaller"
}
