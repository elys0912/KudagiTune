package playlistsyncfox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import playlistsyncfox.android.log.LogLevel
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.sync.SyncServices
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.leafIds

// 画面は状態を描くだけ（X7-1-1）。配色は OS のライト・ダークに従い（Theme.kt。D20）、部品は下の 1 か所に置いて全部の画面が使う。

/** 画面から Activity（権限、共有、削除の同意）へ頼む操作。 */
class ScreenActions(
    val onSyncButton: () -> Unit,
    val onNotificationHelp: () -> Unit,
    val onRequestRead: () -> Unit,
    val onDeleteLeftovers: (List<StoredFile>) -> Unit,
    val onShareLog: (String) -> Unit,
    val onOpenStorageSettings: () -> Unit,
    val licenses: () -> List<LicenseEntry>?,
    /** 同梱の全文（[LicenseEntry.textFiles] の 1 件）。読めなければ null。 */
    val licenseText: (String) -> String?,
)

@Composable
fun AppScreen(model: AppViewModel, actions: ScreenActions, notificationsAllowed: () -> Boolean, readGranted: () -> Boolean) {
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
                        Screen.Home -> HomeScreen(model, actions, notificationsAllowed, readGranted)
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
    val c = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth().background(c.band)) {
        Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicText("playlist-sync-fox", style = TextStyle(color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Medium))
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

@Composable
private fun Label(text: String, bold: Boolean = false, color: Color = LocalPsfoxColors.current.text, mono: Boolean = false) =
    BasicText(
        text,
        style = TextStyle(
            color = color,
            fontSize = if (mono) 13.sp else 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )

/** 枠のボタン。 */
@Composable
private fun Action(label: String, onClick: () -> Unit) = ActionCell(label, Modifier.fillMaxWidth(), onClick)

@Composable
private fun ActionCell(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPsfoxColors.current
    Box(
        modifier.heightIn(min = 44.dp).border(1.dp, c.buttonBorder, RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center))
    }
}

/** 主ボタン（白文字）。 */
@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    val c = LocalPsfoxColors.current
    Box(
        Modifier.fillMaxWidth().height(48.dp).background(c.primary, RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}

/** 枠のボタンを 2 列に並べる（S1）。 */
@Composable
private fun ActionGrid(actions: List<Pair<String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, onClick) -> ActionCell(label, Modifier.weight(1f), onClick) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    val c = LocalPsfoxColors.current
    Column(
        Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 棒。[fraction] は 0〜1。 */
@Composable
private fun Bar(fraction: Float, height: Int) {
    val c = LocalPsfoxColors.current
    Box(Modifier.fillMaxWidth().height(height.dp).background(c.barGroove, RoundedCornerShape((height / 2).dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).background(c.accent, RoundedCornerShape((height / 2).dp)))
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit) {
    val c = LocalPsfoxColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 15.sp),
        cursorBrush = SolidColor(c.text),
        modifier = Modifier.fillMaxWidth().border(1.dp, c.buttonBorder, RoundedCornerShape(4.dp)).padding(10.dp),
    )
}

@Composable
private fun Confirm(text: String, ok: String, onOk: () -> Unit, onCancel: () -> Unit, cancel: String = "取り消す") {
    val c = LocalPsfoxColors.current
    Column(
        Modifier.fillMaxWidth().border(2.dp, c.accent, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text)
        Action(ok, onOk)
        Action(cancel, onCancel)
    }
}

@Composable
private fun Back(model: AppViewModel) = Action("戻る", model::back)


@Composable
private fun HomeScreen(model: AppViewModel, actions: ScreenActions, notificationsAllowed: () -> Boolean, readGranted: () -> Boolean) {
    val c = LocalPsfoxColors.current
    val state by model.home.collectAsState()
    when (state.kind) {
        HomeKind.Unpaired -> Label("PC とペアリングしてください")
        else -> {
            Card {
                Caption("接続先の PC")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(c.success, RoundedCornerShape(4.dp)))
                    Label(state.pcName.orEmpty(), bold = true)
                }
                Label("接続先：${state.endpoint.orEmpty()}", color = c.muted)
                if (state.kind == HomeKind.Syncing) Label("同期中", color = c.muted)
                val last = state.last
                if (last == null) {
                    Label("まだ同期していません", color = c.muted)
                } else {
                    Label("前回の同期：${formatTime(last.atMillis)} ${last.word}", color = c.muted)
                }
            }
            Card {
                Caption("同期するプレイリスト")
                if (state.selectedCount == 0) {
                    Label("同期するプレイリストを選んでください")
                } else if (state.playlists.isEmpty()) {
                    Label("選んだプレイリスト：${state.selectedCount} 件（まだ同期していません）")
                } else {
                    state.playlists.forEach {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Label(it.name)
                            Label("${it.trackCount} 曲", color = c.muted)
                        }
                    }
                }
            }
        }
    }
    when (state.kind) {
        HomeKind.Syncing -> PrimaryAction("進行状況を見る", model::showProgress)
        HomeKind.Unpaired -> PrimaryAction("ペアリングする", actions.onSyncButton)
        else -> PrimaryAction("同期する", actions.onSyncButton)
    }
    ActionGrid(
        buildList {
            if (state.kind != HomeKind.Unpaired) add("プレイリストを選ぶ" to { model.openSelection() })
            if (state.last != null) add("結果を見る" to { model.openResult(null) })
            add("設定" to { model.openSettings(notificationsAllowed(), readGranted()) })
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
    var manual by remember { mutableStateOf("") }
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
        Action(if (all) "フォルダ内をすべて外す：${node.name}" else "フォルダ内をすべて選ぶ：${node.name}") { model.selection.setFolder(node, !all) }
        if (open) children.forEach { NodeRow(model, stage, it, depth + 1) }
    }
}

