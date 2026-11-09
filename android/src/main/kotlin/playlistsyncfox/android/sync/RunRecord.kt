package playlistsyncfox.android.sync

import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.shared.diff.Diff
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.result.Capacity
import playlistsyncfox.shared.result.Counts
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.ErrorScope
import playlistsyncfox.shared.result.SyncError
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncResult
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import playlistsyncfox.shared.result.hasMore
import playlistsyncfox.shared.result.toUnplayable

/*
 * 同期の終わりの記録（T3 の行、`result_json`、T6）の組み立て。状態を持たない関数だけを置く。
 * 呼ぶ順と保存は [SyncRunner] が持つ。
 */

/** 同期 1 回の件数と容量。計画は差分の値、実績は転送した値。 */
internal class RunTotals(
    val plannedAdd: Int,
    val plannedReplace: Int,
    val plannedBytes: Long,
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val failed: Int,
    val bytesTransferred: Long,
    val freeBefore: Long?,
    val freeAfter: Long?,
)

/** 実行の途中で立てた印のうち、`result_json` に載るもの。 */
internal class RunMarks(
    val duplicatesDropped: Int,
    val removalSkipped: Boolean,
    val keyMismatch: Boolean?,
    val errorDetail: ErrorDetail?,
    val manifestVersionMismatch: Boolean,
)

internal fun buildSyncResult(
    trigger: SyncTrigger,
    transport: Transport,
    startedAt: Long,
    endedAt: Long,
    outcome: SyncOutcome,
    totals: RunTotals,
    errors: List<SyncError>,
    loaded: Manifest?,
    finalDiff: Diff?,
    libraryId: String?,
    marks: RunMarks,
): SyncResult = SyncResult(
    startedAt = isoMillis(startedAt),
    finishedAt = isoMillis(endedAt),
    trigger = trigger,
    transport = transport,
    outcome = outcome,
    hasMore = hasMore(outcome, totals.added + totals.replaced, totals.plannedAdd + totals.plannedReplace),
    counts = Counts(
        toAdd = totals.plannedAdd,
        toReplace = totals.plannedReplace,
        toRemove = finalDiff?.toRemove?.size ?: 0,
        unchanged = finalDiff?.unchangedCount ?: 0,
        added = totals.added,
        replaced = totals.replaced,
        removed = totals.removed,
        failed = totals.failed,
    ),
    capacity = Capacity(totals.plannedBytes, totals.bytesTransferred, totals.freeBefore, totals.freeAfter),
    errors = errors.toList(),
    excluded = loaded?.excluded.orEmpty(),
    unplayable = finalDiff?.let { toUnplayable(it.unplayable, it.unsupported) }.orEmpty(),
    libraryId = libraryId,
    contentDigest = loaded?.contentDigest,
    unresolvedPlaylists = loaded?.unresolvedPlaylists?.map { it.lastKnownName }.orEmpty(),
    duplicatesDropped = marks.duplicatesDropped,
    removalSkipped = marks.removalSkipped,
    keyMismatch = marks.keyMismatch.takeIf { errors.any { it is SyncError.KeyMismatch } },
    errorDetail = marks.errorDetail.takeIf { errors.any { it is SyncError.KeyMismatch } },
    manifestVersionMismatch = marks.manifestVersionMismatch,
)

/** T3 の `result`。容量の事前確認で止めたときは、結果の区分によらず `not_started`。 */
internal fun resultTextOf(outcome: SyncOutcome, storagePreCheck: Boolean): String {
    if (storagePreCheck) return SyncRun.RESULT_NOT_STARTED
    return when (outcome) {
        SyncOutcome.Completed -> SyncRun.RESULT_SUCCESS
        SyncOutcome.Partial -> SyncRun.RESULT_PARTIAL
        SyncOutcome.Interrupted -> SyncRun.RESULT_INTERRUPTED
        SyncOutcome.Failed -> SyncRun.RESULT_FAILED
    }
}

/** T3 の `error_kind`。同期全体の E を先に、無ければ最初の曲単位の E。 */
internal fun errorKindOf(errors: List<SyncError>, userInterrupted: Boolean): String? =
    // 利用者の中断は、先に曲単位の失敗があっても空にする。曲単位の失敗は件数と T6 に残る
    if (userInterrupted) null else (errors.firstOrNull { it.scope == ErrorScope.Run } ?: errors.firstOrNull())?.let { "E${it.number}" }

internal fun issuesOf(runId: Long, loaded: Manifest?, finalDiff: Diff?, trackErrors: List<SyncError>): List<RunIssue> {
    val issues = mutableListOf<RunIssue>()
    loaded?.excluded?.forEach {
        issues += RunIssue(
            runId = runId,
            title = it.name ?: "",
            artist = it.artist,
            reason = it.reason.name.lowercase(),
            kind = RunIssue.KIND_EXCLUDED,
        )
    }
    finalDiff?.unplayable?.forEach { issues += unplayableIssue(runId, it, UNPLAYABLE_PROTECTED) }
    finalDiff?.unsupported?.forEach { issues += unplayableIssue(runId, it, UNPLAYABLE_UNSUPPORTED_FORMAT) }
    for (error in trackErrors) {
        val (id, name) = when (error) {
            is SyncError.FileMissing -> error.persistentId to error.name
            is SyncError.ManifestStale -> error.persistentId to error.name
            is SyncError.WriteFailed -> error.persistentId to error.name
            else -> continue
        }
        issues += RunIssue(runId = runId, persistentId = id, title = name, kind = RunIssue.KIND_FAILED, errorKind = "E${error.number}")
    }
    return issues
}

private const val UNPLAYABLE_PROTECTED = "protected"
private const val UNPLAYABLE_UNSUPPORTED_FORMAT = "unsupported_format"

/** T6 の `unplayable` の行。[reason] は `protected` か `unsupported_format`。どちらも転送しない。 */
private fun unplayableIssue(runId: Long, track: ManifestTrack, reason: String) = RunIssue(
    runId = runId,
    persistentId = track.persistentId,
    title = track.name ?: track.fileName,
    artist = track.artist,
    reason = reason,
    format = track.kind ?: track.extension,
    kind = RunIssue.KIND_UNPLAYABLE,
)
