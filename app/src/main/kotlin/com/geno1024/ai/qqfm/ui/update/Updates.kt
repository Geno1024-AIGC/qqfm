package com.geno1024.ai.qqfm.ui.update

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.qqfm.BuildConfig
import com.geno1024.ai.qqfm.R
import com.geno1024.ai.qqfm.update.ApkInstaller
import com.geno1024.ai.qqfm.update.Updater
import kotlinx.coroutines.launch

/**
 * Where newer builds are found, fetched from, and installed.
 *
 * The source is offered as a choice rather than a fallback because whether GitHub's
 * file hosts are reachable is a property of the network a person is on, not of the
 * app. It lives inline on the about page rather than on a screen of its own, because
 * checking for an update is one line of a page rather than a destination.
 */
@Composable
fun UpdatesSection(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Checking has to be asked for again from here, or the section would show a
    // stale answer while looking like it had just looked.
    LaunchedEffect(Unit) {
        viewModel.recheckIfStale()
    }

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.update_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::check, enabled = !state.checking) {
                Text(stringResource(R.string.action_check_update))
            }
        }
        UpdatePanel(viewModel = viewModel)
    }
}

@Composable
private fun UpdatePanel(
    viewModel: UpdateViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noActivityMessage = stringResource(R.string.update_install_no_activity)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ReleaseCard(
                state = state,
                onDownload = viewModel::download,
                onInstall = { apk ->
                    val activity = context.findActivity()
                    if (activity == null) {
                        viewModel.noteInstallOutcome(noActivityMessage)
                    } else {
                        // The install copies tens of megabytes, so it runs off the
                        // main thread and this scope is what the frame waits on.
                        scope.launch { ApkInstaller.install(activity, apk, viewModel::noteInstallOutcome) }
                    }
                },
                onDismissNotice = viewModel::dismiss,
            )

            SourceDropdown(
                source = state.source,
                onSelect = viewModel::setSource,
            )
        }

        state.error?.let { message ->
            ErrorBar(message = message, onDismiss = viewModel::dismissError)
        }
    }
}

@Composable
private fun ReleaseCard(
    state: UpdateUiState,
    onDownload: () -> Unit,
    onInstall: (java.io.File) -> Unit,
    onDismissNotice: () -> Unit,
) {
    val release = state.available
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // A spinner alone, replacing the card, is what made a slow check look like a
        // dead screen. The previous answer stays, with the check shown as a side note.
        if (state.checking) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (release == null && !state.checking) {
            Text(
                text = stringResource(R.string.update_up_to_date),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else if (release != null) {
            Text(text = release.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = release.version?.toString().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.downloading) {
                val total = state.totalBytes.takeIf { it > 0 } ?: 1L
                LinearProgressIndicator(
                    progress = { (state.downloadedBytes.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.update_downloading,
                        percent(state.downloadedBytes, state.totalBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (state.downloaded != null) {
                val downloaded = state.downloaded
                Button(onClick = { onInstall(downloaded) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.update_install))
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onDownload, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.update_download))
                    }
                    TextButton(onClick = onDismissNotice) {
                        Text(stringResource(R.string.update_not_now))
                    }
                }
            }
        }

        state.message?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Which host the download is taken from: one field to open rather than a column of
 * radios, since the choice is between a few mirrors and not a setting to weigh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceDropdown(
    source: Updater.Source,
    onSelect: (Updater.Source) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = source.label,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.update_source_heading)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .exposedDropdownSize(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Updater.SOURCES.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    trailingIcon = {
                        if (option.id == source.id) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ErrorBar(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        title = { Text(stringResource(R.string.error_title)) },
        text = { Text(message) },
    )
}

/** A whole percent, or a dash while the size is still unknown. */
private fun percent(done: Long, total: Long): String =
    if (total <= 0) "—" else "${(done * 100 / total)}%"

private fun android.content.Context.findActivity(): Activity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
