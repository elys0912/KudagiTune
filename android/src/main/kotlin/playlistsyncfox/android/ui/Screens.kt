package playlistsyncfox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import playlistsyncfox.android.R
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.leafIds

// 画面は状態を描くだけ（X7-1-1）。配色は OS のライト・ダークに従い（Theme.kt。D20）、部品は Components.kt に置いて全部の画面が使う。

/** 画面から Activity（権限、共有、削除の同意）へ頼む操作。 */
class ScreenActions(
    val onSyncButton: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onNotificationHelp: () -> Unit,
    val onRequestRead: () -> Unit,
    val onDeleteLeftovers: (List<StoredFile>) -> Unit,
    val onShareLog: (String) -> Unit,
    val onSaveLog: () -> Unit,
    val onOpenStorageSettings: () -> Unit,
    val licenses: () -> List<LicenseEntry>?,
    /** 同梱の全文（[LicenseEntry.textFiles] の 1 件）。読めなければ null。 */
    val licenseText: (String) -> String?,
    /** 依存の NOTICE（[LicenseEntry.noticeFile]）。NOTICE を持たない部品は null。 */
    val licenseNotice: (String) -> String?,
)

@Composable
fun AppScreen(model: AppViewModel, actions: ScreenActions) {
    val screen by model.screen.collectAsState()
    val message by model.message.collectAsState()
    PsfoxTheme {
        Column(Modifier.fillMaxSize().background(LocalPsfoxColors.current.background).systemBarsPadding().imePadding()) {
            TitleBand()
            when (val current = screen) {
                // S3 は曲ごとの一覧が自分でスクロールするので、全体は縦にスクロールさせない（入れ子のスクロールを避ける）
                is Screen.Progress -> ProgressScreen(model, current, actions, Modifier.weight(1f).fillMaxWidth())
                else -> Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    when (current) {
                        Screen.Home -> HomeScreen(model, actions)
                        Screen.Pairing -> PairingScreen(model)
                        is Screen.Result -> ResultScreen(model, actions)
                        Screen.Settings -> SettingsScreen(model, actions)
                        is Screen.Selection -> SelectionScreen(model, current)
                        Screen.Log -> LogScreen(model, actions)
                        Screen.Licenses -> LicensesScreen(model, actions)
                        is Screen.Progress -> Unit
                    }
                    message?.let { Label(it) }
                }
            }
        }
    }
}

/** 上の帯（アプリ名。X7-1-1 の 8）。 */
@Composable
private fun TitleBand() {
    val colors = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth().background(colors.band)) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicText(stringResource(R.string.app_name), style = TextStyle(color = colors.text, fontSize = 17.sp, fontWeight = FontWeight.Medium))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    }
}

@Composable
private fun HomeScreen(model: AppViewModel, actions: ScreenActions) {
    val colors = LocalPsfoxColors.current
    val state by model.home.collectAsState()
    when (state.kind) {
        HomeKind.Unpaired -> Label("PC とペアリングしてください")
        else -> {
            Card {
                Caption("接続先の PC")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(colors.success, RoundedCornerShape(4.dp)))
                    Label(state.pcName.orEmpty(), bold = true)
                }
                Label("接続先：${state.endpoint.orEmpty()}", color = colors.muted)
                if (state.kind == HomeKind.Syncing) Label("同期中", color = colors.muted)
                val last = state.last
                if (last == null) {
                    Label("まだ同期していません", color = colors.muted)
                } else {
                    val transport = last.transportLabel?.let { "（$it）" }.orEmpty()
                    Label("前回の同期：${formatTime(last.atMillis)} ${last.word}$transport", color = colors.muted)
                }
            }
            Card {
                Caption("同期するプレイリスト")
                if (state.selectedCount == 0) {
                    Label(NO_SELECTION_NOTICE)
                } else if (state.playlists.isEmpty()) {
                    Label("選んだプレイリスト：${state.selectedCount} 件（まだ同期していません）")
                } else {
                    state.playlists.forEach {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Label(it.name)
                            Label("${it.trackCount} 曲", color = colors.muted)
                        }
                    }
                }
            }
        }
    }
    when (state.kind) {
        HomeKind.Syncing -> PrimaryAction("進行状況を見る", model::showProgress)
        HomeKind.Unpaired -> PrimaryAction("ペアリングする", actions.onSyncButton)
        else -> PrimaryAction("同期", actions.onSyncButton)
    }
    ActionGrid(
        buildList {
            if (state.kind != HomeKind.Unpaired) add("プレイリストを選ぶ" to { model.openSelection() })
            if (state.last != null) add("結果を見る" to { model.openResult(null) })
            add("設定" to actions.onOpenSettings)
        },
    )
    RemovalConfirm(model)
}

