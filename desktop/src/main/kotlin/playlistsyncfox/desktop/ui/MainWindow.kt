package playlistsyncfox.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.filter
import playlistsyncfox.desktop.PcApp
import playlistsyncfox.desktop.XmlPathResult
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.server.SendingTrack
import playlistsyncfox.desktop.settings.LastSync
import playlistsyncfox.desktop.settings.LogLevel
import playlistsyncfox.desktop.settings.ReportOutcome
import playlistsyncfox.desktop.watch.XmlStatus
import playlistsyncfox.shared.result.ProgressReport
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 窓の配置と配色は X5-5。「PC の状態」を写すだけで、操作は PcApp に渡す。

private val errorNames = mapOf(
    "E1" to "接続の失敗", "E2" to "ペアリングの不一致", "E3" to "容量不足", "E4" to "XML を読めない", "E5" to "曲のファイルがない",
    "E6" to "転送の中断", "E7" to "時間切れ", "E8" to "転送中に曲が変更", "E9" to "端末への保存の失敗",
)

private val outcomeWords = mapOf(
    ReportOutcome.SUCCESS to "完了", ReportOutcome.PARTIAL to "一部失敗", ReportOutcome.INTERRUPTED to "中断", ReportOutcome.FAILED to "失敗",
)

private val syncTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

// ログの欄の時刻は PC の地方時（X5-4）
private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

private val LEFT_COLUMN_WIDTH = 300.dp
private val LOG_PANEL_HEIGHT = 270.dp
private val STATUS_BAR_HEIGHT = 22.dp
private val LICENSE_DIALOG_MAX_HEIGHT = 480.dp

fun trayText(status: TrayStatus): String = when (status) {
    TrayStatus.KeyStorageFailed -> "鍵の保管に失敗しました"
    TrayStatus.ServerFailed -> "サーバーを起動できません"
    TrayStatus.NotPaired -> "ペアリングしていません"
    TrayStatus.XmlUnreadable -> "XML を読めません"
    TrayStatus.Syncing -> "同期中"
    TrayStatus.Idle -> "待機中"
}

private fun warningText(warning: Warning): String = when (warning) {
    Warning.XmlStale -> "XML がしばらく更新されていません"
    Warning.PortInUse -> "ポートが使用中のため、サーバーを起動できません"
    Warning.ServerStartFailed -> "サーバーを起動できません。ログを確かめてください"
    Warning.KeyStorageFailed -> "鍵を読み出せません。対処はマニュアルを見てください"
    Warning.KeyMissing -> "端末の鍵が見つかりません。PC と端末の両方で解除してから、もう一度ペアリングしてください"
    Warning.CertificateRegenerated -> "証明書を作り直しました。端末でもう一度ペアリングしてください"
    Warning.SettingsReset -> "設定を読めなかったため、元のファイルを退避して初期設定で起動しました"
}

private fun lastSyncText(sync: LastSync): String {
    val failures = sync.failureCounts.entries.joinToString("、") { "${errorNames[it.key] ?: it.key} ${it.value} 件" }
    return "前回の同期：${syncTimeFormat.format(Instant.parse(sync.startedAt))}　${outcomeWords.getValue(sync.outcome)}　追加 ${sync.counts.added} 更新 ${sync.counts.replaced} 削除 ${sync.counts.removed}　$failures"
}

private fun mib(bytes: Long): String = String.format(Locale.ROOT, "%.1f MiB", bytes / 1_048_576.0)

private fun percent(done: Long, total: Long): Int = if (total <= 0) 0 else (done * 100 / total).toInt().coerceIn(0, 100)

@Composable
fun MainWindowContent(app: PcApp, state: PcState, onOpenLogDir: () -> Unit) {
    val colors = MaterialTheme.appColors
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.weight(1f)) {
            Column(
                Modifier.width(LEFT_COLUMN_WIDTH).fillMaxHeight().background(colors.side).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                state.warnings.forEach { Text(warningText(it), color = colors.caution) }
                XmlSection(state)
                DeviceSection(app, state)
                PairingSection(app, state)
                SettingsSection(app)
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(colors.border))
            Column(Modifier.weight(1f).fillMaxHeight()) {
                ProgressPanel(state, Modifier.weight(1f).fillMaxWidth())
                LogPanel(app, onOpenLogDir)
            }
        }
        StatusBar(app, state)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.06.sp, color = MaterialTheme.appColors.muted)
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}

@Composable
private fun SecondaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) { Text(text, color = MaterialTheme.colorScheme.onBackground) }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(2.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(title)
        content()
    }
}

@Composable
private fun StatusLine(dotColor: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(dotColor)
        Text(text)
    }
}

