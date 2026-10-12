package com.geno1024.ai.qqfm

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.geno1024.ai.qqfm.ui.theme.QQFMTheme
import com.geno1024.ai.qqfm.update.OldApkReaper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        clearInstallLeftovers()
        setContent {
            QQFMTheme {
                QqfmApp()
            }
        }
    }

    /**
     * Clears the builds an update left behind.
     *
     * Runs before the UI so the file work is not waiting on a frame, and off the main
     * thread because it walks Downloads. It is a plain thread rather than a coroutine:
     * there is nothing to cancel here, and the result is a cache nobody is waiting on.
     */
    private fun clearInstallLeftovers() {
        Thread {
            runCatching { OldApkReaper.reapIfUpdated(application, BuildConfig.VERSION_CODE) }
        }.start()
    }
}
