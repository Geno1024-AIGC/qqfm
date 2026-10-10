package com.geno1024.ai.qqfm

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.geno1024.ai.qqfm.ui.about.AboutScreen
import com.geno1024.ai.qqfm.ui.gallery.GalleryScreen
import com.geno1024.ai.qqfm.ui.gallery.GalleryViewModel
import com.geno1024.ai.qqfm.ui.update.UpdatesScreen
import com.geno1024.ai.qqfm.ui.viewer.ViewerScreen

@Composable
fun QqfmApp(viewModel: GalleryViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewerId by remember { mutableStateOf<String?>(null) }
    var viewerAnchor by remember { mutableStateOf<Int?>(null) }
    var showUpdates by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }

    val items = state.items
    val start = viewerId?.let { id -> items.firstOrNull { it.id == id } }

    // Deleting the image on screen drops it out of the items list. Rather than
    // closing, the viewer slides onto whatever took its place, which is the next
    // image in the current sort order.
    LaunchedEffect(viewerId, items) {
        if (viewerId == null || items.any { it.id == viewerId }) return@LaunchedEffect
        val anchor = viewerAnchor
        viewerAnchor = null
        viewerId = items.getOrNull(anchor ?: 0)?.id
            ?: items.lastOrNull()?.id
    }

    // The system back button mirrors the in-app back arrows: it closes the viewer
    // and the pushed pages before ever reaching the gallery's default exit.
    BackHandler(enabled = showUpdates) { showUpdates = false }
    BackHandler(enabled = showAbout && !showUpdates) { showAbout = false }
    BackHandler(enabled = !showUpdates && !showAbout && start != null) { viewerId = null }

    when {
        showUpdates -> UpdatesScreen(onClose = { showUpdates = false })
        showAbout -> AboutScreen(
            cleanedFiles = state.cleanedFiles,
            cleanedBytes = state.cleanedBytes,
            onClose = { showAbout = false },
            onOpenUpdates = { showUpdates = true },
        )
        start != null -> ViewerScreen(
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
        )
        else -> GalleryScreen(
            state = state,
            viewModel = viewModel,
            onOpen = { viewerId = it.id },
            onOpenAbout = { showAbout = true },
        )
    }
}