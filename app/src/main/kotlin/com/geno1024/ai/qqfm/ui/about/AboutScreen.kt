package com.geno1024.ai.qqfm.ui.about

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.geno1024.ai.qqfm.BuildConfig
import com.geno1024.ai.qqfm.data.ConversationIndexer
import com.geno1024.ai.qqfm.data.MediaRepository
import com.geno1024.ai.qqfm.data.NtdbProbe
import com.geno1024.ai.qqfm.data.OcrScanProgress
import com.geno1024.ai.qqfm.data.OcrScanStatus
import com.geno1024.ai.qqfm.ocr.formatDuration
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.formatGrouped
import com.geno1024.ai.qqfm.ui.formatTimestamp
import com.geno1024.ai.qqfm.ui.update.UpdatesSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What this app is, which build it is, what it has cleaned up, and the one setting
 * worth changing here.
 *
 * The tally is kept because it is the one number that says whether the thing is
 * worth having installed at all: the alternative, the free space on the phone, says
 * nothing about which app took it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    cleanedFiles: Long,
    cleanedBytes: Long,
    persistScanIndex: Boolean,
    hideFrozen: Boolean,
    frozenCount: Int,
    ocrIndexed: Int,
    ocrScan: OcrScanProgress,
    onClose: () -> Unit,
    onPersistScanIndexChange: (Boolean) -> Unit,
    onHideFrozenChange: (Boolean) -> Unit,
    onClearOcr: () -> Unit,
    onStartOcr: () -> Unit,
    onPauseOcr: () -> Unit,
    onStopOcr: () -> Unit,
    onRestartOcr: () -> Unit,
    onBackfillOcr: () -> Unit,
) {
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}

    // The service runs whether or not the notification is allowed; asking first just
    // means the progress is visible from the shade when the user says yes.
    fun startScan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onStartOcr()
    }

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(ocrScan.status, ocrScan.runningSince) {
        while (ocrScan.status == OcrScanStatus.RUNNING) {
            now = SystemClock.elapsedRealtime()
            delay(1_000L)
        }
        now = SystemClock.elapsedRealtime()
    }
    val elapsed = ocrScan.elapsedMillis(now)

    // The probe talks to root and finishes in seconds; the report stays on screen
    // so its numbers can be read off, selected and copied afterwards.
    var probeRunning by remember { mutableStateOf(false) }
    var probeReport by remember { mutableStateOf<String?>(null) }
    val probeScope = rememberCoroutineScope()

    // Building the conversation index reads ~17 GB of pictures and every message
    // row; the phase comes through a flow so the callback can stay on the worker
    // thread instead of posting a recomposition per batch.
    val indexer = remember {
        ConversationIndexer(context.applicationContext, MediaRepository(context.applicationContext))
    }
    val indexPhase = remember { MutableStateFlow<ConversationIndexer.Phase?>(null) }
    val phase by indexPhase.collectAsState()
    var indexRunning by remember { mutableStateOf(false) }
    var indexReport by remember { mutableStateOf<String?>(null) }
    val indexScope = rememberCoroutineScope()

    /** The numbers a result carries, worded so a run and a stored result read alike. */
    fun sums(r: ConversationIndexer.Result, senders: Int): String =
        "chatraw ${formatGrouped(r.files.toLong())} 张、算出 md5 ${formatGrouped(r.hashed.toLong())} 张，" +
            "对上会话 ${formatGrouped(r.indexed.toLong())} 张（文件库补充 " +
            "${formatGrouped(r.fromFiles.toLong())} 张、图片/缩略图按文件名 " +
            "${formatGrouped(r.fromNames.toLong())} 条、消息自带路径 " +
            "${formatGrouped(r.named.toLong())} 条），分布在 " +
            "${formatGrouped(r.conversations.toLong())} 个会话，其中 " +
            "${formatGrouped(senders.toLong())} 张知道发送者"

    // The build stores its numbers in the index itself, so opening the page shows the
    // standing result instead of an empty card that only fills in after a run.
    LaunchedEffect(Unit) {
        val (last, senders) = withContext(Dispatchers.IO) {
            indexer.lastResult() to indexer.senderCount()
        }
        // Only fills a card that a run has not already written into.
        if (last != null && indexReport == null) {
            val stamp = if (last.at > 0) "上次建立于 ${formatTimestamp(last.at)}：" else "上次建立："
            indexReport = "📇 $stamp" + sums(last, senders) + "。"
        }
    }

    // The build runs for minutes on the first pass, so the card shows the same kind
    // of bar the OCR pass has: a fraction plus how long it has been going.
    var indexTick by remember { mutableLongStateOf(0L) }
    var indexStartedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(indexRunning) {
        if (!indexRunning) return@LaunchedEffect
        indexStartedAt = SystemClock.elapsedRealtime()
        while (true) {
            indexTick = SystemClock.elapsedRealtime()
            delay(500L)
        }
    }

    /**
     * Starts the index build. The main button takes the cache when it is still valid;
     * [force] is the "rebuild anyway" path next to it.
     */
    fun startIndex(force: Boolean) {
        if (indexRunning) return
        indexRunning = true
        indexReport = null
        indexScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                indexer.build(force = force) { phase -> indexPhase.value = phase }
            }
            indexPhase.value = null
            indexRunning = false
            indexReport = when (outcome) {
                is ConversationIndexer.Outcome.Failed -> "❌ ${outcome.message}"
                is ConversationIndexer.Outcome.Done -> {
                    val r = outcome.result
                    val head = when {
                        r.cached ->
                            "✅ 已是最新：QQ 的两个库和图片缓存都没有变化，这次没有重扫。"
                        r.resumed ->
                            "✅ 增量更新（只扫了新消息）："
                        else ->
                            "✅ 全量重建："
                    }
                    val senders = withContext(Dispatchers.IO) { indexer.senderCount() }
                    val stamp = if (r.at > 0) "。完成于 ${formatTimestamp(r.at)}" else ""
                    head + sums(r, senders) + stamp
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "QQFM",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "浏览、排序并删除 QQ 聊天图片的本地缓存。删除只动本地缓存文件，" +
                    "不会改动聊天记录，所以删掉的消息里图片会显示不出来。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Text(
                text = "累计清理",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cleanedFiles.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatBytes(cleanedBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.End,
                    )
                    Text(
                        text = "已释放空间",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (cleanedFiles == 0L) {
                Text(
                    text = "还没有清理过记录。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                text = "图库",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("缓存扫描结果", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "把扫到的文件列表存在应用缓存里（约 9 MB / 个目录），" +
                            "下次打开图库直接读取。关掉后不再落盘，每次打开都会重新扫描。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = persistScanIndex,
                    onCheckedChange = onPersistScanIndexChange,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("隐藏已锁定的图片", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = if (frozenCount == 0) {
                            "目前没有锁定的图片。"
                        } else {
                            "已锁定 $frozenCount 张，打开开关后它们不会出现在图库里，" +
                                "取消隐藏即可恢复。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = hideFrozen,
                    onCheckedChange = onHideFrozenChange,
                )
            }

            HorizontalDivider()

            Text(
                text = "文字识别",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("已识别 $ocrIndexed 张图片", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "打开图片时会顺带识别其中的文字，存本地，供图库搜索使用。" +
                            "也可以在下面识别所选目录的全部图片；还没识别的图片不会出现在搜索结果里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                TextButton(
                    onClick = onClearOcr,
                    enabled = ocrIndexed > 0 && ocrScan.status != OcrScanStatus.RUNNING,
                ) {
                    Text("清空索引")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = when (ocrScan.status) {
                        OcrScanStatus.IDLE ->
                            "尚未开始。全库识别会遍历所选目录的图片，为搜索建立文字索引。"
                        OcrScanStatus.RUNNING ->
                            if (ocrScan.backfill) "正在补齐未识别的图片…" else "正在识别…"
                        OcrScanStatus.PAUSED ->
                            if (ocrScan.backfill) {
                                "补齐已暂停，进度已保存，可以从断点继续。"
                            } else {
                                "已暂停，进度已保存，可以从断点继续。"
                            }
                        OcrScanStatus.DONE ->
                            if (ocrScan.backfill) "上次补齐已完成。" else "上次识别已完成。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )

                if (ocrScan.total > 0) {
                    LinearProgressIndicator(
                        progress = {
                            (ocrScan.done.toFloat() / ocrScan.total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${ocrScan.done} / ${ocrScan.total}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "用时 ${formatDuration(elapsed)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                } else if (ocrScan.status == OcrScanStatus.RUNNING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (ocrScan.failures > 0) {
                    Text(
                        text = "${ocrScan.failures} 张读取失败，已跳过。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (ocrScan.status) {
                        OcrScanStatus.RUNNING -> {
                            OutlinedButton(onClick = onPauseOcr) { Text("暂停") }
                            TextButton(onClick = onStopOcr) { Text("停止") }
                        }
                        OcrScanStatus.PAUSED -> {
                            Button(onClick = { startScan() }) { Text("继续") }
                            TextButton(onClick = onRestartOcr) { Text("重新开始") }
                            TextButton(onClick = onStopOcr) { Text("停止") }
                        }
                        OcrScanStatus.IDLE, OcrScanStatus.DONE -> {
                            Button(onClick = { startScan() }) {
                                Text(if (ocrScan.status == OcrScanStatus.DONE) "重新识别" else "开始识别")
                            }
                        }
                    }
                }
                if (ocrScan.status != OcrScanStatus.RUNNING) {
                    TextButton(onClick = onBackfillOcr) {
                        Text("补齐未识别（重跑失败和未处理的图片）")
                    }
                }
                Text(
                    text = "按从新到旧的顺序识别所选目录。识别在后台进行，可以离开此页；" +
                        "暂停、退出或系统回收进程后都会从断点继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            Text(
                text = "消息数据库（调试）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "只读探测 QQ 的聊天记录库 nt_msg.db：定位文件、离线推导密钥、" +
                        "尝试解密第一页并列出全部表名。这是“按会话分类图库”的可行性验证，" +
                        "全程只读，不复制、不改动 QQ 的任何数据。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        probeRunning = true
                        probeScope.launch {
                            probeReport = runCatching { NtdbProbe.run() }.getOrElse { error ->
                                "❌ 探针异常：${error.javaClass.simpleName}: ${error.message}"
                            }
                            probeRunning = false
                        }
                    },
                    enabled = !probeRunning,
                ) {
                    Text(
                        text = when {
                            probeRunning -> "正在探测…"
                            probeReport == null -> "开始探测"
                            else -> "重新探测"
                        },
                    )
                }
                val report = probeReport
                if (report != null) {
                    SelectionContainer {
                        Text(
                            text = report,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { startIndex(force = false) },
                        enabled = !indexRunning,
                    ) {
                        Text(if (indexRunning) "正在建立…" else "建立会话索引")
                    }
                    if (!indexRunning) {
                        TextButton(onClick = { startIndex(force = true) }) {
                            Text("强制重建")
                        }
                    }
                }
                if (!indexRunning) {
                    Text(
                        text = "「建立会话索引」只在 QQ 的库或图片缓存变了时重扫，并从上次读到的" +
                            "消息继续；「强制重建」清空水位线，两条消息表从第一条重新读一遍，" +
                            "耗时最久，只在怀疑有漏时才用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                phase?.let { current ->
                    if (current != ConversationIndexer.Phase.Unchanged) {
                        LinearProgressIndicator(
                            progress = { phaseFraction(current) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = phaseLabel(current),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (indexRunning) {
                            Text(
                                text = "用时 ${formatDuration(indexTick - indexStartedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                indexReport?.let { text ->
                    SelectionContainer {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }

            HorizontalDivider()

            UpdatesSection(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}

/** One line describing what the index build is doing, for the debug card. */
private fun phaseLabel(phase: ConversationIndexer.Phase): String = when (phase) {
    ConversationIndexer.Phase.Locating -> "正在定位消息数据库…"
    ConversationIndexer.Phase.Unchanged -> "缓存仍然有效，没有重扫"
    ConversationIndexer.Phase.Naming -> "正在读取会话名称…"
    is ConversationIndexer.Phase.Hashing ->
        if (phase.total == 0) {
            "正在统计需要校验的图片…"
        } else {
            "正在校验图片 md5：${phase.done} / ${phase.total}"
        }
    is ConversationIndexer.Phase.ReadingTrees ->
        if (phase.total > 0) {
            "正在清点 ${phase.tree.id}：${phase.done} / ${phase.total}"
        } else {
            "正在清点 ${phase.tree.id}：${phase.done} 个"
        }
    is ConversationIndexer.Phase.ReadingMessages ->
        "正在读取聊天记录：已扫 ${phase.scanned} 条，命中 ${phase.matched} 张"
    is ConversationIndexer.Phase.ReadingFiles ->
        "正在读取文件库：已扫 ${phase.scanned} 条，命中 ${phase.matched} 张"
}

/**
 * Where the whole build is, 0..1, from the numbers each phase reports.
 *
 * The weights follow how long the passes actually take: hashing ~100k files and
 * reading 2.4 million message rows are minutes each, the other three are seconds.
 */
private fun phaseFraction(phase: ConversationIndexer.Phase): Float = (when (phase) {
    ConversationIndexer.Phase.Locating -> 0.01f
    is ConversationIndexer.Phase.Hashing ->
        0.02f + 0.40f * if (phase.total > 0) phase.done.toFloat() / phase.total else 0f
    is ConversationIndexer.Phase.ReadingTrees ->
        0.42f + 0.06f * if (phase.total > 0) {
            (phase.done.toFloat() / phase.total).coerceIn(0f, 1f)
        } else {
            0f
        }
    is ConversationIndexer.Phase.ReadingMessages -> 0.48f + 0.44f * phase.fraction
    is ConversationIndexer.Phase.ReadingFiles ->
        0.92f + 0.06f * if (phase.total > 0) {
            (phase.scanned.toFloat() / phase.total).coerceIn(0f, 1f)
        } else {
            0f
        }
    ConversationIndexer.Phase.Naming -> 0.98f
    ConversationIndexer.Phase.Unchanged -> 1f
}).coerceIn(0f, 1f)
