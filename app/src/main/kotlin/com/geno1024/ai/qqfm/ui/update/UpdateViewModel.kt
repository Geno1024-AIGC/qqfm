package com.geno1024.ai.qqfm.ui.update

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.geno1024.ai.qqfm.data.AppSettings
import com.geno1024.ai.qqfm.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UpdateUiState(
    val source: Updater.Source = Updater.SOURCES.first(),
    val available: Updater.Release? = null,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val downloaded: File? = null,
    val error: String? = null,
    val message: String? = null,
)

/**
 * Checks for a newer build and fetches it.
 *
 * The installed version is read from the package manager rather than kept anywhere of
 * our own, so it cannot drift from what is actually running.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {

    private val app = getApplication<Application>()
    private val settings = AppSettings.of(app)

    private val state = MutableStateFlow(UpdateUiState(source = settings.updateSource))
    val uiState: StateFlow<UpdateUiState> = state.asStateFlow()

    init {
        check()
    }

    /**
     * How long an offer stays worth showing, and how a repeat visit is treated.
     *
     * A build is announced once. After that, coming back to this screen should not
     * re-announce it, and it should not need a tap either.
     */
    private var lastCheckedAt = 0L

    private companion object {
        /** Long enough to catch a new build, short enough not to nag. */
        const val REVISIT_AFTER_MILLIS = 30 * 60 * 1000L
    }

    /**
     * Re-checks when the last one was long enough ago to have missed something.
     */
    fun recheckIfStale(nowMillis: Long = System.currentTimeMillis()) {
        if (nowMillis - lastCheckedAt >= REVISIT_AFTER_MILLIS) check()
    }

    fun check() = viewModelScope.launch {
        lastCheckedAt = System.currentTimeMillis()
        state.update { it.copy(checking = true, error = null, message = null) }
        val outcome = withContext(Dispatchers.IO) { runCatching { Updater.fetchReleases() } }
        outcome
            .onSuccess { releases ->
                val current = Updater.parseVersion(installedVersion())
                val dismissed = settings.dismissedVersion
                val found = Updater.newer(current, releases)
                state.update {
                    it.copy(
                        available = found,
                        // A build the user was already told about is not news; it is
                        // still kept so it can be installed from here.
                        message = when {
                            found == null -> "已是最新版本。"
                            found.version?.toString() == dismissed -> "${found.name} 可以安装。"
                            else -> "发现新版本 ${found.name}。"
                        },
                    )
                }
            }
            .onFailure { failure ->
                state.update {
                    it.copy(error = "无法获取版本列表：${failure.message ?: "未知原因"}")
                }
            }
        state.update { it.copy(checking = false) }
    }

    fun setSource(source: Updater.Source) {
        settings.updateSource = source
        state.update { it.copy(source = source) }
    }

    /** Marks the offered build as seen, so it is not offered again on every visit. */
    fun dismiss() {
        state.value.available?.version?.let { settings.dismissedVersion = it.toString() }
        state.update { it.copy(available = null, message = "已忽略该版本的更新提示。") }
    }

    fun download() = viewModelScope.launch {
        val release = state.value.available ?: return@launch
        val target = File(app.cacheDir, "update/${release.apkName}")
        state.update {
            it.copy(downloading = true, error = null, message = null, downloadedBytes = 0, totalBytes = release.apkSize)
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                Updater.download(Updater.downloadUrl(state.value.source, release), target) { done, total ->
                    // The length in the header is unknown until the first bytes land;
                    // the feed's size is the better number while that is true.
                    val size = if (total > 0) total else release.apkSize
                    state.update { it.copy(downloadedBytes = done, totalBytes = size) }
                }
            }
        }
        outcome
            .onSuccess {
                state.update { it.copy(downloaded = target, message = "已下载 ${target.name}。") }
            }
            .onFailure { failure ->
                target.delete()
                state.update {
                    it.copy(error = "下载失败：${failure.message ?: "未知原因"}")
                }
            }
        state.update { it.copy(downloading = false) }
    }

    fun dismissError() = state.update { it.copy(error = null) }

    /**
     * Puts what an attempt came to where the section shows its news.
     */
    fun noteInstallOutcome(outcome: String) {
        state.update { it.copy(message = outcome) }
    }

    private fun installedVersion(): String = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        info.versionName.orEmpty()
    }.getOrDefault("")
}
