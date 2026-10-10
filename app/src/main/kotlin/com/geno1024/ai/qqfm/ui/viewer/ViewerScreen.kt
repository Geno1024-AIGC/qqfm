package com.geno1024.ai.qqfm.ui.viewer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaPaths
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    start: MediaItem,
    imageStore: ImageStore,
    onClose: () -> Unit,
    onDelete: (MediaItem, Int) -> Unit,
) {
    val startPage = remember(start.id) {
        items.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    var deleting by remember { mutableStateOf(false) }

    // Reset zoom when page changes
    var scale by remember(start.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(start.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(start.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f; offsetX = 0f; offsetY = 0f
    }

    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            onClose()
        } else if (pagerState.currentPage > items.lastIndex) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    val currentId = items.getOrNull(pagerState.currentPage)?.id
    LaunchedEffect(currentId) {
        if (currentId != null) deleting = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("${pagerState.currentPage + 1} / ${items.size}")
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
IconButton(
                    onClick = {
                        val item = items.getOrNull(pagerState.currentPage) ?: return@IconButton
                        deleting = true
                        onDelete(item, pagerState.currentPage)
                    },
                    enabled = !deleting,
                ) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除")
                    }
                },
            )
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            val bitmap by produceState<Bitmap?>(initialValue = null, key1 = item.id) {
                value = imageStore.full(item, 2048)
            }
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                                detectPinchToZoom { zoom, pan ->
                                    scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    if (scale > 1.01f) {
                                        offsetX += pan.x
                                        offsetY += pan.y
                                    } else {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            }
                            .then(
                                Modifier.graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offsetX,
                                    translationY = offsetY,
                                )
                            ),
                    )
                } else {
                    CircularProgressIndicator(color = Color.White)
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
                            ),
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = relativePath(item),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        text = "${formatBytes(item.size)} · ${formatTimestamp(item.mtime)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
        }
    }
}

/** The item's path relative to the chat picture root, e.g. `chatimg/000/Cache_...`. */
private fun relativePath(item: MediaItem): String =
    item.path.removePrefix(MediaPaths.ROOT).removePrefix("/")

/**
 * Pinch to zoom and two-finger pan.
 *
 * The stock transform detector consumes single-pointer drags too, which would
 * swallow the pager's swipe and leave the viewer with no way to reach the next
 * image. This waits for a second finger first and leaves one-finger events
 * untouched, so swiping still pages.
 */
private suspend fun PointerInputScope.detectPinchToZoom(onTransform: (zoom: Float, pan: Offset) -> Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) continue
            if (event.changes.count { it.pressed } < 2) continue
            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            if (zoom == 1f && pan == Offset.Zero) continue
            onTransform(zoom, pan)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

private const val MAX_SCALE = 5f