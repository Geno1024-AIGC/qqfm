package com.geno1024.ai.qqfm.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.geno1024.ai.qqfm.R

/**
 * Reports how an install ended, and asks for the confirmation the platform deferred.
 *
 * Two things land here. The platform's own session result, which only a package installer
 * session can produce, and [Intent.ACTION_MY_PACKAGE_REPLACED], which is what a build that
 * went in through the ordinary installer reports. Between them every install says how it
 * ended, one way or another, which is the difference between a record and a guess.
 *
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] is the status this receiver used to
 * swallow: committing a session does not put a confirmation on screen, it hands the app
 * an [Intent.EXTRA_INTENT] and waits to be asked.
 */
class InstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            record(context, context.getString(R.string.update_installed))
            return
        }

        val result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, resultCode)

        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            confirm(context, intent)
            return
        }

        val text = when (result) {
            PackageInstaller.STATUS_SUCCESS ->
                context.getString(R.string.update_installed)
            PackageInstaller.STATUS_FAILURE_ABORTED ->
                context.getString(R.string.update_install_canceled)
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                context.getString(R.string.update_install_conflict)
            PackageInstaller.STATUS_FAILURE_INVALID ->
                context.getString(R.string.update_install_invalid)
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                context.getString(R.string.update_install_incompatible)
            PackageInstaller.STATUS_FAILURE_STORAGE ->
                context.getString(R.string.update_install_no_space)
            PackageInstaller.STATUS_FAILURE_TIMEOUT ->
                context.getString(R.string.update_install_timeout)
            PackageInstaller.STATUS_FAILURE_BLOCKED ->
                context.getString(R.string.update_install_blocked)
            else -> context.getString(R.string.update_install_failed, result)
        }
        record(context, text)
    }

    /**
     * Puts the platform's own confirmation on screen.
     */
    private fun confirm(context: Context, intent: Intent) {
        val confirm = intent.confirmIntent()
        if (confirm == null) {
            record(context, context.getString(R.string.update_install_not_started, "no confirmation was offered"))
            return
        }
        runCatching { context.startActivity(confirm.addFlags(CONFIRM_FLAGS)) }
            .onFailure { failure ->
                record(context, context.getString(R.string.update_install_not_started, failure.message ?: "unknown reason"))
            }
    }

    /**
     * Tells the person how the install ended.
     *
     * Notifying is a courtesy, never a requirement: without POST_NOTIFICATIONS the call
     * is dropped, and a receiver that throws after the install already ended would take
     * the only report of that install down with it.
     */
    private fun record(context: Context, text: String) {
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.update_channel), IMPORTANCE)
                .also(manager::createNotificationChannel)
            manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(text)
                .setAutoCancel(true)
                .build())
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.confirmIntent(): Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_INTENT)
    }

    companion object {
        const val ACTION = "com.geno1024.ai.qqfm.INSTALL_RESULT"

        /**
         * Starting from a receiver is not starting from an activity, so the platform
         * insists on a task of its own.
         */
        private const val CONFIRM_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK

        private const val CHANNEL = "install"
        private const val NOTIFICATION_ID = 1
        private const val IMPORTANCE = NotificationManager.IMPORTANCE_DEFAULT
    }
}