/** S3（X7-1-4）。上から、見出しと全体の棒、曲ごとの一覧（残りの高さ）、ログの欄（150 dp）、「中断する」。 */
@Composable
private fun ProgressScreen(model: AppViewModel, current: Screen.Progress, actions: ScreenActions, modifier: Modifier) {
    val c = LocalPsfoxColors.current
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
                    BasicText("${ui.doneCount} / ${ui.totalCount} 曲$failed", style = TextStyle(color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                    BasicText("${formatBytes(ui.doneBytes)} / ${formatBytes(ui.totalBytes)}", style = TextStyle(color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
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
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))

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
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Action("中断する", model::cancelSync)
        }
    }
}

@Composable
private fun TrackRowItem(row: TrackRowUi) {
    val c = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(if (row.highlighted) c.transferringRow else Color.Transparent).padding(horizontal = 16.dp, vertical = 9.dp),
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
                        style = TextStyle(color = if (row.dimmed) c.muted else c.text, fontSize = 14.sp),
                    )
                    BasicText(row.sizeText, style = TextStyle(color = c.muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace))
                }
                row.fraction?.let { Bar(it, 4) }
                row.reason?.let { BasicText(it, style = TextStyle(color = c.failure, fontSize = 12.sp)) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.rowLine))
    }
}

/** 状態の印。完了＝チェック、転送中＝下向きの矢印、待機＝時計、失敗＝×。 */
@Composable
private fun MarkIcon(mark: TrackMark) {
    val c = LocalPsfoxColors.current
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        val stroke = 2.2.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Color) =
            drawLine(color, Offset(x1 * w, y1 * w), Offset(x2 * w, y2 * w), strokeWidth = stroke, cap = StrokeCap.Round)
        when (mark) {
            TrackMark.Done -> {
                line(0.2f, 0.52f, 0.42f, 0.74f, c.success)
                line(0.42f, 0.74f, 0.82f, 0.28f, c.success)
            }
            TrackMark.Transferring -> {
                line(0.5f, 0.15f, 0.5f, 0.85f, c.accent)
                line(0.22f, 0.57f, 0.5f, 0.85f, c.accent)
                line(0.78f, 0.57f, 0.5f, 0.85f, c.accent)
            }
            TrackMark.Waiting -> {
                drawCircle(c.muted, radius = 0.38f * w, center = Offset(w / 2, w / 2), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                line(0.5f, 0.3f, 0.5f, 0.5f, c.muted)
                line(0.5f, 0.5f, 0.65f, 0.6f, c.muted)
            }
            TrackMark.Failed -> {
                line(0.22f, 0.22f, 0.78f, 0.78f, c.failure)
                line(0.78f, 0.22f, 0.22f, 0.78f, c.failure)
            }
        }
    }
}

