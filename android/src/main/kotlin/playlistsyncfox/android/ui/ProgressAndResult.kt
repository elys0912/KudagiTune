package playlistsyncfox.android.ui

import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.log.LogEntry
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage
import playlistsyncfox.android.sync.TrackRow
import playlistsyncfox.android.sync.TrackState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// S3 同期の進み具合（X7-1-4）と S4 結果（X7-1-5）。

enum class ProgressNav { Stay, Started, ToResult, ToHome }

/**
 * S3 の次の遷移。`finished` で S4 へ。同期が動いていなければ S1 へ戻る（通知の古いタップへの備え）。
 * [awaitingStart] は、この画面から開始した直後で、ワーカーの最初の進捗を待っている間。最初の進捗が届いたら [ProgressNav.Started]（待ちを終える）。
 */
fun progressNav(progress: SyncProgress?, awaitingStart: Boolean): ProgressNav = when {
    progress?.stage == SyncStage.Finished -> ProgressNav.ToResult
    progress == null && !awaitingStart -> ProgressNav.ToHome
    progress != null && awaitingStart -> ProgressNav.Started
    else -> ProgressNav.Stay
}

data class ProgressUiState(
    val heading: String,
    val doneCount: Int,
    val totalCount: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    /** 容量不足の不足分。`ACTION_MANAGE_STORAGE` の案内を添える。 */
    val shortageBytes: Long?,
    val nearLimitNote: String?,
    /** `finished` で E2 のときの案内（X7-1-3 の表）。 */
    val notice: Notice?,
    /** 全体の棒の割合（0〜1）。 */
    val fraction: Float,
    val failedCount: Int,
    val tracks: List<TrackRowUi>,
)

/** 行の状態の印（X7-1-4）。完了＝成功の色のチェック、転送中＝アクセントの下向きの矢印、待機＝薄い時計、失敗＝失敗の色の×。 */
enum class TrackMark { Done, Transferring, Waiting, Failed }

/**
 * S3 の曲ごとの行の表示（X7-1-4）。[highlighted] は転送中の行の地の色、[dimmed] は完了の曲名の薄い文字、
 * [fraction] は転送中の行の細い棒（それ以外は null）、[reason] は失敗の理由の語（X7-1-5 と同じ語）。
 */
data class TrackRowUi(
    val id: String,
    val title: String,
    val mark: TrackMark,
    val highlighted: Boolean,
    val dimmed: Boolean,
    val sizeText: String,
    val fraction: Float?,
    val reason: String?,
)

fun trackRowUi(row: TrackRow): TrackRowUi = TrackRowUi(
    id = row.persistentId,
    title = row.title,
    mark = when (row.state) {
        TrackState.Done -> TrackMark.Done
        TrackState.Transferring -> TrackMark.Transferring
        TrackState.Waiting -> TrackMark.Waiting
        TrackState.Failed -> TrackMark.Failed
    },
    highlighted = row.state == TrackState.Transferring,
    dimmed = row.state == TrackState.Done,
    sizeText = if (row.state == TrackState.Transferring) "${formatBytes(row.receivedBytes)} / ${formatBytes(row.size)}" else formatBytes(row.size),
    fraction = fraction(row.receivedBytes, row.size).takeIf { row.state == TrackState.Transferring },
    reason = row.errorNumber.takeIf { row.state == TrackState.Failed }?.let { errorWord("E$it") },
)

/** 棒の割合。全体が 0 なら 0、超えても 1 で切る。 */
fun fraction(done: Long, total: Long): Float = if (total <= 0) 0f else (done.toDouble() / total).coerceIn(0.0, 1.0).toFloat()

private val logTimeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")

/** ログの欄の時刻（端末の地方時の `HH:mm:ss`。X7-1-4）。 */
fun logTime(entry: LogEntry, zone: ZoneId = ZoneId.systemDefault()): String =
    logTimeFormat.format(Instant.ofEpochMilli(entry.at).atZone(zone))

/** ログの欄のメッセージ。E の番号があれば添える。 */
fun logMessage(entry: LogEntry): String = entry.errorNumber?.let { "${entry.message}（E$it）" } ?: entry.message

fun stageHeading(stage: SyncStage): String = when (stage) {
    SyncStage.Connecting -> "PC に接続しています"
    SyncStage.Manifest -> "曲の一覧を取得しています"
    SyncStage.Diff -> "変更点を確認しています"
    SyncStage.Capacity -> "空き容量を確認しています"
    SyncStage.Transfer -> "曲を転送しています"
    SyncStage.Playlist -> "プレイリストを保存しています"
    SyncStage.Finished -> "終了しました"
}

fun progressUi(progress: SyncProgress) = ProgressUiState(
    heading = stageHeading(progress.stage),
    doneCount = progress.doneCount,
    totalCount = progress.totalCount,
    doneBytes = progress.doneBytes,
    totalBytes = progress.totalBytes,
    shortageBytes = progress.shortageBytes,
    nearLimitNote = "実行時間の上限が近づいています。残りは次の同期で続けます".takeIf { progress.nearLimit },
    notice = progress.errorDetail?.let(::noticeOf),
    fraction = fraction(progress.doneBytes, progress.totalBytes),
    failedCount = progress.tracks.count { it.state == TrackState.Failed },
    tracks = progress.tracks.map(::trackRowUi),
)

