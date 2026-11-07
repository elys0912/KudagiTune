package playlistsyncfox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.sync.SyncServices
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.leafIds

// 画面は状態を描くだけ（X7-1-1）。見た目（配色、配置）は設計の外なので最小限にとどめる。

/** 画面から Activity（権限、共有、削除の同意）へ頼む操作。 */
class ScreenActions(
    val onSyncButton: () -> Unit,
    val onNotificationHelp: () -> Unit,
    val onRequestRead: () -> Unit,
    val onDeleteLeftovers: (List<StoredFile>) -> Unit,
    val onShareLog: (String) -> Unit,
    val onOpenStorageSettings: () -> Unit,
    val licenses: () -> List<LicenseEntry>?,
)

@Composable
fun AppScreen(model: AppViewModel, actions: ScreenActions, notificationsAllowed: () -> Boolean, readGranted: () -> Boolean) {
    val screen by model.screen.collectAsState()
    val message by model.message.collectAsState()
    Box(Modifier.fillMaxSize().background(Color.White).padding(16.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val current = screen) {
                Screen.Home -> HomeScreen(model, actions, notificationsAllowed, readGranted)
                Screen.Pairing -> PairingScreen(model)
                is Screen.Progress -> ProgressScreen(model, current, actions)
                is Screen.Result -> ResultScreen(model, actions)
                Screen.Settings -> SettingsScreen(model, actions)
                is Screen.Selection -> SelectionScreen(model, current)
                Screen.Log -> LogScreen(model, actions)
                Screen.Licenses -> LicensesScreen(model, actions)
            }
            message?.let { Label(it) }
        }
    }
}

@Composable
private fun Label(text: String, bold: Boolean = false) =
    BasicText(text, style = TextStyle(color = Color.Black, fontSize = 16.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))

@Composable
private fun Action(label: String, onClick: () -> Unit) =
    BasicText(
        label,
        modifier = Modifier.border(1.dp, Color.Black).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        style = TextStyle(color = Color.Black, fontSize = 16.sp),
    )

@Composable
private fun Field(value: String, onChange: (String) -> Unit) =
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
        modifier = Modifier.fillMaxWidth().border(1.dp, Color.Gray).padding(8.dp),
    )

@Composable
private fun Confirm(text: String, ok: String, onOk: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.border(2.dp, Color.Black).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(text)
        Action(ok, onOk)
        Action("取り消す", onCancel)
    }
}

@Composable
private fun Back(model: AppViewModel) = Action("戻る", model::back)

@Composable
private fun HomeScreen(model: AppViewModel, actions: ScreenActions, notificationsAllowed: () -> Boolean, readGranted: () -> Boolean) {
    val state by model.home.collectAsState()
    when (state.kind) {
        HomeKind.Unpaired -> Label("ペアリングしてください")
        else -> {
            Label(state.pcName.orEmpty(), bold = true)
            Label("接続先：${state.endpoint}")
            val last = state.last
            if (state.kind == HomeKind.Syncing) Label("同期中")
            if (last == null) {
                Label("まだ同期していません")
            } else {
                Label("最後の同期：${formatTime(last.atMillis)} ${last.word}")
            }
            if (state.selectedCount == 0) {
                Label("同期するプレイリストを選んでください")
            } else if (state.playlists.isEmpty()) {
                Label("選んだプレイリスト：${state.selectedCount} 件（まだ同期していません）")
            } else {
                state.playlists.forEach { Label("${it.name}（${it.trackCount} 曲）") }
            }
        }
    }
    when (state.kind) {
        HomeKind.Syncing -> Action("進み具合を見る", model::showProgress)
        HomeKind.Unpaired -> Action("ペアリング", actions.onSyncButton)
        else -> Action("同期", actions.onSyncButton)
    }
    if (state.kind != HomeKind.Unpaired) Action("プレイリストを選ぶ") { model.openSelection() }
    if (state.last != null) Action("結果を見る") { model.openResult(null) }
    Action("設定") { model.openSettings(notificationsAllowed(), readGranted()) }
    RemovalConfirm(model)
}

