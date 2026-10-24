package com.geno1024.ai.qqfm.ui.viewer

import android.graphics.drawable.Animatable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.widget.ImageView
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.geno1024.ai.qqfm.data.ImageStore
import com.geno1024.ai.qqfm.data.LoadedImage
import com.geno1024.ai.qqfm.data.MediaItem
import com.geno1024.ai.qqfm.data.MediaPaths
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.formatTimestamp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onEnsureOcr: (MediaItem) -> Unit,
    onRequestOcr: (MediaItem, (String?) -> Unit) -> Unit,
) {
    val startPage = remember(start.id) {
        items.indexOfFirst { it.id == start.id }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = startPage) { items.size }

    var deleting by remember { mutableStateOf(false) }

    var scale by remember(start.id) { mutableFloatStateOf(1f) }
    var offsetX by remember(start.id) { mutableFloatStateOf(0f) }
    var offsetY by remember(start.id) { mutableFloatStateOf(0f) }
    var pageSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(pagerState.currentPage) {
        scale = 1f
        offsetX = 0f
        offsetY = 0f
    }

    /**
     * Keeps the picture inside the frame.
     *
     * Scaled about its centre, so the free space on each side is half the extra
     * width; without this a long screenshot pans straight out of the viewport.
     */
    fun clampPan() {
        if (scale <= RESTING_SCALE) {
            offsetX = 0f
            offsetY = 0f
            return
        }
        val maxX = pageSize.width * (scale - 1f) / 2f
        val maxY = pageSize.height * (scale - 1f) / 2f
        offsetX = offsetX.coerceIn(-maxX, maxX)
        offsetY = offsetY.coerceIn(-maxY, maxY)
    }

    /** Double tap toggles between the whole picture and a closer look at the tap. */
    fun toggleZoom(tap: Offset) {
        if (scale > RESTING_SCALE) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
            return
        }
        val zoomed = DOUBLE_TAP_SCALE
        val centre = Offset(pageSize.width / 2f, pageSize.height / 2f)
        // Keep whatever was under the finger under the finger: the layer scales about
        // the centre, so the offset has to absorb the difference.
        offsetX += (tap.x - centre.x) * (scale - zoomed)
        offsetY += (tap.y - centre.y) * (scale - zoomed)
        scale = zoomed
        clampPan()
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

    // The snackbar lives in the gallery, which sits behind the viewer, so anything
    // the viewer needs to say it says itself.
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    fun showNotice(text: String) {
        scope.launch {
            notice = text
            delay(1500)
            if (notice == text) notice = null
        }
    }

    // The size also moves when the gallery republishes for other reasons, so a plain
    // difference would report deletions that never happened. Only the id we actually
    // asked to delete earns a notice.
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(items.size, pendingDeleteId) {
        val pending = pendingDeleteId ?: return@LaunchedEffect
        if (items.none { it.id == pending }) {
            pendingDeleteId = null
            showNotice("已删除 1 张")
        }
    }

    var ocrText by remember { mutableStateOf<String?>(null) }
    var ocrLoading by remember { mutableStateOf(false) }

    // The file on screen comes from one exact path, so the deletion is confirmed by
    // naming it: browsing several trees at once makes "which one is this" worth
    // answering before anything is removed.

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
                            if (ocrLoading) return@IconButton
                            ocrLoading = true
                            showNotice("识别中…")
                            onRequestOcr(item) { text ->
                                ocrLoading = false
                                ocrText = text?.takeIf { it.isNotBlank() } ?: "(没有识别到文字)"
                            }
                        },
                        enabled = !ocrLoading,
                    ) {
                        Text("文", style = MaterialTheme.typography.titleMedium)
                    }
                    IconButton(
                        onClick = {
                            val item = items.getOrNull(pagerState.currentPage) ?: return@IconButton
                            // A frozen image only goes away once it is unlocked, and
                            // saying so beats a button that silently does nothing.
                            if (item.id in frozenIds) {
                                showNotice("这张已冻结，解锁后才能删除")
                                return@IconButton
                            }
                            pendingDeleteId = item.id
                            onDelete(item, pagerState.currentPage)
                        },
                        enabled = !deleting,
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "删除",
                            tint = if (currentFrozen) {
                                LocalContentColor.current.copy(alpha = 0.35f)
                            } else {
                                LocalContentColor.current
                            },
                        )
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
                LaunchedEffect(item.id) { onEnsureOcr(item) }
                val loaded by produceState<LoadedImage?>(initialValue = null, key1 = item.id) {
                    value = imageStore.viewer(item, 2048)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                        .onSizeChanged { pageSize = it },
                    contentAlignment = Alignment.Center,
                ) {
                    val zoomModifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectImageGestures(
                                canPan = { scale > RESTING_SCALE },
                                onPinch = { zoom, pan ->
                                    scale = (scale * zoom).coerceIn(1f, MAX_SCALE)
                                    offsetX += pan.x
                                    offsetY += pan.y
                                    clampPan()
                                },
                                onPan = { pan ->
                                    offsetX += pan.x
                                    offsetY += pan.y
                                    clampPan()
                                },
                                onDoubleTap = ::toggleZoom,
                            )
                        }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                    when (val image = loaded) {
                        is LoadedImage.Still -> Image(
                            bitmap = image.bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = zoomModifier,
                        )
                        // An ImageView rather than a Compose painter, because that is
                        // what keeps an AnimatedImageDrawable running and invalidating.
                        is LoadedImage.Motion -> AndroidView(
                            factory = { context ->
                                ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                }
                            },
                            update = { view ->
                                view.setImageDrawable(image.drawable)
                                (image.drawable as? Animatable)?.start()
                            },
                            modifier = zoomModifier,
                        )
                        null -> CircularProgressIndicator(color = Color.White)
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

    val shownText = ocrText
    if (shownText != null) {
        AlertDialog(
            onDismissRequest = { ocrText = null },
            title = { Text("识别到的文字") },
            text = {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text(shownText)
                }
            },
            confirmButton = {
                TextButton(onClick = { ocrText = null }) { Text("关闭") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("QQFM OCR", shownText))
                        ocrText = null
                        showNotice("已复制")
                    },
                ) { Text("复制") }
            },
        )
    }
}