@Composable
private fun XmlSection(state: PcState) = Section("iTunes ライブラリ") {
    val colors = MaterialTheme.appColors
    when (val xml = state.xml) {
        is XmlStatus.Unreadable ->
            // 日本語名は実物で未確認（Q16-4）。英語名も並べ、どちらの版でも探せるようにする
            Text(
                "XML を読めません。iTunes の［環境設定］→［詳細］で、「iTunes ライブラリ XML をほかのアプリケーションと共有」" +
                    "（英語版は「Share iTunes Library XML with other applications」）に当たる項目をオンにしてください",
                color = MaterialTheme.colorScheme.error,
            )
        is XmlStatus.Stale -> StatusLine(colors.caution, "XML が ${xml.days} 日更新されていません")
        // まだ読んでいない間（起動直後、読めずに再試行中）は、読めた印の緑を出さない
        XmlStatus.Unchecked -> StatusLine(colors.muted, "XML を確かめています")
        XmlStatus.Normal -> StatusLine(colors.success, "XML を読み込めています")
    }
}

@Composable
private fun DeviceSection(app: PcApp, state: PcState) = Section("端末") {
    val colors = MaterialTheme.appColors
    val device = state.device
    if (device == null) {
        Text("ペアリングした端末はありません")
        return@Section
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(if (device.connected) colors.success else colors.muted)
        Text("${device.displayName}：${if (device.connected) "接続中" else "未接続"}")
    }
    device.lastSync?.let { Text(lastSyncText(it), fontSize = 12.sp, color = colors.muted) }
    if (device.keyMissing) Text(warningText(Warning.KeyMissing), color = MaterialTheme.colorScheme.error)
    SecondaryButton("ペアリングを解除する") { app.unpair(device.deviceId) }
}