/** 「消す」の確認。同期ボタンは S1 と S4 にあるので、両方に置く。 */
@Composable
private fun RemovalConfirm(model: AppViewModel) {
    val confirm by model.confirm.collectAsState()
    confirm?.let {
        Confirm(
            text = "${it.toRemove} 曲を端末から消す。続けるか",
            ok = "消して同期する",
            onOk = { model.answerConfirm(true) },
            onCancel = { model.answerConfirm(false) },
        )
    }
}

@Composable
private fun PairingScreen(model: AppViewModel) {
    val state by model.pairing.state.collectAsState()
    var manual by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    when (val stage = state.stage) {
        PairingStage.Discovering -> {
            Label("PC を探している", bold = true)
            state.found.forEach { pc -> Action(pc.serviceName) { model.pairing.select(pc) } }
            if (state.notFound) Label("見つからない。宛先を入力してほしい", bold = true)
            Label("宛先（IP、または名前。ポートは省略できる）")
            Field(manual) { manual = it }
            state.manualError?.let { Label(it) }
            Action("宛先を決める") { model.pairing.submitManual(manual) }
        }
        is PairingStage.Target -> {
            Label("宛先：${stage.target.name}", bold = true)
            stage.fingerprint?.let { Label("PC の確認コード：$it") }
            Label("PC に出ているペアリングコード（$CODE_LENGTH 桁）")
            Field(code) { code = it }
            stage.codeError?.let { Label(it) }
            if (!state.sending) {
                Action("ペアリングコードを送る") {
                    val sent = code
                    code = ""
                    model.pairing.submitCode(sent)
                }
            }
        }
        is PairingStage.Done -> {
            Label("ペアリングが済んだ：${stage.pcName}", bold = true)
            Label("違うと思えば、PC の窓の「端末」とこのアプリの設定の両方で解除する")
            if (stage.showCleanupHint) Label("以前このアプリを使っていたなら、先に以前の曲の整理を")
        }
        PairingStage.AttemptsExhausted -> Label("試行の上限に達した。PC でコードを作り直してほしい")
        PairingStage.Waiting -> Label("しばらく待ってからやり直してほしい")
        PairingStage.DeviceExists -> Label("PC に登録が残っている。PC の窓の「端末」で解除してから")
        is PairingStage.Failed -> {
            Label(stage.notice.title, bold = true)
            Label(stage.notice.guidance)
            Action("やり直す", model.pairing::retry)
        }
    }
    if (state.stage is PairingStage.Done) Action("一覧へ", model::back) else Back(model)
}

@Composable
private fun SelectionScreen(model: AppViewModel, current: Screen.Selection) {
    val stage by model.selection.stage.collectAsState()
    current.notice?.let { Label(it, bold = true) }
    when (val s = stage) {
        SelectionStage.Loading -> Label("読み込み中")
        is SelectionStage.Loaded -> {
            s.nodes.forEach { NodeRow(model, s, it, 0) }
            s.missing.forEach { row ->
                Label("見つからない：${row.name}")
                Action("外す") { model.selection.remove(row.playlistId) }
            }
        }
        is SelectionStage.Failed -> {
            Label(catalogWord(s.reason), bold = true)
            s.rows.forEach { row ->
                Label(row.name)
                Action("外す") { model.selection.remove(row.playlistId) }
            }
            Action("やり直す", model.selection::load)
        }
    }
    Back(model)
}

@Composable
private fun NodeRow(model: AppViewModel, stage: SelectionStage.Loaded, node: PlaylistNode, depth: Int) {
    var open by remember { mutableStateOf(true) }
    val children = node.children
    if (children == null) {
        val on = node.id in stage.selectedIds
        Label("${"　".repeat(depth)}${if (on) "[x]" else "[ ]"} ${node.name}（${node.trackCount ?: 0} 曲）")
        Action(if (on) "外す：${node.name}" else "選ぶ：${node.name}") { model.selection.setLeaf(node.id, !on) }
    } else {
        val leaves = leafIds(node)
        val all = leaves.isNotEmpty() && leaves.all { it in stage.selectedIds }
        Label("${"　".repeat(depth)}${if (open) "▼" else "▶"} ${node.name}")
        Action(if (open) "閉じる：${node.name}" else "開く：${node.name}") { open = !open }
        Action(if (all) "配下をすべて外す：${node.name}" else "配下をすべて選ぶ：${node.name}") { model.selection.setFolder(node, !all) }
        if (open) children.forEach { NodeRow(model, stage, it, depth + 1) }
    }
}