/** The item's path relative to the chat picture root, e.g. `chatimg/000/Cache_...`. */
private fun relativePath(item: MediaItem): String =
    item.path.removePrefix(MediaPaths.ROOT).removePrefix("/")

/**
 * Zoom, pan and double tap on one picture, while leaving page swipes alone.
 *
 * One finger has to mean two different things: a swipe that turns the page when the
 * picture is shown as-is, and a drag that walks around it once it is zoomed in. The
 * way out of the conflict is to consume the drag only when zoomed, because the pager
 * is this node's ancestor and simply stops when a child eats the change.
 */
private suspend fun PointerInputScope.detectImageGestures(
    canPan: () -> Boolean,
    onPinch: (zoom: Float, pan: Offset) -> Unit,
    onPan: (pan: Offset) -> Unit,
    onDoubleTap: (position: Offset) -> Unit,
) {
    var lastTapAt = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var moved = false
        do {
            val event = awaitPointerEvent()
            // Something closer to the root already took this gesture: the pager
            // scrolling the page, for one. Letting go of it keeps both usable.
            if (event.changes.any { it.isConsumed }) continue
            val pointers = event.changes.count { it.pressed }
            when {
                pointers >= 2 -> {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != Offset.Zero) {
                        moved = true
                        onPinch(zoom, pan)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
                canPan() -> {
                    val pan = event.calculatePan()
                    if (pan != Offset.Zero) {
                        moved = true
                        onPan(pan)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        } while (event.changes.any { it.pressed })

        if (!moved) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTapAt < DOUBLE_TAP_MS) {
                lastTapAt = 0L
                onDoubleTap(down.position)
            } else {
                lastTapAt = now
            }
        }
    }
}

/** Below this the picture counts as shown as-is, and swipes belong to the pager. */
private const val RESTING_SCALE = 1.01f
private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val DOUBLE_TAP_MS = 300L
