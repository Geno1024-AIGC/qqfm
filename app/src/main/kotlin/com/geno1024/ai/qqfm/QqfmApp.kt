package com.geno1024.ai.qqfm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.qqfm.data.OcrScanBus
import com.geno1024.ai.qqfm.ui.about.AboutScreen
import com.geno1024.ai.qqfm.ui.gallery.GalleryScreen
import com.geno1024.ai.qqfm.ui.gallery.GalleryViewModel
import com.geno1024.ai.qqfm.ui.gallery.SourcePickerScreen
import com.geno1024.ai.qqfm.ui.viewer.ViewerScreen

@Composable
fun QqfmApp(viewModel: GalleryViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ocrScan by OcrScanBus.state.collectAsStateWithLifecycle()
    var viewerId by remember { mutableStateOf<String?>(null) }
    var viewerAnchor by remember { mutableStateOf<Int?>(null) }
    var showAbout by remember { mutableStateOf(false) }
    var showSources by remember { mutableStateOf(false) }

    val items = state.items

    // Resolved without a null gap on purpose. Deleting the image on screen removes it
    // from the list, and a plain lookup would leave the viewer with nothing to show
    // for one frame, which showed up as a flash of the gallery before the next image.
    val start = viewerId?.let { id ->
        items.firstOrNull { it.id == id }
            ?: items.getOrNull(viewerAnchor ?: 0)
            ?: items.lastOrNull()
    }

    // Hoisted out of the gallery so opening an image does not throw the scroll
    // position away with the composition: going back has to land where it left off.
    // Keyed on which trees are being browsed, so each selection remembers its place.
    val gridState = rememberSaveable(state.sourceLabel, saver = LazyGridState.Saver) {
        LazyGridState()
    }

    // Keep the id pointing at the image actually on screen, so the fallback above
    // only ever has to step in on the one deletion it exists for.
    LaunchedEffect(start?.id) {
        if (start != null && viewerId != start.id) viewerId = start.id
    }

    // No tree picked yet: the picker is the whole screen, with nowhere to go back to.
    val choosingSources = showSources || state.sources.isEmpty()

    // The service writes to the OCR database behind the view model's back; while the
    // about page is open, keep its count in step without polling every picture.
    LaunchedEffect(showAbout, ocrScan.status, ocrScan.done / 25) {
        if (showAbout) viewModel.refreshOcrCount()
    }

    BackHandler(enabled = showAbout) { showAbout = false }
    BackHandler(enabled = showSources && state.sources.isNotEmpty()) { showSources = false }
    BackHandler(enabled = !showAbout && !showSources && start != null) { viewerId = null }

    Box(Modifier.fillMaxSize()) {
        when {
            showAbout -> AboutScreen(
                cleanedFiles = state.cleanedFiles,
                cleanedBytes = state.cleanedBytes,
                persistScanIndex = state.persistScanIndex,
                hideFrozen = state.hideFrozen,
                frozenCount = state.frozen.size,
                ocrIndexed = state.ocrIndexed,
                ocrScan = ocrScan,
                onClose = { showAbout = false },
                onPersistScanIndexChange = viewModel::setPersistScanIndex,
                onHideFrozenChange = viewModel::setHideFrozen,
                onClearOcr = viewModel::clearOcrIndex,
                onStartOcr = viewModel::startOcrScan,
                onPauseOcr = viewModel::pauseOcrScan,
                onStopOcr = viewModel::stopOcrScan,
                onRestartOcr = viewModel::restartOcrScan,
                onBackfillOcr = viewModel::backfillOcrScan,
            )
            choosingSources -> SourcePickerScreen(
                initial = if (showSources) state.sources else viewModel.rememberedSources(),
                onConfirm = { chosen ->
                    showSources = false
                    viewModel.chooseSources(chosen)
                },
                onCancel = if (state.sources.isEmpty()) null else ({ showSources = false }),
            )
            else -> {
                // The gallery stays composed underneath the viewer on purpose: swapping
                // the two would drop every grid cell, and coming back would restart their
                // thumbnail loads, showing a wave of spinners over images already decoded.
                GalleryScreen(
                    state = state,
                    viewModel = viewModel,
                    gridState = gridState,
                    onOpen = { viewerId = it.id },
                    onOpenAbout = { showAbout = true },
                    onOpenSources = { showSources = true },
                )

                if (start != null) {
                    ViewerScreen(
                        items = items,
                        start = start,
                        imageStore = viewModel.imageStore,
                        frozenIds = state.frozen,
                        onClose = { viewerId = null },
                        onDelete = { item, index ->
                            viewerAnchor = index
                            viewModel.deleteOne(item)
                        },
                        onToggleFrozen = { viewModel.toggleFrozen(it.id) },
                        onEnsureOcr = viewModel::ensureOcr,
                        onRequestOcr = viewModel::requestOcrText,
                    )
                }
            }
        }
    }
}