@Composable
private fun ProgressScreen(model: AppViewModel, current: Screen.Progress, actions: ScreenActions) {
    val progress by model.progress.collectAsState()
    current.notice?.let { Label(it, bold = true) }
    val ui = progress
    if (ui == null) {
        Label("開始を待っている")
    } else {
        Label(ui.heading, bold = true)
        Label("${ui.doneCount} / ${ui.totalCount} 曲　${formatBytes(ui.doneBytes)} / ${formatBytes(ui.totalBytes)}")
        ui.currentTitle?.let { Label(it) }
        ui.nearLimitNote?.let { Label(it, bold = true) }
        ui.shortageBytes?.let {
            Label("空き容量が ${formatBytes(it)} 足りない", bold = true)
            Action("空きを作る画面を開く", actions.onOpenStorageSettings)
        }
        ui.notice?.let {
            Label(it.title, bold = true)
            Label(it.guidance)
        }
    }
    Action("中断", model::cancelSync)
}

@Composable
private fun ResultScreen(model: AppViewModel, actions: ScreenActions) {
    val screen by model.result.collectAsState()
    val state = screen.current
    if (state == null) {
        Label("結果がまだ無い")
    } else {
        Label("${state.word}（${state.trigger}）　${formatTime(state.atMillis)}", bold = true)
        state.removalNote?.let { Label(it, bold = true) }
        if (state.notRemoved > 0) Label("消さなかった：${state.notRemoved} 件")
        state.counts?.let { Label("追加 ${it.added}　置き換え ${it.replaced}　削除 ${it.removed}　失敗 ${it.failed}") }
        state.bytes?.let { Label("転送 ${formatBytes(it)}") }
        state.errorWord?.let { Label("原因：$it") }
        state.noticeLabel?.let { Label("E2 の内容：$it") }
        Issues("除外", state.excluded)
        Issues("再生できない", state.unplayable)
        Issues("失敗", state.failed)
    }
    Action("もう一度同期する", actions.onSyncButton)
    RemovalConfirm(model)
    if (screen.past.size > 1) {
        Label("過去の実行", bold = true)
        screen.past.forEach { Action("${formatTime(it.atMillis)} ${it.word}") { model.openResult(it.runId) } }
    }
    Back(model)
}

@Composable
private fun Issues(title: String, list: IssueList) {
    if (list.lines.isEmpty()) return
    Label("$title（${list.lines.size + list.more}）", bold = true)
    list.lines.forEach { Label(if (it.detail.isEmpty()) it.title else "${it.title}　${it.detail}") }
    if (list.more > 0) Label("ほか ${list.more} 件")
}

