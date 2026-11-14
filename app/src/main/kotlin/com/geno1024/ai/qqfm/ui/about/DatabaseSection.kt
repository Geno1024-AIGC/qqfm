package com.geno1024.ai.qqfm.ui.about

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.geno1024.ai.qqfm.data.ConversationIndexer
import com.geno1024.ai.qqfm.data.NtdbProbe
import com.geno1024.ai.qqfm.ocr.formatDuration
import kotlinx.coroutines.launch

/**
 * The standing numbers of the last build (or the last stored run), shaped for the
 * card: one headline, one line of context, and the counts as rows.
 *
 * The counts used to be a single sentence that gained a clause per release
 * ("chatraw N 张、算出 md5 N 张，对上会话 N 张（文件库补充…、图片/缩略图…、
 * 消息自带路径…），shortvideo…，分布在…，其中…") and was hard to scan; a table
 * keeps each number where the eye expects it.
 */
data class IndexSummary(
    val headline: String,
    val ok: Boolean,
    val detail: String,
    val facts: List<Pair<String, String>>,
)

/**
 * The read-only database card and the conversation-index card.
 *
 * Both talk to QQ's message database, but they answer different questions: the
 * probe asks "can this device read it at all", the build asks "what does it say".
 * Keeping them apart means a failed probe is not buried under a build's progress
 * bar, and a good probe is not re-run just to see the index result.
 */
@Composable
fun DatabaseSection(
    indexPhase: ConversationIndexer.Phase?,
    indexRunning: Boolean,
    indexSummary: IndexSummary?,
    indexElapsedMillis: Long,
    onStartIndex: (force: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "消息数据库",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "图库按会话归类依赖 QQ 的消息库。这里先确认它能不能只读解开，再建立索引。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ProbeCard()
        IndexCard(indexPhase, indexRunning, indexSummary, indexElapsedMillis, onStartIndex)
    }
}

/** Runs [NtdbProbe] on demand and shows its findings as a checklist, not a log. */
@Composable
private fun ProbeCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<NtdbProbe.Report?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showTables by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("只读检测", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = "检查 root、消息库位置、密钥和第一页解密。几秒钟，可重复执行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        val result = runCatching { NtdbProbe.run() }.getOrElse { error ->
                            NtdbProbe.Report(
                                ok = false,
                                headline = "检测本身出错了：${error.javaClass.simpleName}: ${error.message}",
                                steps = emptyList(),
                                facts = emptyList(),
                                tables = emptyList(),
                                log = error.stackTraceToString(),
                            )
                        }
                        report = result
                        // A failure is the one time the raw transcript is what the
                        // reader needs, so it opens by itself.
                        showLog = !result.ok
                        showTables = false
                        running = false
                    }
                },
                enabled = !running,
            ) {
                Text(
                    when {
                        running -> "检测中…"
                        report == null -> "开始检测"
                        else -> "重新检测"
                    },
                )
            }
        }

        if (running) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        val current = report
        if (current != null) {
            Text(
                text = (if (current.ok) "✓ " else "✗ ") + current.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            if (current.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    current.steps.forEach { step -> StepRow(step) }
                }
            }

            if (current.facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    current.facts.forEach { fact -> FactRow(fact) }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (current.tables.isNotEmpty()) {
                    TextButton(onClick = { showTables = !showTables }) {
                        Text(if (showTables) "收起表名" else "表名（${current.tables.size}）")
                    }
                }
                TextButton(onClick = { showLog = !showLog }) {
                    Text(if (showLog) "收起日志" else "详细日志")
                }
                if (showLog) {
                    TextButton(
                        onClick = {
                            val clipboard =
                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText("QQFM NtdbProbe", current.log),
                            )
                        },
                    ) {
                        Text("复制日志")
                    }
                }
            }
            if (showTables && current.tables.isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = current.tables.joinToString("  "),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            if (showLog) {
                SelectionContainer {
                    Text(
                        text = current.log,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        Text(
            text = "全程只读：不复制数据库，也不写、不改、不删 QQ 的任何数据。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepRow(step: NtdbProbe.Step) {
    val color = when (step.level) {
        NtdbProbe.Level.OK -> MaterialTheme.colorScheme.primary
        NtdbProbe.Level.WARN -> MaterialTheme.colorScheme.tertiary
        NtdbProbe.Level.FAIL -> MaterialTheme.colorScheme.error
    }
    val mark = when (step.level) {
        NtdbProbe.Level.OK -> "✓"
        NtdbProbe.Level.WARN -> "!"
        NtdbProbe.Level.FAIL -> "✗"
    }
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = mark,
            modifier = Modifier.width(18.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = step.label, style = MaterialTheme.typography.bodyMedium)
            if (step.detail.isNotEmpty()) {
                Text(
                    text = step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun FactRow(fact: NtdbProbe.Fact) {
    Row {
        Text(
            text = fact.label,
            modifier = Modifier.width(84.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = fact.value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/** The index build: the button, its progress, and the numbers the last run left. */
@Composable
private fun IndexCard(
    phase: ConversationIndexer.Phase?,
    running: Boolean,
    summary: IndexSummary?,
    elapsedMillis: Long,
    onStartIndex: (force: Boolean) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column {
            Text("会话索引", style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "把本地图片和视频归到它们所在的会话，画廊的「按会话分类」和" +
                    "大图页的「来自 …」都用这份索引。只读，不改动聊天记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onStartIndex(false) }, enabled = !running) {
                Text(if (running) "正在建立…" else "建立索引")
            }
            if (!running) {
                TextButton(onClick = { onStartIndex(true) }) { Text("强制重建") }
            }
        }

        if (!running) {
            Text(
                text = "「建立索引」会跳过没变化的数据，并从上次读到的位置继续；" +
                    "「强制重建」从头重读全部消息，耗时最长，只在怀疑有漏归时才用。",
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
                if (running) {
                    Text(
                        text = "用时 ${formatDuration(elapsedMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        summary?.let { current ->
            Text(
                text = (if (current.ok) "✓ " else "✗ ") + current.headline,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current.ok) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            if (current.detail.isNotEmpty()) {
                Text(
                    text = current.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (current.facts.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    current.facts.forEach { (label, value) ->
                        Row {
                            Text(
                                text = label,
                                modifier = Modifier.width(112.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = value,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One line describing what the index build is doing. */
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
