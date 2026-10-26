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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
import com.geno1024.ai.qqfm.data.OcrScanProgress
import com.geno1024.ai.qqfm.data.OcrScanStatus
import com.geno1024.ai.qqfm.ocr.formatDuration
import com.geno1024.ai.qqfm.ui.formatBytes
import com.geno1024.ai.qqfm.ui.update.UpdatesSection
import kotlinx.coroutines.delay

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
                        OcrScanStatus.IDLE -> "尚未开始。全库识别会遍历所选目录的图片，为搜索建立文字索引。"
                        OcrScanStatus.RUNNING -> "正在识别…"
                        OcrScanStatus.PAUSED -> "已暂停，进度已保存，可以从断点继续。"
                        OcrScanStatus.DONE -> "上次识别已完成。"
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
                Text(
                    text = "按从新到旧的顺序识别所选目录。识别在后台进行，可以离开此页；" +
                        "暂停、退出或系统回收进程后都会从断点继续。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider()

            UpdatesSection(modifier = Modifier.padding(bottom = 8.dp))
        }
    }
}