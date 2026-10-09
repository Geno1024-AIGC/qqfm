package com.geno1024.ai.qqfm

import androidx.compose.runtime.Composable
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
    var viewerIndex by remember { mutableStateOf<Int?>(null) }
    var showUpdates by remember { mutableStateOf(false) }

    val index = viewerIndex
    when {
        showUpdates -> UpdatesScreen(onClose = { showUpdates = false })
        index == null -> GalleryScreen(
            state = state,
            viewModel = viewModel,
            onOpen = { viewerIndex = it },
            onOpenUpdates = { showUpdates = true },
        )
        else -> ViewerScreen(
            items = state.items,
            startIndex = index,
            imageStore = viewModel.imageStore,
            onClose = { viewerIndex = null },
        )
    }
}
