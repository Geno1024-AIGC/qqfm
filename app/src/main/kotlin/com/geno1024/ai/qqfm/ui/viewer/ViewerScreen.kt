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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    items: List<MediaItem>,
    start: MediaItem,
    imageStore: ImageStore,
    frozenIds: Set<String>,
    onClose: () -> Unit,
    onDelete: (MediaItem, Int) -> Unit,
    onToggleFrozen: (MediaItem) -> Unit,
) {
    val startPage = remember(start.id) {
        items.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    var deleting by remember { mutableStateOf(false) }

    var scale by remember(start.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(start.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(start.id) { mutableFloatStateOf(0f) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            onClose()
        } else if (pagerState.currentPage > items.lastIndex) {
            pagerState.scrollToPage(items.lastIndex)
        }
    }

    val currentId = items.getOrNull(pagerState.currentPage)?.id
    val currentFrozen = currentId != null && currentId in frozenIds
    LaunchedEffect(currentId) {
        if (currentId != null) deleting = false
    }

    // The snackbar lives in the gallery, which is not composed while the viewer is
    // up, so a deletion reports its own count here instead of going unseen.
    var notice by remember { mutableStateOf<String?>(null) }
    var lastCount by remember { mutableIntStateOf(items.size) }
    LaunchedEffect(items.size) {
        val gone = lastCount - items.size
        lastCount = items.size
        if (gone > 0) {
            notice = "已删除 $gone 张"
            delay(1500)
            notice = null
        }
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
                            onToggleFrozen(item)
                        },
                    ) {
                        Icon(
                            imageVector = if (currentFrozen) Icons.Filled.Lock else Icons.Outlined.Lock,
                            contentDescription = if (currentFrozen) "取消冻结" else "冻结",
                            tint = if (currentFrozen) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
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
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
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
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offsetX
                                    translationY = offsetY
                                },
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }

                    if (item.id in frozenIds) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = "已冻结",
                            tint = Color.White,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.4f))
                                .padding(6.dp)
                                .size(16.dp),
                        )
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

            val text = notice
            if (text != null) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 84.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black.copy(alpha = 0.7f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
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
 * The stock transform detector consumes single-pointer drags as well, which would
 * swallow the pager's swipe and leave the viewer with no way to reach the next
 * image. This one waits for a second finger and leaves one-finger events untouched,
 * so swiping still pages.
 */
private suspend fun PointerInputScope.detectPinchToZoom(
    onTransform: (zoom: Float, pan: Offset) -> Unit,
) {
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