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
import com.geno1024.ai.qqfm.ui.gallery.GalleryScreen
import com.geno1024.ai.qqfm.ui.gallery.GalleryViewModel
import com.geno1024.ai.qqfm.ui.update.UpdatesScreen
import com.geno1024.ai.qqfm.ui.viewer.ViewerScreen

@Composable
fun QqfmApp(viewModel: GalleryViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var viewerId by remember { mutableStateOf<String?>(null) }
    var showUpdates by remember { mutableStateOf(false) }

    val items = state.items
    val start = viewerId?.let { id -> items.firstOrNull { it.id == id } }

    // A file deleted in the viewer drops out of the items list; fall back to the
    // gallery once the viewed item is gone.
    LaunchedEffect(viewerId, items) {
        if (viewerId != null && items.none { it.id == viewerId }) viewerId = null
    }

    // The system back button mirrors the in-app back arrows: it closes the viewer
    // and the updates page before ever reaching the gallery's default exit.
    BackHandler(enabled = showUpdates) { showUpdates = false }
    BackHandler(enabled = !showUpdates && start != null) { viewerId = null }

    when {
        showUpdates -> UpdatesScreen(onClose = { showUpdates = false })
        start != null -> ViewerScreen(
            items = items,
            start = start,
            imageStore = viewModel.imageStore,
            onClose = { viewerId = null },
            onDelete = viewModel::deleteOne,
        )
        else -> GalleryScreen(
            state = state,
            viewModel = viewModel,
            onOpen = { viewerId = it.id },
            onOpenUpdates = { showUpdates = true },
        )
    }
}