@Composable
private fun PairingSection(app: PcApp, state: PcState) = Section("ペアリング") {
    when (val pairing = state.pairing) {
        is PairingView.Open -> {
            Text("ペアリングコード：${pairing.code}（残り ${state.pairingSecondsLeft} 秒、失敗 ${pairing.failedAttempts} 回）", style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
            state.fingerprint?.let { fingerprint ->
                Text("確認コード：$fingerprint", fontFamily = FontFamily.Monospace)
                SecondaryButton("確認コードをコピー") { copyToClipboard(fingerprint) }
            }
            state.endpoints.forEach { Text("接続先：$it", fontFamily = FontFamily.Monospace) }
            SecondaryButton("コードを作り直す") { app.showPairingCode() }
            SecondaryButton("閉じる") { app.closePairing() }
        }
        is PairingView.Locked -> Text("入力の回数が上限に達しました。しばらく待ってから「コードを表示」を押してください")
        PairingView.Closed -> {
            if (state.device != null) Text("ペアリング済みの端末があります。新しくペアリングするには、先に「端末」で解除してください")
            PrimaryButton("コードを表示", enabled = state.device == null) { app.showPairingCode() }
        }
    }
}

// クリップボードへ写すのは確認コードだけ。ペアリングコードは写さない（X5-5）
private fun copyToClipboard(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

@Composable
private fun SettingsSection(app: PcApp) = Section("設定") {
    val settings = app.settings.current
    // 未設定のときは、実際に読んでいる既定の場所を出す（空のまま保存すると読めない扱いになるため）
    var xmlPath by remember { mutableStateOf(app.xmlPathInUse) }
    var xmlMessage by remember { mutableStateOf("") }
    OutlinedTextField(xmlPath, { xmlPath = it }, label = { Text("XML の場所") }, modifier = Modifier.fillMaxWidth())
    SecondaryButton("確認して保存する") { xmlMessage = if (app.setXmlPath(xmlPath) == XmlPathResult.Saved) "保存しました" else "この場所の XML を読めません" }
    Text(xmlMessage)

    Row(verticalAlignment = Alignment.CenterVertically) {
        var launch by remember { mutableStateOf(settings.launchOnLogin) }
        Checkbox(launch, { launch = it; app.setLaunchOnLogin(it) })
        Text("Windows へのログイン時に起動する")
    }

    var licenses by remember { mutableStateOf<List<LicenseEntry>?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    SecondaryButton("ライセンス") {
        if (licenses == null) licenses = loadBundledLicenses().orEmpty()
        showLicenses = true
    }
    if (showLicenses) LicenseDialog(licenses.orEmpty()) { showLicenses = false }
}

/** 部品の一覧と、許諾ごとの全文（B4-7、N4-4）。全文は長いので、設定の欄に並べず別の枠に出す。 */
@Composable
private fun LicenseDialog(entries: List<LicenseEntry>, onClose: () -> Unit) {
    val texts = remember(entries) { bundledLicenseTexts(entries) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("ライセンス") },
        text = {
            Column(Modifier.heightIn(max = LICENSE_DIALOG_MAX_HEIGHT).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (entries.isEmpty()) Text("ライセンスの一覧を読み込めません")
                entries.forEach { e ->
                    Column {
                        Text("${e.name} ${e.version}", style = MaterialTheme.typography.bodyMedium)
                        e.licenses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                texts.forEach { t ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.title, fontWeight = FontWeight.Bold)
                        Text("対象：${t.componentNames.joinToString("、")}", fontSize = 12.sp, color = MaterialTheme.appColors.muted)
                        Text(t.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { PrimaryButton("閉じる", onClick = onClose) },
    )
}

// ---- 右：同期の進行状況 ----

@Composable
private fun ProgressPanel(state: PcState, modifier: Modifier) {
    val colors = MaterialTheme.appColors
    Column(modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("同期の進行状況", style = MaterialTheme.typography.headlineSmall)
            if (state.syncing) {
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("同期中", fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        val progress = state.progress
        val sending = state.sending
        if (progress != null) OverallProgress(progress)
        if (sending != null) SendingProgress(sending)
        if (progress == null && sending == null) {
            Text(trayText(trayStatus(state)))
            state.device?.lastSync?.let { Text(lastSyncText(it), fontSize = 12.sp, color = colors.muted) }
        }
    }
}

@Composable
private fun OverallProgress(progress: ProgressReport) {
    val colors = MaterialTheme.appColors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("全体")
            Text("${percent(progress.doneBytes, progress.totalBytes)}%", fontFamily = FontFamily.Monospace)
        }
        Bar(progress.doneBytes, progress.totalBytes, 8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Text("${progress.doneTracks} / ${progress.totalTracks} 曲", fontSize = 12.sp, color = colors.muted, fontFamily = FontFamily.Monospace)
            Text("${mib(progress.doneBytes)} / ${mib(progress.totalBytes)}", fontSize = 12.sp, color = colors.muted, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
private fun SendingProgress(sending: SendingTrack) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("送信中：${sending.displayName}", modifier = Modifier.weight(1f))
            Text("${percent(sending.sentBytes, sending.size)}%", fontFamily = FontFamily.Monospace)
        }
        Bar(sending.sentBytes, sending.size, 4.dp)
        Text("${mib(sending.sentBytes)} / ${mib(sending.size)}", fontSize = 12.sp, color = MaterialTheme.appColors.muted, fontFamily = FontFamily.Monospace)
    }
}

/** 割合の棒。`done` が `total` を超えても 100% で止める。 */
@Composable
private fun Bar(done: Long, total: Long, thickness: Dp) {
    val colors = MaterialTheme.appColors
    Box(Modifier.fillMaxWidth().height(thickness).clip(RoundedCornerShape(thickness / 2)).background(colors.track)) {
        Box(Modifier.fillMaxWidth(percent(done, total) / 100f).fillMaxHeight().background(colors.accent))
    }
}

// ---- 右下：ログの欄 ----

@Composable
private fun LogPanel(app: PcApp, onOpenLogDir: () -> Unit) {
    val colors = MaterialTheme.appColors
    val lines by app.logLines.collectAsState()
    val listState = rememberLazyListState()

    // 最下段にいる間だけ新しい行に合わせて送る。利用者が上へ送って止めたら追わない
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { !it }.collect { follow = !listState.canScrollForward }
    }
    LaunchedEffect(lines) {
        if (follow && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Column(Modifier.fillMaxWidth().height(LOG_PANEL_HEIGHT)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(Modifier.fillMaxWidth().height(32.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight()) {
                Text("ログ", fontSize = 11.sp, modifier = Modifier.align(Alignment.Center))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(1.dp).background(colors.accent))
            }
            Box(Modifier.weight(1f))
            Text("ログの場所を開く", fontSize = 11.sp, color = colors.muted, modifier = Modifier.clickable(onClick = onOpenLogDir))
        }
        LazyColumn(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = 10.dp), state = listState) {
            itemsIndexed(lines) { _, line ->
                val levelColor = when (line.level) {
                    LogLevel.Info -> colors.success
                    LogLevel.Warn -> colors.caution
                    LogLevel.Error -> MaterialTheme.colorScheme.error
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(logTimeFormat.format(line.at), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.muted)
                    Text(line.level.label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = levelColor, modifier = Modifier.width(44.dp))
                    Text(line.module, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.muted, modifier = Modifier.width(64.dp))
                    Text(line.message, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
                }
            }
        }
    }
}

// ---- 最下段：状態の帯 ----

@Composable
private fun StatusBar(app: PcApp, state: PcState) {
    Row(
        Modifier.fillMaxWidth().height(STATUS_BAR_HEIGHT).background(MaterialTheme.appColors.statusBar).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(trayText(trayStatus(state)), fontSize = 12.sp, color = Color.White)
        Text("ポート ${app.settings.current.port}", fontSize = 12.sp, color = Color.White)
        Text("端末 ${if (state.device != null) 1 else 0} 台", fontSize = 12.sp, color = Color.White)
    }
}
