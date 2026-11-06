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
import playlistsyncfox.desktop.watch.XmlStatus
import java.time.Duration
import java.time.Instant

// 見た目は書かない（X5-5）。「PC の状態」を写すだけで、操作は PcApp に渡す。

private val errorNames = mapOf(
    "E1" to "接続の失敗", "E2" to "鍵の不一致", "E3" to "容量不足", "E4" to "XML が使えない", "E5" to "ファイルが無い",
    "E6" to "転送の中断", "E7" to "時間切れ", "E8" to "マニフェストが古い", "E9" to "書き込みの失敗",
)

fun trayText(status: TrayStatus): String = when (status) {
    TrayStatus.KeyStorageFailed -> "鍵の保管に失敗"
    TrayStatus.NotPaired -> "ペアリング未済"
    TrayStatus.XmlUnreadable -> "XML が読めない"
    TrayStatus.Syncing -> "同期中"
    TrayStatus.Idle -> "待機中"
}

private fun warningText(warning: Warning): String = when (warning) {
    Warning.XmlStale -> "XML の更新が古い"
    Warning.PortInUse -> "ポートが使用中でサーバーを起動できない"
    Warning.KeyStorageFailed -> "鍵を読み出せません。対処は説明書"
    Warning.KeyMissing -> "この端末の鍵が見つかりません。PC と端末の両方で解除して再ペアリング"
    Warning.SelectionEmpty -> "プレイリストが選ばれていない"
    Warning.CertificateRegenerated -> "証明書を作り直した。端末は再ペアリングが要る"
    Warning.SettingsReset -> "設定を読めず、退避して既定で起動した"
}

@Composable
fun MainWindowContent(app: PcApp, state: PcState) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(trayText(trayStatus(state)), style = MaterialTheme.typography.headlineSmall)
        state.warnings.forEach { Text(warningText(it), color = MaterialTheme.colorScheme.error) }
        PlaylistsSection(app, state)
        DeviceSection(app, state)
        PairingSection(app, state)
        SettingsSection(app)
    }
}

@Composable
private fun PlaylistsSection(app: PcApp, state: PcState) {
    Text("プレイリスト", style = MaterialTheme.typography.titleMedium)
    when (val xml = state.xml) {
        is XmlStatus.Unreadable ->
            Text("XML を読めない。iTunes の詳細設定「Share iTunes Library XML with other applications」を有効にする", color = MaterialTheme.colorScheme.error)
        is XmlStatus.Stale -> Text("XML の更新が古い（${xml.days} 日）")
        else -> Unit
    }
    // 読めない間は前回の内容を保たず空にする（playlists は読めた結果から作る）
    if (state.xml !is XmlStatus.Unreadable) state.playlists.forEach { NodeRow(app, state, it, 0) }
    state.missingIds.forEach { id ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("見つからない：$id")
            Button(onClick = { app.selectPlaylists(withLeafChecked(state.selectedIds, id, false)) }) { Text("外す") }
        }
    }
}

@Composable
private fun NodeRow(app: PcApp, state: PcState, node: PlaylistNode, depth: Int) {
    val leaves = leafIds(node)
    val checked = leaves.isNotEmpty() && leaves.all { it in state.selectedIds }
    Row(Modifier.padding(start = (depth * 16).dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { on ->
            app.selectPlaylists(if (node.children == null) withLeafChecked(state.selectedIds, node.id, on) else withFolderChecked(state.selectedIds, node, on))
        })
        Text(if (node.children == null) "${node.name}（${node.trackCount} 曲）" else node.name)
    }
    node.children?.forEach { NodeRow(app, state, it, depth + 1) }
}

@Composable
private fun DeviceSection(app: PcApp, state: PcState) {
    Text("端末", style = MaterialTheme.typography.titleMedium)
    val device = state.device
    if (device == null) {
        Text("端末がありません")
        return
    }
    Text("${device.displayName}：${if (device.connected) "接続中" else "未接続"}")
    device.lastSync?.let { sync ->
        val failures = sync.failureCounts.entries.joinToString("、") { "${it.key} ${errorNames[it.key].orEmpty()} ${it.value}" }
        Text("最後の同期：${sync.startedAt}　${sync.outcome}　追加 ${sync.counts.added} 置換 ${sync.counts.replaced} 削除 ${sync.counts.removed}　$failures")
    }
    if (device.keyMissing) Text(warningText(Warning.KeyMissing), color = MaterialTheme.colorScheme.error)
    Button(onClick = { app.unpair(device.deviceId) }) { Text("ペアリングを解除") }
}

@Composable
private fun PairingSection(app: PcApp, state: PcState) {
    Text("ペアリング", style = MaterialTheme.typography.titleMedium)
    when (val pairing = state.pairing) {
        is PairingView.Open -> {
            val remaining = Duration.between(Instant.now(), pairing.expiresAt).seconds.coerceAtLeast(0)
            Text("コード：${pairing.code}（残り $remaining 秒、失敗 ${pairing.failedAttempts} 回）", style = MaterialTheme.typography.headlineSmall)
            state.fingerprint?.let { Text("指紋：$it") }
            state.endpoints.forEach { Text("接続先：$it") }
            Button(onClick = { app.showPairingCode() }) { Text("作り直す") }
            Button(onClick = { app.closePairing() }) { Text("閉じる") }
        }
        is PairingView.Locked -> Text("試行が上限に達した。待ってから「コードを表示」")
        PairingView.Closed -> {
            if (state.device != null) Text("端末の解除は「端末」から")
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
    Button(onClick = { xmlMessage = if (app.setXmlPath(xmlPath) == XmlPathResult.Saved) "保存した" else "読めない" }) { Text("確かめて保存") }
    Text(xmlMessage)

    var days by remember { mutableStateOf(settings.staleDays.toString()) }
    OutlinedTextField(days, { days = it; days.toIntOrNull()?.let(app::setStaleDays) }, label = { Text("古いと警告する日数（0 で警告しない）") })

    var port by remember { mutableStateOf(settings.port.toString()) }
    OutlinedTextField(port, { port = it; port.toIntOrNull()?.let(app::setPort) }, label = { Text("サーバーのポート（1024〜65535）") })

    Row(verticalAlignment = Alignment.CenterVertically) {
        var launch by remember { mutableStateOf(settings.launchOnLogin) }
        Checkbox(launch, { launch = it; app.setLaunchOnLogin(it) })
        Text("ログイン時に起動")
    }
}
