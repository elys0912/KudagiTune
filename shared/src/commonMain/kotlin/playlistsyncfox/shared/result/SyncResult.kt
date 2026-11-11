package playlistsyncfox.shared.result

import playlistsyncfox.shared.manifest.ExcludedTrack
import playlistsyncfox.shared.manifest.ManifestTrack

enum class SyncTrigger { Manual, Auto }

enum class Transport { Lan, Tailscale, UsbTethering, BluetoothTethering }

/** 区分。T3 の `result` と P4 の `outcome` への写しは M4 が行う。 */
enum class SyncOutcome { Completed, Partial, Interrupted, Failed }

/** [UnsupportedFormat] は新しくは付けない。端末に保存された過去の同期結果を読み戻すために残す。 */
enum class UnplayableReason { Protected, UnsupportedFormat }

/** E2 の詳細。[NoDetail] は `detail` なしの 401。 */
enum class ErrorDetail { ClockSkew, Replay, PinMismatch, RateLimited, NoDetail }

/** 予定と完了の件数。マニフェストを取れなかった実行は全項目 0。 */
data class Counts(
    val toAdd: Int,
    val toReplace: Int,
    val toRemove: Int,
    val unchanged: Int,
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val failed: Int,
)

/** 空き容量は測れなかったときに null。 */
data class Capacity(
    val diffBytes: Long,
    val bytesTransferred: Long,
    val freeBytesBefore: Long?,
    val freeBytesAfter: Long?,
)

data class UnplayableTrack(
    val name: String?,
    val kind: String?,
    val extension: String,
    val reason: UnplayableReason,
)

data class SyncResult(
    val startedAt: String,
    val finishedAt: String,
    val trigger: SyncTrigger,
    val transport: Transport,
    val outcome: SyncOutcome,
    val hasMore: Boolean,
    val counts: Counts,
    val capacity: Capacity,
    val errors: List<SyncError>,
    val excluded: List<ExcludedTrack>,
    val unplayable: List<UnplayableTrack>,
    val libraryId: String?,
    val contentDigest: String?,
    val unresolvedPlaylists: List<String>,
    val duplicatesDropped: Int,
    val removalSkipped: Boolean,
    val keyMismatch: Boolean?,
    val errorDetail: ErrorDetail?,
    /** マニフェストの版違い（`ManifestReadResult.VersionMismatch`）で止まったとき真。壊れたマニフェストでは偽。 */
    val manifestVersionMismatch: Boolean = false,
)

/** P4 の本文の元。曲名を含めない。 */
data class SyncReport(
    val startedAt: String,
    val finishedAt: String,
    val outcome: SyncOutcome,
    val hasMore: Boolean,
    val counts: Counts,
    val capacity: Capacity,
    val failureCounts: Map<Int, Int>,
    val excludedCount: Int,
)

/** E の番号ごとの件数と、最初の曲名。 */
internal data class BreakdownEntry(val errorNumber: Int, val count: Int, val firstTrackName: String?)

/**
 * 同期の区分を決める。
 *
 * @param transferStarted 1 曲でも取得の要求を送ったか
 * @param stoppedWithoutE E なしで止めたか。`errors` には入らない
 */
fun classifyOutcome(
    errors: List<SyncError>,
    transferStarted: Boolean,
    userInterrupted: Boolean,
    stoppedWithoutE: Boolean,
): SyncOutcome {
    if (userInterrupted || errors.any { it is SyncError.TimeLimit }) return SyncOutcome.Interrupted

    val stoppedRun = stoppedWithoutE || errors.any { it.scope == ErrorScope.Run }
    return when {
        stoppedRun && transferStarted -> SyncOutcome.Interrupted
        stoppedRun -> SyncOutcome.Failed
        errors.isEmpty() -> SyncOutcome.Completed
        else -> SyncOutcome.Partial
    }
}

/** 「消す」を実行してよいのは、完了と一部のとき。 */
fun mayRemove(outcome: SyncOutcome): Boolean = outcome == SyncOutcome.Completed || outcome == SyncOutcome.Partial

/** 続きがあるのは、中断で足す・置き換えに未完了が残るとき。 */
fun hasMore(outcome: SyncOutcome, completedCount: Int, plannedCount: Int): Boolean =
    outcome == SyncOutcome.Interrupted && completedCount < plannedCount

/** E の番号の昇順。 */
internal fun breakdown(errors: List<SyncError>): List<BreakdownEntry> =
    errors.groupBy { it.number }.toSortedMap().map { (number, sameNumber) ->
        BreakdownEntry(number, sameNumber.size, sameNumber.firstNotNullOfOrNull { (it as? TrackError)?.name })
    }

fun toReport(result: SyncResult): SyncReport = SyncReport(
    startedAt = result.startedAt,
    finishedAt = result.finishedAt,
    outcome = result.outcome,
    hasMore = result.hasMore,
    counts = result.counts,
    capacity = result.capacity,
    failureCounts = breakdown(result.errors).associate { it.errorNumber to it.count },
    excludedCount = result.excluded.size,
)

/** 保護付きの曲を、再生できない曲の一覧の形に写す。 */
fun toUnplayable(protected: List<ManifestTrack>): List<UnplayableTrack> =
    protected.map { UnplayableTrack(it.name, it.kind, it.extension, UnplayableReason.Protected) }
