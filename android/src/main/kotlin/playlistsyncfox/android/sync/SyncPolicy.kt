package playlistsyncfox.android.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.db.isKeyMismatch
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.SyncError
import playlistsyncfox.shared.result.toReport
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 進行の段（X6-3）。X7-1-4 の `stage` と同じ語。 */
enum class SyncStage { Connecting, Manifest, Diff, Capacity, Transfer, Playlist, Finished }

/** 曲ごとの行の状態（X6-3 の `tracks`。D22）。 */
enum class TrackState { Waiting, Transferring, Done, Failed }

/**
 * S3 の曲ごとの行（X6-3 の `tracks`。D22）。[receivedBytes] は `Transferring` のとき、曲の頭から数えて書き込んだ量。
 * [errorNumber] は `Failed` のときの E の番号。
 */
data class TrackRow(
    val persistentId: String,
    val title: String,
    val size: Long,
    val state: TrackState = TrackState.Waiting,
    val receivedBytes: Long = 0,
    val errorNumber: Int? = null,
)

/** ワーカーが更新し、画面と通知が購読する読み取りの状態。DB には持たない。[outcome] は終了時の T3 の `result`。 */
data class SyncProgress(
    val stage: SyncStage,
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val doneBytes: Long = 0,
    val totalBytes: Long = 0,
    val currentTitle: String? = null,
    val shortageBytes: Long? = null,
    val nearLimit: Boolean = false,
    val outcome: String? = null,
    val errorDetail: ErrorDetail? = null,
    val tracks: List<TrackRow> = emptyList(),
)

/** 前面へ昇格してからの経過が V17 に達したら真。V17 で同期は区切らず、注意を出すだけ（J33）。 */
fun isNearLimit(foregroundSince: Long, now: Long): Boolean = now - foregroundSince >= SyncValues.NEAR_LIMIT_MS

/** OS の停止の理由。時間切れか、利用者の中断（アプリからの取り消し）か。 */
enum class StopCause { Timeout, UserCancel }

/** 停止の理由から決める `error_kind`（E7 か空）と、利用者の中断かどうか。 */
data class StopDecision(val error: SyncError?, val userInterrupted: Boolean)

fun decideStop(cause: StopCause): StopDecision = when (cause) {
    StopCause.Timeout -> StopDecision(SyncError.TimeLimit, userInterrupted = false)
    StopCause.UserCancel -> StopDecision(null, userInterrupted = true)
}

enum class SuppressReason { RecentManual, KeyMismatch, StorageShortage }

/**
 * 自動の抑止（X6-3 の 2 の 1）。T3 の行の種類と T4 の `paired_at` から導く。[runs] は新しい順。
 * (a) 手動の `success`・`partial` の最新の `ended_at` から V19 以内（失敗、中断、`not_started` は起点にしない）
 * (b) `ended_at` が [pairedAt] 以後の行に、鍵の不一致の E2 がある
 * (c) 最新の E3 の行より後に、手動の `success` か `partial` の行が無い
 */
fun autoSuppression(runs: List<SyncRun>, pairedAt: Long, now: Long): SuppressReason? {
    fun SyncRun.isManualDone() =
        trigger == SyncRun.TRIGGER_MANUAL && (result == SyncRun.RESULT_SUCCESS || result == SyncRun.RESULT_PARTIAL) && endedAt != null

    val lastManualEnd = runs.filter { it.isManualDone() }.maxOfOrNull { it.endedAt!! }
    if (lastManualEnd != null && now - lastManualEnd < SyncValues.AUTO_SUPPRESS_MS) return SuppressReason.RecentManual

    if (runs.any { it.endedAt != null && it.endedAt >= pairedAt && it.errorKind == "E2" && isKeyMismatch(it.resultJson) }) {
        return SuppressReason.KeyMismatch
    }

    val lastStorageShortage = runs.firstOrNull { it.errorKind == "E3" } ?: return null
    val resolved = runs.any { it.isManualDone() && it.isAfter(lastStorageShortage) }
    return if (resolved) null else SuppressReason.StorageShortage
}

