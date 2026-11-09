package playlistsyncfox.android.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.shared.manifest.ExcludedReason
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MISMATCH_KEY = "manifest_version_mismatch"

/** 全項目に 0 でも空でもない値を入れた結果。往復で項目が欠けたら、元の値と食い違う。 */
private fun sampleResult(
    errors: List<SyncError> = emptyList(),
    outcome: SyncOutcome = SyncOutcome.Failed,
    keyMismatch: Boolean? = null,
    errorDetail: ErrorDetail? = null,
    manifestVersionMismatch: Boolean = false,
) = SyncResult(
    startedAt = "2026-11-07T00:00:00.000Z",
    finishedAt = "2026-11-07T00:01:00.000Z",
    trigger = SyncTrigger.Manual,
    transport = Transport.Lan,
    outcome = outcome,
    hasMore = true,
    counts = Counts(5, 4, 3, 2, 1, 1, 1, 2),
    capacity = Capacity(1000, 500, 9000, null),
    errors = errors,
    excluded = listOf(ExcludedTrack("曲", "アーティスト", ExcludedReason.NOT_FOUND), ExcludedTrack(null, null, ExcludedReason.NO_ID)),
    unplayable = listOf(UnplayableTrack("保護", "Protected AAC", "m4p", UnplayableReason.Protected), UnplayableTrack(null, null, "m4a", UnplayableReason.UnsupportedFormat)),
    libraryId = "LIB",
    contentDigest = "digest",
    unresolvedPlaylists = listOf("未解決"),
    duplicatesDropped = 2,
    removalSkipped = true,
    keyMismatch = keyMismatch,
    errorDetail = errorDetail,
    manifestVersionMismatch = manifestVersionMismatch,
)

class StoredResultTest {
    @Test
    fun `TA-036 SyncResult の全項目が result_json に往復して同じ値で戻る`() {
        val errors = listOf(
            SyncError.ConnectionFailed(ConnectionFailure.Timeout),
            SyncError.KeyMismatch(KeyMismatchCause.PinMismatch),
            SyncError.InsufficientStorage(123, StoragePhase.Writing),
            SyncError.XmlUnavailable,
            SyncError.FileMissing("A", "曲A", FileMissingReason.Unreadable),
            SyncError.TransferInterrupted("B", 4),
            SyncError.TransferInterrupted(null, 2),
            SyncError.TimeLimit,
            SyncError.ManifestStale("C", "曲C", true),
            SyncError.WriteFailed("D", "曲D", WriteStep.MediaStoreRegistration),
        )
        val original = sampleResult(errors, SyncOutcome.Interrupted, keyMismatch = true, errorDetail = ErrorDetail.PinMismatch)

        val json = encodeResult(original)

        assertEquals(original, decodeResult(json))
        for (key in listOf("removal_skipped", "key_mismatch", "error_detail")) assertTrue("\"$key\"" in json, key)
    }

    @Test
    fun `TA-036 detail なしの 401 と空の値も往復で区別される`() {
        val noDetail = sampleResult(keyMismatch = true, errorDetail = ErrorDetail.NoDetail)
        val none = sampleResult(keyMismatch = null, errorDetail = null)

        assertEquals(ErrorDetail.NoDetail, decodeResult(encodeResult(noDetail)).errorDetail)
        assertNull(decodeResult(encodeResult(none)).errorDetail)
    }

    @Test
    fun `版違いの印が真の結果は、保存して読み戻しても全体が同じで、キーは manifest_version_mismatch になる`() {
        val original = sampleResult(manifestVersionMismatch = true)

        val saved = encodeResult(original)

        assertEquals(original, decodeResult(saved))
        assertTrue(Json.parseToJsonElement(saved).jsonObject.getValue(MISMATCH_KEY).jsonPrimitive.boolean)
    }

    @Test
    fun `manifest_version_mismatch を持たない既存の行は、偽として読む`() {
        val saved = Json.parseToJsonElement(encodeResult(sampleResult(manifestVersionMismatch = true))).jsonObject
        val savedBeforeTheKeyExisted = JsonObject(saved - MISMATCH_KEY).toString()

        assertFalse(decodeResult(savedBeforeTheKeyExisted).manifestVersionMismatch)
    }
}