@Composable
private fun SettingsScreen(model: AppViewModel, actions: ScreenActions) {
    val state by model.settings.collectAsState()
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmRelocated by remember { mutableStateOf(false) }
    val syncing = isSyncing(SyncServices.progress.collectAsState().value)
    val ui = state
    if (ui == null) {
        Label("読み込み中")
        Back(model)
        return
    }
    Label("自動起動：${if (ui.autoEnabled) "入" else "切"}（Wi-Fi など従量制でない回線で、約 6 時間ごと）")
    Action(if (ui.autoEnabled) "切る" else "入れる") { model.toggleAuto(!ui.autoEnabled) }
    Label("PC に無い曲を端末から消す：${if (ui.deleteMissing) "入" else "切"}（プレイリストから外した曲も含む。既定は切）")
    Action(if (ui.deleteMissing) "切る" else "入れる") { model.setDeleteMissing(!ui.deleteMissing) }
    Label("保存先：${ui.saveFolder}")
    ui.storage.options.forEach { option ->
        val chosen = option.volume == ui.storage.selected
        Label("${if (chosen) "[x]" else "[ ]"} ${option.label}")
        if (!chosen && option.available) Action("${option.label}にする") { model.setStorage(option.volume) }
    }
    Label("保存先を替えると、次の同期で新しい場所へ全曲を取り直す。前の場所の曲は自動では消えない")
    if (ui.relocatedCount > 0) {
        Label("以前の場所に残る曲とプレイリスト：${ui.relocatedCount} 件")
        if (syncing) Label("同期が動いている間は消せない") else Action("以前の場所の曲を消す") { confirmRelocated = true }
        if (confirmRelocated) {
            Confirm("以前の場所の、このアプリが書いた ${ui.relocatedCount} 件を消す。続けるか", "消す", { confirmRelocated = false; model.deleteRelocated() }, { confirmRelocated = false })
        }
    }
    if (ui.notificationsOff) {
        Label("通知が出ない")
        Action("通知の許可", actions.onNotificationHelp)
    }
    if (ui.pcName != null) {
        Label("ペアリング：${ui.pcName}", bold = true)
        ui.fingerprint?.let { Label("確認コード：$it") }
        Action("解除") { confirmUnpair = true }
        if (confirmUnpair) {
            Confirm("ペアリングを解除する。曲とプレイリストは端末に残る。PC の窓の「端末」でも解除してほしい", "解除する", { confirmUnpair = false; model.unpair() }, { confirmUnpair = false })
        }
    }
    Label("以前の曲の整理", bold = true)
    when (val cleanup = ui.cleanup) {
        CleanupState.NeedsPermission -> {
            Label("権限が要る")
            Action("権限を許可して一覧を出す", actions.onRequestRead)
        }
        CleanupState.Denied -> Label("権限が無いため整理できない")
        is CleanupState.Found -> Cleanup(cleanup.items, actions)
    }
    Label("ログ：${formatBytes(ui.logBytes)}", bold = true)
    Action("ログを見る", model::openLog)
    Action("ライセンス", model::openLicenses)
    Action("一覧へ", model::back)
}

@Composable
private fun Cleanup(items: List<StoredFile>, actions: ScreenActions) {
    if (items.isEmpty()) {
        Label("整理するものは無い")
        return
    }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    Label("${items.size} 件")
    items.forEach { file ->
        val on = file.uri in selected
        Action("${if (on) "[x]" else "[ ]"} ${file.displayName}（${formatBytes(file.size)}）") {
            selected = if (on) selected - file.uri else selected + file.uri
        }
    }
    Action("選んだものを削除する（同意の画面が出る）") { actions.onDeleteLeftovers(items.filter { it.uri in selected }) }
}

@Composable
private fun LogScreen(model: AppViewModel, actions: ScreenActions) {
    val lines by model.logLines.collectAsState()
    var confirmShare by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Action("共有") { confirmShare = true }
    Action("消去") { confirmClear = true }
    if (confirmShare) {
        Confirm("ログには曲名が含まれる。共有するか", "共有する", { confirmShare = false; actions.onShareLog(model.logText()) }, { confirmShare = false })
    }
    if (confirmClear) {
        Confirm("ログを全部消す", "消す", { confirmClear = false; model.clearLog() }, { confirmClear = false })
    }
    Back(model)
    lines.forEach { Label(it) }
}

@Composable
private fun LicensesScreen(model: AppViewModel, actions: ScreenActions) {
    val entries = remember { actions.licenses() }
    var open by remember { mutableStateOf<LicenseEntry?>(null) }
    Back(model)
    val chosen = open
    when {
        entries == null -> Label("読み込めない")
        chosen != null -> {
            Label("${chosen.name} ${chosen.version}", bold = true)
            Label(chosen.body)
            Action("一覧へ") { open = null }
        }
        else -> entries.forEach { Action("${it.name} ${it.version}　${it.license}") { open = it } }
    }
}
