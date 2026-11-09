package playlistsyncfox.android.db

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import playlistsyncfox.shared.manifest.ExcludedTrack
import playlistsyncfox.shared.result.Capacity
import playlistsyncfox.shared.result.ConnectionFailure
import playlistsyncfox.shared.result.Counts
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.FileMissingReason
import playlistsyncfox.shared.result.KeyMismatchCause
import playlistsyncfox.shared.result.StoragePhase
import playlistsyncfox.shared.result.SyncError
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncResult
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import playlistsyncfox.shared.result.UnplayableReason
import playlistsyncfox.shared.result.UnplayableTrack
import playlistsyncfox.shared.result.WriteStep

// T3 の `result_json`。SyncResult の全体を 1 列の JSON で持つ（X6-1）。
// shared の型は直列化の印を持たないため、保存用の型に写して往復する。

private val storedJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private class StoredUnplayable(val name: String? = null, val kind: String? = null, val extension: String, val reason: String)

@Serializable
private class StoredError(
    val number: Int,
    @SerialName("persistent_id") val persistentId: String? = null,
    val name: String? = null,
    val attempts: Int? = null,
    val retried: Boolean? = null,
    @SerialName("shortfall_bytes") val shortfallBytes: Long? = null,
    val detail: String? = null,
)

@Serializable
private class StoredResult(
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    val trigger: String,
    val transport: String,
    val outcome: String,
    @SerialName("has_more") val hasMore: Boolean,
    @SerialName("to_add") val toAdd: Int,
    @SerialName("to_replace") val toReplace: Int,
    @SerialName("to_remove") val toRemove: Int,
    val unchanged: Int,
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val failed: Int,
    @SerialName("diff_bytes") val diffBytes: Long,
    @SerialName("bytes_transferred") val bytesTransferred: Long,
    @SerialName("free_bytes_before") val freeBytesBefore: Long? = null,
    @SerialName("free_bytes_after") val freeBytesAfter: Long? = null,
    val errors: List<StoredError>,
    val excluded: List<ExcludedTrack>,
    val unplayable: List<StoredUnplayable>,
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("content_digest") val contentDigest: String? = null,
    @SerialName("unresolved_playlists") val unresolvedPlaylists: List<String>,
    @SerialName("duplicates_dropped") val duplicatesDropped: Int,
    @SerialName("removal_skipped") val removalSkipped: Boolean,
    @SerialName("key_mismatch") val keyMismatch: Boolean? = null,
    @SerialName("error_detail") val errorDetail: String? = null,
    // 版違いの印（X6-9）。この項目を持たない既存の行は偽として読む
    @SerialName("manifest_version_mismatch") val manifestVersionMismatch: Boolean = false,
)

fun encodeResult(result: SyncResult): String = storedJson.encodeToString(StoredResult.serializer(), result.toStored())

fun decodeResult(text: String): SyncResult = storedJson.decodeFromString(StoredResult.serializer(), text).toResult()

/** `result_json` のうち、自動の抑止の判定に要る `key_mismatch` だけを読む。読めない行は偽。 */
fun isKeyMismatch(resultJson: String?): Boolean =
    resultJson != null && runCatching { storedJson.decodeFromString(StoredResult.serializer(), resultJson).keyMismatch == true }
        .getOrDefault(false)

private fun SyncResult.toStored() = StoredResult(
    startedAt = startedAt,
    finishedAt = finishedAt,
    trigger = trigger.name,
    transport = transport.name,
    outcome = outcome.name,
    hasMore = hasMore,
    toAdd = counts.toAdd,
    toReplace = counts.toReplace,
    toRemove = counts.toRemove,
    unchanged = counts.unchanged,
    added = counts.added,
    replaced = counts.replaced,
    removed = counts.removed,
    failed = counts.failed,
    diffBytes = capacity.diffBytes,
    bytesTransferred = capacity.bytesTransferred,
    freeBytesBefore = capacity.freeBytesBefore,
    freeBytesAfter = capacity.freeBytesAfter,
    errors = errors.map { it.toStored() },
    excluded = excluded,
    unplayable = unplayable.map { StoredUnplayable(it.name, it.kind, it.extension, it.reason.name) },
    libraryId = libraryId,
    contentDigest = contentDigest,
    unresolvedPlaylists = unresolvedPlaylists,
    duplicatesDropped = duplicatesDropped,
    removalSkipped = removalSkipped,
    keyMismatch = keyMismatch,
    errorDetail = errorDetail?.let(::detailText),
    manifestVersionMismatch = manifestVersionMismatch,
)