/** 一覧の 1 項目。 */
data class IssueLine(val title: String, val detail: String)

/** 各一覧は V21 の件数まで出し、超えた分は「ほか N 件」の件数だけにする。 */
data class IssueList(val lines: List<IssueLine>, val more: Int)

data class ResultUiState(
    val runId: Long,
    val word: String,
    val trigger: String,
    val atMillis: Long,
    /** 強制終了の跡の行は件数と容量が分からないので null。 */
    val counts: Counts?,
    val bytes: Long?,
    val errorWord: String?,
    /** E2 の表示語（`error_detail` から選ぶ）。 */
    val noticeLabel: String?,
    val removalNote: String?,
    /** マニフェストの版違いで止まったときの案内（X6-9）。それ以外は null。 */
    val versionMismatchNote: String?,
    /** 消さなかった件数（差分の「消す」のうち実行しなかった分）。0 なら出さない。 */
    val notRemoved: Int,
    val excluded: IssueList,
    val unplayable: IssueList,
    val failed: IssueList,
) {
    data class Counts(val added: Int, val replaced: Int, val removed: Int, val failed: Int)
}

/** V21 結果の一覧の表示件数の上限。 */
const val ISSUE_LIMIT = 100

private fun issueList(lines: List<IssueLine>) = IssueList(lines.take(ISSUE_LIMIT), (lines.size - ISSUE_LIMIT).coerceAtLeast(0))

fun resultUi(run: SyncRun, issues: List<RunIssue>): ResultUiState {
    val stored = run.resultJson?.takeIf { it.isNotEmpty() }?.let { runCatching { decodeResult(it) }.getOrNull() }
    // E2 は原因欄も詳細も `error_detail` の語で出す（S3 と同じ関数）。`error_detail` が無い行は E の番号の語
    val e2Notice = stored?.errorDetail.takeIf { run.errorKind == "E2" }?.let(::noticeOf)
    return ResultUiState(
        runId = run.runId,
        word = resultWord(if (run.endedAt == null) SyncRun.RESULT_INTERRUPTED else run.result),
        trigger = triggerWord(run.trigger),
        atMillis = run.endedAt ?: run.startedAt,
        counts = ResultUiState.Counts(run.added, run.replaced, run.removed, run.failed).takeIf { stored != null },
        bytes = run.bytesTransferred.takeIf { stored != null },
        errorWord = e2Notice?.title ?: errorWord(run.errorKind),
        noticeLabel = e2Notice?.label,
        removalNote = removalNote(run.trigger).takeIf { stored?.removalSkipped == true },
        versionMismatchNote = VERSION_MISMATCH_NOTICE.takeIf { stored?.manifestVersionMismatch == true },
        notRemoved = if (stored == null) 0 else (stored.counts.toRemove - run.removed).coerceAtLeast(0),
        excluded = issueList(
            issues.filter { it.kind == RunIssue.KIND_EXCLUDED }
                .map { IssueLine(it.title, listOfNotNull(it.artist, reasonWord(it.reason).takeIf(String::isNotEmpty)).joinToString(" / ")) },
        ),
        unplayable = issueList(
            issues.filter { it.kind == RunIssue.KIND_UNPLAYABLE }
                .map { IssueLine(it.title, listOfNotNull(it.format, reasonWord(it.reason).takeIf(String::isNotEmpty)).joinToString(" / ")) },
        ),
        failed = issueList(issues.filter { it.kind == RunIssue.KIND_FAILED }.map { IssueLine(it.title, it.errorKind.orEmpty()) }),
    )
}

/** 同期が「消す」を飛ばしたときの案内。自動と手動で文が違い、手動の 4 つの場合は同じ文。 */
private fun removalNote(trigger: String) =
    if (trigger == SyncRun.TRIGGER_AUTO) "削除が必要な曲があります。手動で同期すると、確認のうえ削除します" else "削除の確認が済んでいないため、削除しませんでした。もう一度同期すると確認が出ます"

/** 過去の実行の一覧の 1 行。 */
data class PastRun(val runId: Long, val word: String, val atMillis: Long)

data class ResultScreen(val current: ResultUiState?, val past: List<PastRun>)

/**
 * S4 の読み込み。[runId] が null なら既定（最後の同期の行）、あれば過去の実行からその行の T3 と T6。
 */
suspend fun loadResult(store: SyncStore, runId: Long?, syncing: Boolean): ResultScreen {
    val runs = store.runs()
    val target = if (runId != null) runs.firstOrNull { it.runId == runId } else latestRun(runs, syncing)?.run
    return ResultScreen(
        current = target?.let { resultUi(it, store.issues(it.runId)) },
        past = runs.filter { it.endedAt != null }.map { PastRun(it.runId, resultWord(it.result), it.endedAt!!) },
    )
}

