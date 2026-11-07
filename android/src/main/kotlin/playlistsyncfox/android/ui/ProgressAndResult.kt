package playlistsyncfox.android.ui

import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage

// S3 同期の進み具合（X7-1-4）と S4 結果（X7-1-5）。

enum class ProgressNav { Stay, ToResult, ToHome }

/**
 * S3 の次の遷移。`finished` で S4 へ。同期が動いていなければ S1 へ戻る（通知の古いタップへの備え）。
 * [awaitingStart] は、この画面から開始した直後で、ワーカーの最初の進捗を待っている間。
 */
fun progressNav(progress: SyncProgress?, awaitingStart: Boolean): ProgressNav = when {
    progress?.stage == SyncStage.Finished -> ProgressNav.ToResult
    progress == null && !awaitingStart -> ProgressNav.ToHome
    else -> ProgressNav.Stay
}

data class ProgressUiState(
    val heading: String,
    val doneCount: Int,
    val totalCount: Int,
    val doneBytes: Long,
    val totalBytes: Long,
    val currentTitle: String?,
    /** 容量不足の不足分。`ACTION_MANAGE_STORAGE` の案内を添える。 */
    val shortageBytes: Long?,
    val nearLimitNote: String?,
    /** `finished` で E2 のときの案内（X7-1-3 の表）。 */
    val notice: Notice?,
)

fun stageHeading(stage: SyncStage): String = when (stage) {
    SyncStage.Connecting -> "PC に接続している"
    SyncStage.Manifest -> "曲の一覧を取得している"
    SyncStage.Diff -> "差分を計算している"
    SyncStage.Capacity -> "空き容量を確認している"
    SyncStage.Transfer -> "曲を転送している"
    SyncStage.Playlist -> "プレイリストを書いている"
    SyncStage.Finished -> "終了"
}

fun progressUi(progress: SyncProgress) = ProgressUiState(
    heading = stageHeading(progress.stage),
    doneCount = progress.doneCount,
    totalCount = progress.totalCount,
    doneBytes = progress.doneBytes,
    totalBytes = progress.totalBytes,
    currentTitle = progress.currentTitle.takeIf { progress.stage == SyncStage.Transfer },
    shortageBytes = progress.shortageBytes,
    nearLimitNote = "時間の上限が近い。続きは次の同期で行う".takeIf { progress.nearLimit },
    notice = progress.errorDetail?.let(::noticeOf),
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
    val excluded: IssueList,
    val unplayable: IssueList,
    val failed: IssueList,
) {
    data class Counts(val added: Int, val replaced: Int, val removed: Int, val failed: Int)
}

/** V21 結果の一覧の表示件数の上限。 */
const val ISSUE_LIMIT = 100

private fun issueList(lines: List<IssueLine>, limit: Int) = IssueList(lines.take(limit), (lines.size - limit).coerceAtLeast(0))

fun resultUi(run: SyncRun, issues: List<RunIssue>, limit: Int = ISSUE_LIMIT): ResultUiState {
    val stored = run.resultJson?.takeIf { it.isNotEmpty() }?.let { runCatching { decodeResult(it) }.getOrNull() }
    val e2Detail = stored?.errorDetail.takeIf { run.errorKind == "E2" }
    return ResultUiState(
        runId = run.runId,
        word = resultWord(if (run.endedAt == null) SyncRun.RESULT_INTERRUPTED else run.result),
        trigger = triggerWord(run.trigger),
        atMillis = run.endedAt ?: run.startedAt,
        counts = ResultUiState.Counts(run.added, run.replaced, run.removed, run.failed).takeIf { stored != null },
        bytes = run.bytesTransferred.takeIf { stored != null },
        errorWord = errorWord(run.errorKind),
        noticeLabel = e2Detail?.let { noticeOf(it).label },
        removalNote = removalNote(run.trigger).takeIf { stored?.removalSkipped == true },
        excluded = issueList(
            issues.filter { it.kind == RunIssue.KIND_EXCLUDED }
                .map { IssueLine(it.title, listOfNotNull(it.artist, reasonWord(it.reason).takeIf(String::isNotEmpty)).joinToString(" / ")) },
            limit,
        ),
        unplayable = issueList(
            issues.filter { it.kind == RunIssue.KIND_UNPLAYABLE }
                .map { IssueLine(it.title, listOfNotNull(it.format, reasonWord(it.reason).takeIf(String::isNotEmpty)).joinToString(" / ")) },
            limit,
        ),
        failed = issueList(issues.filter { it.kind == RunIssue.KIND_FAILED }.map { IssueLine(it.title, it.errorKind.orEmpty()) }, limit),
    )
}

/** 同期が「消す」を飛ばしたときの案内。自動と手動で文が違い、手動の 4 つの場合は同じ文。 */
private fun removalNote(trigger: String) =
    if (trigger == SyncRun.TRIGGER_AUTO) "消す処理は手動の同期で行う" else "確認が要る消す処理を行っていない。もう一度同期して確認する"

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