/** カードの見出し（小さい薄い文字）。 */
@Composable
private fun Caption(text: String) = BasicText(text, style = TextStyle(color = LocalPsfoxColors.current.muted, fontSize = 12.sp))

/** 「消す」の確認。同期ボタンは S1 と S4 にあるので、両方に置く。 */
@Composable
private fun RemovalConfirm(model: AppViewModel) {
    val confirm by model.confirm.collectAsState()
    confirm?.let {
        Confirm(
            text = "PC のプレイリストにない ${it.toRemove} 曲を、この端末から削除します。よろしいですか？",
            ok = "削除して同期する",
            cancel = "削除せずに同期する",
            onOk = { model.answerConfirm(true) },
            onCancel = { model.answerConfirm(false) },
        )
    }
}

@Composable
private fun PairingScreen(model: AppViewModel) {
    val state by model.pairing.state.collectAsState()
    var manual by rememberSaveable { mutableStateOf("") }
    // ペアリングコードは、状態の保存（プロセスの再作成）にも残さない
    var code by remember { mutableStateOf("") }
    when (val stage = state.stage) {
        PairingStage.Discovering -> {
            Label("PC を探しています", bold = true)
            state.found.forEach { pc -> Action(pc.serviceName) { model.pairing.select(pc) } }
            if (state.notFound) Label("PC が見つかりません。PC のアドレスを入力してください", bold = true)
            Label("PC のアドレス（IP アドレスかホスト名。ポート番号は省略できます）")
            Field(manual) { manual = it }
            state.manualError?.let { Label(it) }
            Action("接続する") { model.pairing.submitManual(manual) }
        }
        is PairingStage.Target -> {
            Label("接続先：${stage.target.name}", bold = true)
            stage.fingerprint?.let { Label("PC の確認コード：$it（PC の画面と同じか確かめてください）") }
            Label("PC に表示されている $CODE_LENGTH 桁のペアリングコード")
            Field(code) { code = it }
            stage.codeError?.let { Label(it) }
            if (!state.sending) {
                Action("ペアリングする") {
                    val sent = code
                    code = ""
                    model.pairing.submitCode(sent)
                }
            }
        }
        is PairingStage.Done -> {
            Label("ペアリングしました：${stage.pcName}", bold = true)
            Label("接続先が違う場合は、PC の画面の「端末」とこのアプリの設定の両方で解除してください")
            if (stage.showCleanupHint) Label("以前このアプリを使っていた場合は、先に設定の「以前のインストールで保存した曲の整理」で古い曲を整理してください")
        }
        PairingStage.AttemptsExhausted -> Label("入力できる回数を超えました。PC でペアリングコードを作り直してください")
        PairingStage.Waiting -> Label("しばらく待ってからやり直してください")
        PairingStage.DeviceExists -> Label("この端末の登録が PC に残っています。PC の画面の「端末」で解除してから、やり直してください")
        is PairingStage.Failed -> {
            Label(stage.notice.title, bold = true)
            Label(stage.notice.guidance)
            Action("やり直す", model.pairing::retry)
        }
    }
    if (state.stage is PairingStage.Done) Action("ホームへ", model::back) else Back(model)
}