private fun StoredResult.toResult() = SyncResult(
    startedAt = startedAt,
    finishedAt = finishedAt,
    trigger = SyncTrigger.valueOf(trigger),
    transport = Transport.valueOf(transport),
    outcome = SyncOutcome.valueOf(outcome),
    hasMore = hasMore,
    counts = Counts(toAdd, toReplace, toRemove, unchanged, added, replaced, removed, failed),
    capacity = Capacity(diffBytes, bytesTransferred, freeBytesBefore, freeBytesAfter),
    errors = errors.map { it.toError() },
    excluded = excluded,
    unplayable = unplayable.map { UnplayableTrack(it.name, it.kind, it.extension, UnplayableReason.valueOf(it.reason)) },
    libraryId = libraryId,
    contentDigest = contentDigest,
    unresolvedPlaylists = unresolvedPlaylists,
    duplicatesDropped = duplicatesDropped,
    removalSkipped = removalSkipped,
    keyMismatch = keyMismatch,
    errorDetail = errorDetail?.let(::detailOf),
    manifestVersionMismatch = manifestVersionMismatch,
)

/** `detail` なしの 401 は空文字で持つ。 */
private fun detailText(detail: ErrorDetail): String = when (detail) {
    ErrorDetail.ClockSkew -> "clock_skew"
    ErrorDetail.Replay -> "replay"
    ErrorDetail.PinMismatch -> "pin_mismatch"
    ErrorDetail.RateLimited -> "rate_limited"
    ErrorDetail.NoDetail -> ""
}

private fun detailOf(text: String): ErrorDetail = when (text) {
    "clock_skew" -> ErrorDetail.ClockSkew
    "replay" -> ErrorDetail.Replay
    "pin_mismatch" -> ErrorDetail.PinMismatch
    "rate_limited" -> ErrorDetail.RateLimited
    else -> ErrorDetail.NoDetail
}

private fun SyncError.toStored(): StoredError = when (this) {
    is SyncError.ConnectionFailed -> StoredError(number, detail = cause.name)
    is SyncError.KeyMismatch -> StoredError(number, detail = cause.name)
    is SyncError.InsufficientStorage -> StoredError(number, shortfallBytes = shortfallBytes, detail = phase.name)
    SyncError.XmlUnavailable -> StoredError(number)
    is SyncError.FileMissing -> StoredError(number, persistentId, name, detail = reason.name)
    is SyncError.TransferInterrupted -> StoredError(number, persistentId, attempts = attempts)
    SyncError.TimeLimit -> StoredError(number)
    is SyncError.ManifestStale -> StoredError(number, persistentId, name, retried = retried)
    is SyncError.WriteFailed -> StoredError(number, persistentId, name, detail = step.name)
}

private fun StoredError.toError(): SyncError = when (number) {
    1 -> SyncError.ConnectionFailed(ConnectionFailure.valueOf(detail!!))
    2 -> SyncError.KeyMismatch(KeyMismatchCause.valueOf(detail!!))
    3 -> SyncError.InsufficientStorage(shortfallBytes!!, StoragePhase.valueOf(detail!!))
    4 -> SyncError.XmlUnavailable
    5 -> SyncError.FileMissing(persistentId!!, name!!, FileMissingReason.valueOf(detail!!))
    6 -> SyncError.TransferInterrupted(persistentId, attempts!!)
    7 -> SyncError.TimeLimit
    8 -> SyncError.ManifestStale(persistentId!!, name!!, retried!!)
    else -> SyncError.WriteFailed(persistentId!!, name!!, WriteStep.valueOf(detail!!))
}
