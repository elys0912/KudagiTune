package playlistsyncfox.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import playlistsyncfox.android.log.LogEntry
import playlistsyncfox.android.log.LogLevel

/** S3（X7-1-4）。上から、見出しと全体の棒、曲ごとの一覧（残りの高さ）、ログの欄（150 dp）、「中断する」。 */
@Composable
internal fun ProgressScreen(model: AppViewModel, current: Screen.Progress, actions: ScreenActions, modifier: Modifier) {
    val colors = LocalPsfoxColors.current
    val progress by model.progress.collectAsState()
    val logLines by model.logPane.collectAsState()
    val ui = progress
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            current.notice?.let { Label(it, bold = true) }
            if (ui == null) {
                Label("開始を待っています")
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                    Label(ui.heading, bold = true)
                    Label("${(ui.fraction * 100).toInt()}%", mono = true)
                }
                Bar(ui.fraction, 8)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    val failed = if (ui.failedCount > 0) "　失敗 ${ui.failedCount}" else ""
                    BasicText("${ui.doneCount} / ${ui.totalCount} 曲$failed", style = TextStyle(color = colors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    BasicText("${formatBytes(ui.doneBytes)} / ${formatBytes(ui.totalBytes)}", style = TextStyle(color = colors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
                ui.nearLimitNote?.let { Label(it, bold = true) }
                ui.shortageBytes?.let {
                    Label("空き容量が ${formatBytes(it)} 足りません", bold = true)
                    Action("ストレージの設定を開く", actions.onOpenStorageSettings)
                }
                ui.notice?.let {
                    Label(it.title, bold = true)
                    Label(it.guidance)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))

        val rows = ui?.tracks.orEmpty()
        val listState = rememberLazyListState()
        val nowIndex = rows.indexOfFirst { it.mark == TrackMark.Transferring }
        val nowId = rows.getOrNull(nowIndex)?.id
        // 転送中の行が変わったとき、その行が見える位置へ送る。利用者が触っている間は送らない
        LaunchedEffect(nowId) {
            if (nowIndex >= 0 && !listState.isScrollInProgress) listState.animateScrollToItem((nowIndex - 2).coerceAtLeast(0))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(rows, key = { it.id }) { TrackRowItem(it) }
        }

        LogPane(logLines)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Action("中断する", model::cancelSync)
        }
    }
}

@Composable
private fun TrackRowItem(row: TrackRowUi) {
    val colors = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(if (row.highlighted) colors.transferringRow else Color.Transparent).padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MarkIcon(row.mark)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(
                        row.title,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = if (row.dimmed) colors.muted else colors.text, fontSize = 14.sp),
                    )
                    BasicText(row.sizeText, style = TextStyle(color = colors.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
                row.fraction?.let { Bar(it, 4) }
                row.reason?.let { BasicText(it, style = TextStyle(color = colors.failure, fontSize = 12.sp)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rowLine))
    }
}

/** 状態の印。完了＝チェック、転送中＝下向きの矢印、待機＝時計、失敗＝×。 */
@Composable
private fun MarkIcon(mark: TrackMark) {
    val colors = LocalPsfoxColors.current
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = 2.2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color) =
            drawLine(color, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), strokeWidth = stroke, cap = StrokeCap.Round)
        when (mark) {
            TrackMark.Done -> {
                line(0.2f, 0.52f, 0.42f, 0.74f, colors.success)
                line(0.42f, 0.74f, 0.82f, 0.28f, colors.success)
            }
            TrackMark.Transferring -> {
                line(0.5f, 0.15f, 0.5f, 0.85f, colors.accent)
                line(0.22f, 0.57f, 0.5f, 0.85f, colors.accent)
                line(0.78f, 0.57f, 0.5f, 0.85f, colors.accent)
            }
            TrackMark.Waiting -> {
                drawCircle(colors.muted, radius = 0.38f * w, center = Offset(w / 2, w / 2), style = Stroke(stroke))
                line(0.5f, 0.3f, 0.5f, 0.5f, colors.muted)
                line(0.5f, 0.5f, 0.65f, 0.6f, colors.muted)
            }
            TrackMark.Failed -> {
                line(0.22f, 0.22f, 0.78f, 0.78f, colors.failure)
                line(0.78f, 0.22f, 0.22f, 0.78f, colors.failure)
            }
        }
    }
}

/** ログの欄（X7-1-4）。新しい行が下。時刻とメッセージ。警告とエラーの行はその色。 */
@Composable
private fun LogPane(lines: List<LogEntry>) {
    val colors = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth().height(150.dp).background(colors.logPanel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Box(Modifier.fillMaxWidth().height(29.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicText("ログ", style = TextStyle(color = colors.muted, fontSize = 11.sp, letterSpacing = 0.5.sp))
        }
        // 下を起点に積むので、新しい行が来ても末尾が見えたままになる
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), reverseLayout = true) {
            items(lines.asReversed()) { entry ->
                val color = when (entry.level) {
                    LogLevel.Info -> colors.text
                    LogLevel.Warn -> colors.warning
                    LogLevel.Error -> colors.failure
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(logTime(entry), style = TextStyle(color = colors.muted, fontSize = 11.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace))
                    BasicText(
                        logMessage(entry),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(color = color, fontSize = 11.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}