@Composable
private fun SelectionScreen(model: AppViewModel, current: Screen.Selection) {
    val stage by model.selection.stage.collectAsState()
    current.notice?.let { Label(it, bold = true) }
    when (val s = stage) {
        SelectionStage.Loading -> Label("読み込んでいます")
        is SelectionStage.Loaded -> {
            s.nodes.forEach { NodeRow(model, s, it, 0) }
            s.missing.forEach { row ->
                Label("PC に見つかりません：${row.name}")
                Action("外す") { model.selection.remove(row.playlistId) }
            }
        }
        is SelectionStage.Failed -> {
            Label(catalogWord(s.reason), bold = true)
            catalogGuidance(s.reason)?.let { Label(it) }
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
    var open by rememberSaveable { mutableStateOf(true) }
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
        Action(if (all) "フォルダ内をすべて外す：${node.name}" else "フォルダ内をすべて選ぶ：${node.name}") { model.selection.setFolder(node, !all) }
        if (open) children.forEach { NodeRow(model, stage, it, depth + 1) }
    }
}

@Composable
private fun ResultScreen(model: AppViewModel, actions: ScreenActions) {
    val screen by model.result.collectAsState()
    val state = screen.current
    if (state == null) {
        Label("まだ結果がありません")
    } else {
        Label("${state.word}（${state.trigger}）　${formatTime(state.atMillis)}", bold = true)
        state.removalNote?.let { Label(it, bold = true) }
        state.versionMismatchNote?.let { Label(it, bold = true) }
        if (state.notRemoved > 0) Label("削除しなかった曲：${state.notRemoved} 件")
        state.counts?.let { Label("追加 ${it.added}　更新 ${it.replaced}　削除 ${it.removed}　失敗 ${it.failed}") }
        state.bytes?.let { Label("転送量 ${formatBytes(it)}") }
        state.errorWord?.let { Label("原因：$it") }
        state.noticeLabel?.let { Label("詳細：$it") }
        Issues("同期しなかった曲", state.excluded)
        Issues("再生できない曲", state.unplayable)
        Issues("失敗した曲", state.failed)
    }
    Action("もう一度同期する", actions.onSyncButton)
    RemovalConfirm(model)
    if (screen.past.size > 1) {
        Label("過去の同期", bold = true)
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
    val state by model.settings.state.collectAsState()
    val ui = state
    if (ui == null) {
        Label("読み込んでいます")
        Back(model)
        return
    }
    SyncOptionsSection(model.settings, ui)
    StorageSection(model, ui)
    if (ui.notificationsOff) {
        Label("通知がオフになっています")
        Action("通知を許可する", actions.onNotificationHelp)
    }
    PairingSection(model, ui)
    CleanupSection(ui.cleanup, actions)
    LogSection(model, actions, ui.logBytes)
    Action("ライセンス", model::openLicenses)
    Action("ホームへ", model::back)
}

/** 自動同期と「PC にない曲を端末から削除する」の切り替え。 */
@Composable
private fun SyncOptionsSection(presenter: SettingsPresenter, ui: SettingsUiState) {
    Label("自動同期：${if (ui.autoEnabled) "オン" else "オフ"}（Wi-Fi など従量制でない回線のとき、約 6 時間ごと）")
    Action(if (ui.autoEnabled) "オフにする" else "オンにする") { presenter.toggleAuto(!ui.autoEnabled) }
    Label("PC にない曲を端末から削除する：${if (ui.deleteMissing) "オン" else "オフ"}（プレイリストから外した曲も対象です）")
    Action(if (ui.deleteMissing) "オフにする" else "オンにする") { presenter.setDeleteMissing(!ui.deleteMissing) }
}

/** 保存先の選択と、前の保存先に残った曲の削除（D16）。 */
@Composable
private fun StorageSection(model: AppViewModel, ui: SettingsUiState) {
    val syncing by model.syncing.collectAsState()
    Label("保存先のフォルダ：${ui.saveFolder}")
    ui.storage.options.forEach { option ->
        val chosen = option.volume == ui.storage.selected
        Label("${if (chosen) "[x]" else "[ ]"} ${option.label}")
        if (!chosen && option.available) Action("${option.label}に変更する") { model.settings.setStorage(option.volume) }
    }
    Label("保存先を変更すると、次の同期で新しい保存先へ全曲を転送し直します。前の保存先の曲は自動では削除されません")
    if (ui.relocatedCount > 0) {
        Label("前の保存先に残っている曲とプレイリスト：${ui.relocatedCount} 件")
        if (syncing) {
            Label(SYNCING_NO_DELETE_NOTICE)
        } else {
            ConfirmedAction(
                label = "前の保存先の曲を削除する",
                prompt = "前の保存先から、このアプリが保存した ${ui.relocatedCount} 件を削除します。よろしいですか？",
                ok = "削除する",
                onConfirmed = model.settings::deleteRelocated,
            )
        }
    }
}

/** ペアリング中の PC と解除。未ペアリングなら何も出さない。 */
@Composable
private fun PairingSection(model: AppViewModel, ui: SettingsUiState) {
    if (ui.pcName == null) return

    Label("ペアリング中の PC：${ui.pcName}", bold = true)
    ui.fingerprint?.let { Label("確認コード：$it") }
    ConfirmedAction(
        label = "解除する",
        prompt = "ペアリングを解除します。曲とプレイリストは端末に残ります。PC の画面の「端末」でも解除してください",
        ok = "解除する",
        onConfirmed = model::unpair,
    )
}

/** 以前のインストールで保存した曲の整理。 */
@Composable
private fun CleanupSection(cleanup: CleanupState, actions: ScreenActions) {
    Label("以前のインストールで保存した曲の整理", bold = true)
    when (cleanup) {
        CleanupState.NeedsPermission -> {
            Label("一覧を出すには、音楽ファイルの読み取りの許可が必要です")
            Action("許可して一覧を出す", actions.onRequestRead)
        }
        CleanupState.Denied -> Label("読み取りが許可されていないため、整理できません")
        is CleanupState.Found -> Cleanup(cleanup.items, actions)
    }
}

/** ログの閲覧とファイルへの保存。 */
@Composable
private fun LogSection(model: AppViewModel, actions: ScreenActions, logBytes: Long) {
    Label("ログ（${formatBytes(logBytes)}）", bold = true)
    Action("ログを見る", model::openLog)
    ConfirmedAction(
        label = "ファイルに保存する",
        prompt = "ログには曲名が含まれます。ファイルに保存しますか？",
        ok = "保存する",
        onConfirmed = actions.onSaveLog,
    )
}

@Composable
private fun Cleanup(items: List<StoredFile>, actions: ScreenActions) {
    if (items.isEmpty()) {
        Label("整理する曲はありません")
        return
    }
    var selected by rememberSaveable { mutableStateOf(emptySet<String>()) }
    Label("${items.size} 件")
    items.forEach { file ->
        val on = file.uri in selected
        Action("${if (on) "[x]" else "[ ]"} ${file.displayName}（${formatBytes(file.size)}）") {
            selected = if (on) selected - file.uri else selected + file.uri
        }
    }
    Action("選んだ曲を削除する（確認の画面が出ます）") { actions.onDeleteLeftovers(items.filter { it.uri in selected }) }
}

@Composable
private fun LogScreen(model: AppViewModel, actions: ScreenActions) {
    val lines by model.logLines.collectAsState()
    var confirmShare by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    Action("共有する") { confirmShare = true }
    Action("消去する") { confirmClear = true }
    if (confirmShare) {
        Confirm("ログには曲名が含まれます。共有しますか？", "共有する", { confirmShare = false; model.shareLog(actions.onShareLog) }, { confirmShare = false })
    }
    if (confirmClear) {
        Confirm("ログをすべて消去します。よろしいですか？", "消去する", { confirmClear = false; model.clearLog() }, { confirmClear = false })
    }
    Back(model)
    lines.forEach { Label(it, mono = true) }
}

@Composable
private fun LicensesScreen(model: AppViewModel, actions: ScreenActions) {
    val entries = remember { actions.licenses() }
    var openIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    Back(model)
    val chosen = openIndex?.let { entries?.getOrNull(it) }
    when {
        entries == null -> Label("ライセンスの一覧を読み込めません")
        chosen != null -> {
            // 全文と NOTICE は選ばれたときに読む。再描画のたびには読まない
            val texts = remember(chosen) { licenseTexts(chosen, actions.licenseText, actions.licenseNotice) }
            Label("${chosen.name} ${chosen.version}", bold = true)
            Label(chosen.body)
            texts.forEach { Label(it, mono = true) }
            Action("一覧へ") { openIndex = null }
        }
        else -> entries.forEachIndexed { index, entry -> Action("${entry.name} ${entry.version}　${entry.license}") { openIndex = index } }
    }
}