/** ログの欄（X7-1-4）。新しい行が下。時刻とメッセージ。警告とエラーの行はその色。 */
@Composable
private fun LogPane(lines: List<playlistsyncfox.android.log.LogEntry>) {
    val c = LocalPsfoxColors.current
    Column(Modifier.fillMaxWidth().height(150.dp).background(c.logPanel)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Box(Modifier.fillMaxWidth().height(29.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
            BasicText("ログ", style = TextStyle(color = c.muted, fontSize = 11.sp, letterSpacing = 0.5.sp))
        }
        // 下を起点に積むので、新しい行が来ても末尾が見えたままになる
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), reverseLayout = true) {
            items(lines.asReversed()) { entry ->
                val color = when (entry.level) {
                    LogLevel.Info -> c.text
                    LogLevel.Warn -> c.warning
                    LogLevel.Error -> c.failure
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicText(logTime(entry), style = TextStyle(color = c.muted, fontSize = 11.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace))
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
    val state by model.settings.collectAsState()
    var confirmUnpair by remember { mutableStateOf(false) }
    var confirmRelocated by remember { mutableStateOf(false) }
    val syncing = isSyncing(SyncServices.progress.collectAsState().value)
    val ui = state
    if (ui == null) {
        Label("読み込んでいます")
        Back(model)
        return
    }
    Label("自動同期：${if (ui.autoEnabled) "オン" else "オフ"}（Wi-Fi など従量制でない回線のとき、約 6 時間ごと）")
    Action(if (ui.autoEnabled) "オフにする" else "オンにする") { model.toggleAuto(!ui.autoEnabled) }
    Label("PC にない曲を端末から削除する：${if (ui.deleteMissing) "オン" else "オフ"}（プレイリストから外した曲も対象です）")
    Action(if (ui.deleteMissing) "オフにする" else "オンにする") { model.setDeleteMissing(!ui.deleteMissing) }
    Label("保存先のフォルダ：${ui.saveFolder}")
    ui.storage.options.forEach { option ->
        val chosen = option.volume == ui.storage.selected
        Label("${if (chosen) "[x]" else "[ ]"} ${option.label}")
        if (!chosen && option.available) Action("${option.label}に変更する") { model.setStorage(option.volume) }
    }
    Label("保存先を変更すると、次の同期で新しい保存先へ全曲を転送し直します。前の保存先の曲は自動では削除されません")
    if (ui.relocatedCount > 0) {
        Label("前の保存先に残っている曲とプレイリスト：${ui.relocatedCount} 件")
        if (syncing) Label("同期中は削除できません") else Action("前の保存先の曲を削除する") { confirmRelocated = true }
        if (confirmRelocated) {
            Confirm("前の保存先から、このアプリが保存した ${ui.relocatedCount} 件を削除します。よろしいですか？", "削除する", { confirmRelocated = false; model.deleteRelocated() }, { confirmRelocated = false })
        }
    }
    if (ui.notificationsOff) {
        Label("通知がオフになっています")
        Action("通知を許可する", actions.onNotificationHelp)
    }
    if (ui.pcName != null) {
        Label("ペアリング中の PC：${ui.pcName}", bold = true)
        ui.fingerprint?.let { Label("確認コード：$it") }
        Action("解除する") { confirmUnpair = true }
        if (confirmUnpair) {
            Confirm("ペアリングを解除します。曲とプレイリストは端末に残ります。PC の画面の「端末」でも解除してください", "解除する", { confirmUnpair = false; model.unpair() }, { confirmUnpair = false })
        }
    }
    Label("以前のインストールで保存した曲の整理", bold = true)
    when (val cleanup = ui.cleanup) {
        CleanupState.NeedsPermission -> {
            Label("一覧を出すには、音楽ファイルの読み取りの許可が必要です")
            Action("許可して一覧を出す", actions.onRequestRead)
        }
        CleanupState.Denied -> Label("読み取りが許可されていないため、整理できません")
        is CleanupState.Found -> Cleanup(cleanup.items, actions)
    }
    Label("ログ（${formatBytes(ui.logBytes)}）", bold = true)
    Action("ログを見る", model::openLog)
    Action("ライセンス", model::openLicenses)
    Action("ホームへ", model::back)
}

@Composable
private fun Cleanup(items: List<StoredFile>, actions: ScreenActions) {
    if (items.isEmpty()) {
        Label("整理する曲はありません")
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
    Action("選んだ曲を削除する（確認の画面が出ます）") { actions.onDeleteLeftovers(items.filter { it.uri in selected }) }
}

@Composable
private fun LogScreen(model: AppViewModel, actions: ScreenActions) {
    val lines by model.logLines.collectAsState()
    var confirmShare by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Action("共有する") { confirmShare = true }
    Action("消去する") { confirmClear = true }
    if (confirmShare) {
        Confirm("ログには曲名が含まれます。共有しますか？", "共有する", { confirmShare = false; actions.onShareLog(model.logText()) }, { confirmShare = false })
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
    var open by remember { mutableStateOf<LicenseEntry?>(null) }
    Back(model)
    val chosen = open
    when {
        entries == null -> Label("ライセンスの一覧を読み込めません")
        chosen != null -> {
            // 全文は選ばれたときに読む。再描画のたびには読まない
            val texts = remember(chosen) { chosen.textFiles.map(actions.licenseText) }
            Label("${chosen.name} ${chosen.version}", bold = true)
            Label(chosen.body)
            texts.forEach { Label(it ?: "ライセンスの全文を読み込めません", mono = true) }
            Action("一覧へ") { open = null }
        }
        else -> entries.forEach { Action("${it.name} ${it.version}　${it.license}") { open = it } }
    }
}
