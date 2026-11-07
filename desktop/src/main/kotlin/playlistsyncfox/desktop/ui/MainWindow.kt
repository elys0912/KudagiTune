package playlistsyncfox.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import playlistsyncfox.desktop.PcApp
import playlistsyncfox.desktop.XmlPathResult
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.settings.ReportOutcome
import playlistsyncfox.desktop.watch.XmlStatus
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 見た目は書かない（X5-5）。「PC の状態」を写すだけで、操作は PcApp に渡す。

private val errorNames = mapOf(
    "E1" to "接続の失敗", "E2" to "ペアリングの不一致", "E3" to "容量不足", "E4" to "XML を読めない", "E5" to "曲のファイルがない",
    "E6" to "転送の中断", "E7" to "時間切れ", "E8" to "転送中に曲が変更", "E9" to "端末への保存の失敗",
)

private val outcomeWords = mapOf(
    ReportOutcome.SUCCESS to "完了", ReportOutcome.PARTIAL to "一部失敗", ReportOutcome.INTERRUPTED to "中断", ReportOutcome.FAILED to "失敗",
)

private val syncTimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

fun trayText(status: TrayStatus): String = when (status) {
    TrayStatus.KeyStorageFailed -> "鍵の保管に失敗しました"
    TrayStatus.NotPaired -> "ペアリングしていません"
    TrayStatus.XmlUnreadable -> "XML を読めません"
    TrayStatus.Syncing -> "同期中"
    TrayStatus.Idle -> "待機中"
}

private fun warningText(warning: Warning): String = when (warning) {
    Warning.XmlStale -> "XML がしばらく更新されていません"
    Warning.PortInUse -> "ポートが使用中のため、サーバーを起動できません"
    Warning.KeyStorageFailed -> "鍵を読み出せません。対処はマニュアルを見てください"
    Warning.KeyMissing -> "端末の鍵が見つかりません。PC と端末の両方で解除してから、もう一度ペアリングしてください"
    Warning.CertificateRegenerated -> "証明書を作り直しました。端末でもう一度ペアリングしてください"
    Warning.SettingsReset -> "設定を読めなかったため、元のファイルを退避して初期設定で起動しました"
}

@Composable
fun MainWindowContent(app: PcApp, state: PcState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(trayText(trayStatus(state)), style = MaterialTheme.typography.headlineSmall)
        state.warnings.forEach { Text(warningText(it), color = MaterialTheme.colorScheme.error) }
        XmlSection(state)
        DeviceSection(app, state)
        PairingSection(app, state)
        SettingsSection(app)
    }
}

@Composable
private fun XmlSection(state: PcState) {
    Text("iTunes ライブラリ", style = MaterialTheme.typography.titleMedium)
    when (val xml = state.xml) {
        is XmlStatus.Unreadable ->
            Text("XML を読めません。iTunes の［環境設定］→［詳細］で「iTunes ライブラリ XML をほかのアプリケーションと共有」をオンにしてください", color = MaterialTheme.colorScheme.error)
        is XmlStatus.Stale -> Text("XML が ${xml.days} 日更新されていません")
        else -> Text("XML を読み込めています")
    }
}

@Composable
private fun DeviceSection(app: PcApp, state: PcState) {
    Text("端末", style = MaterialTheme.typography.titleMedium)
    val device = state.device
    if (device == null) {
        Text("ペアリングした端末はありません")
        return
    }
    Text("${device.displayName}：${if (device.connected) "接続中" else "未接続"}")
    device.lastSync?.let { sync ->
        val failures = sync.failureCounts.entries.joinToString("、") { "${errorNames[it.key] ?: it.key} ${it.value} 件" }
        Text("前回の同期：${syncTimeFormat.format(Instant.parse(sync.startedAt))}　${outcomeWords.getValue(sync.outcome)}　追加 ${sync.counts.added} 更新 ${sync.counts.replaced} 削除 ${sync.counts.removed}　$failures")
    }
    if (device.keyMissing) Text(warningText(Warning.KeyMissing), color = MaterialTheme.colorScheme.error)
    Button(onClick = { app.unpair(device.deviceId) }) { Text("ペアリングを解除する") }
}

@Composable
private fun PairingSection(app: PcApp, state: PcState) {
    Text("ペアリング", style = MaterialTheme.typography.titleMedium)
    when (val pairing = state.pairing) {
        is PairingView.Open -> {
            val remaining = Duration.between(Instant.now(), pairing.expiresAt).seconds.coerceAtLeast(0)
            Text("ペアリングコード：${pairing.code}（残り $remaining 秒、失敗 ${pairing.failedAttempts} 回）", style = MaterialTheme.typography.headlineSmall)
            state.fingerprint?.let { Text("確認コード：$it") }
            state.endpoints.forEach { Text("接続先：$it") }
            Button(onClick = { app.showPairingCode() }) { Text("コードを作り直す") }
            Button(onClick = { app.closePairing() }) { Text("閉じる") }
        }
        is PairingView.Locked -> Text("入力の回数が上限に達しました。しばらく待ってから「コードを表示」を押してください")
        PairingView.Closed -> {
            if (state.device != null) Text("ペアリング済みの端末があります。新しくペアリングするには、先に「端末」で解除してください")
            Button(onClick = { app.showPairingCode() }, enabled = state.device == null) { Text("コードを表示") }
        }
    }
}

@Composable
private fun SettingsSection(app: PcApp) {
    Text("設定", style = MaterialTheme.typography.titleMedium)
    val settings = app.settings.current
    var xmlPath by remember { mutableStateOf(settings.xmlPath.orEmpty()) }
    var xmlMessage by remember { mutableStateOf("") }
    OutlinedTextField(xmlPath, { xmlPath = it }, label = { Text("XML の場所") }, modifier = Modifier.fillMaxWidth())
    Button(onClick = { xmlMessage = if (app.setXmlPath(xmlPath) == XmlPathResult.Saved) "保存しました" else "この場所の XML を読めません" }) { Text("確認して保存する") }
    Text(xmlMessage)

    Row(verticalAlignment = Alignment.CenterVertically) {
        var launch by remember { mutableStateOf(settings.launchOnLogin) }
        Checkbox(launch, { launch = it; app.setLaunchOnLogin(it) })
        Text("Windows へのログイン時に起動する")
    }

    var licenses by remember { mutableStateOf<List<LicenseEntry>?>(null) }
    var showLicenses by remember { mutableStateOf(false) }
    Button(onClick = {
        showLicenses = !showLicenses
        if (showLicenses && licenses == null) licenses = loadBundledLicenses().orEmpty()
    }) { Text("ライセンス") }
    if (showLicenses) {
        val entries = licenses.orEmpty()
        if (entries.isEmpty()) Text("ライセンスの一覧を読み込めません")
        entries.forEach { e ->
            Text("${e.name} ${e.version}", style = MaterialTheme.typography.bodyMedium)
            e.licenses.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