internal fun SyncRun.isAfter(other: SyncRun) = startedAt > other.startedAt || (startedAt == other.startedAt && runId > other.runId)

/** 差分なし・同じ `content_digest` の判定の基準：`content_digest` が NULL でない最新の行。 */
fun digestBase(runs: List<SyncRun>): SyncRun? = runs.firstOrNull { it.contentDigest != null }

/** 基準の行が、[digest] と同じ内容を完了（success）したものか。 */
fun matchesCompleted(base: SyncRun, digest: String): Boolean =
    base.contentDigest == digest && base.result == SyncRun.RESULT_SUCCESS

/** 強制終了の跡の行（始めの整理で閉じた行）。`content_digest` が NULL で基準から外れるため別に見る。 */
fun isCrashTrace(run: SyncRun): Boolean =
    run.result == SyncRun.RESULT_INTERRUPTED && run.endedAt == run.startedAt && run.errorKind == null && run.resultJson == null

/** 差分の記録 `library_id`：この列が NULL でない最新の行の値。無ければ初回で null。 */
fun recordedLibraryId(runs: List<SyncRun>): String? = runs.firstOrNull { it.libraryId != null }?.libraryId

/** 「消す」を実行してよいか（X6-3 の 6）。自動は `Allowed` だけ。手動は 1 件でも承認が要り、承認した件数以内なら実行する（D19）。 */
fun mayExecuteRemoval(
    trigger: String,
    gateAllowed: Boolean,
    removeCount: Int,
    deleteApproved: Boolean,
    approvedDeleteCount: Int,
): Boolean = removeCount == 0 ||
    if (trigger == SyncRun.TRIGGER_MANUAL) deleteApproved && removeCount <= approvedDeleteCount else gateAllowed

private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** P4 が求める `YYYY-MM-DDTHH:mm:ss.SSSZ`。 */
fun isoMillis(epochMillis: Long): String = isoMillis.format(Instant.ofEpochMilli(epochMillis))

/**
 * P4 の本文を T3 の行から作る（初回の報告も再送も同じ）。`outcome` は T3 の `result` から写し、`not_started` は
 * `failed` に畳む（X3-5）。知らない語の行と、`result_json` が空の行（強制終了の跡）は null。
 */
fun reportBody(run: SyncRun): String? {
    val outcome = when (run.result) {
        SyncRun.RESULT_SUCCESS -> "success"
        SyncRun.RESULT_PARTIAL -> "partial"
        SyncRun.RESULT_INTERRUPTED -> "interrupted"
        SyncRun.RESULT_FAILED, SyncRun.RESULT_NOT_STARTED -> "failed"
        else -> return null
    }
    val report = toReport(decodeResult(run.resultJson ?: return null))
    val body: JsonObject = buildJsonObject {
        put("started_at", report.startedAt)
        put("finished_at", report.finishedAt)
        put("outcome", outcome)
        put("has_more", report.hasMore)
        put("counts", buildJsonObject {
            put("to_add", report.counts.toAdd)
            put("to_replace", report.counts.toReplace)
            put("to_remove", report.counts.toRemove)
            put("unchanged", report.counts.unchanged)
            put("added", report.counts.added)
            put("replaced", report.counts.replaced)
            put("removed", report.counts.removed)
            put("failed", report.counts.failed)
        })
        put("capacity", buildJsonObject {
            put("diff_bytes", report.capacity.diffBytes)
            put("bytes_transferred", report.capacity.bytesTransferred)
            report.capacity.freeBytesBefore?.let { put("free_bytes_before", it) }
            report.capacity.freeBytesAfter?.let { put("free_bytes_after", it) }
        })
        put("failure_counts", buildJsonObject {
            report.failureCounts.toSortedMap().forEach { (number, count) -> put("E$number", JsonPrimitive(count)) }
        })
        put("excluded_count", report.excludedCount)
    }
    return body.toString()
}
