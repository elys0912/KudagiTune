package playlistsyncfox.android.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.db.encodeResult
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
import playlistsyncfox.shared.result.classifyOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L
private const val NOW = 10_000_000_000L

private var nextId = 1L

private fun run(
    trigger: String = SyncRun.TRIGGER_MANUAL,
    result: String = SyncRun.RESULT_SUCCESS,
    startedAt: Long = NOW - 5 * 60 * MIN,
    endedAt: Long? = startedAt + 1000,
    errorKind: String? = null,
    resultJson: String? = null,
    contentDigest: String? = null,
    libraryId: String? = null,
) = SyncRun(
    runId = nextId++,
    deviceId = DEVICE,
    trigger = trigger,
    startedAt = startedAt,
    endedAt = endedAt,
    result = result,
    errorKind = errorKind,
    contentDigest = contentDigest,
    libraryId = libraryId,
    resultJson = resultJson,
)

private fun e2(keyMismatch: Boolean, detail: ErrorDetail? = ErrorDetail.NoDetail) = encodeResult(sampleResult(
    errors = listOf(SyncError.KeyMismatch(KeyMismatchCause.SignatureMismatch)),
    keyMismatch = keyMismatch,
    errorDetail = detail,
))

private fun sampleResult(
    errors: List<SyncError> = emptyList(),
    outcome: SyncOutcome = SyncOutcome.Failed,
    keyMismatch: Boolean? = null,
    errorDetail: ErrorDetail? = null,
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
)

class SyncPolicyTest {
    @Test
    fun `TA-063 手動の success の ended_at から V19 未満の自動は抑止する`() {
        val runs = listOf(run(endedAt = NOW - SyncValues.AUTO_SUPPRESS_MS + 1000))

        assertEquals(SuppressReason.RecentManual, autoSuppression(runs, pairedAt = 0, now = NOW))
    }

    @Test
    fun `TA-063 手動の partial の ended_at から V19 を過ぎた自動は続行する`() {
        val runs = listOf(run(result = SyncRun.RESULT_PARTIAL, endedAt = NOW - SyncValues.AUTO_SUPPRESS_MS - 1000))

        assertNull(autoSuppression(runs, pairedAt = 0, now = NOW))
    }

    @Test
    fun `TA-063 失敗か中断か未開始の手動の後は V19 の内でも抑止しない`() {
        for (result in listOf(SyncRun.RESULT_FAILED, SyncRun.RESULT_INTERRUPTED, SyncRun.RESULT_NOT_STARTED)) {
            val runs = listOf(run(result = result, endedAt = NOW - 1000))

            assertNull(autoSuppression(runs, pairedAt = 0, now = NOW), result)
        }
    }

    @Test
    fun `TA-026 paired_at 以後の鍵の不一致の E2 は抑止し間に手動の E1 が入っても外れない`() {
        val runs = listOf(
            run(result = SyncRun.RESULT_FAILED, startedAt = NOW - 100 * MIN, errorKind = "E1", resultJson = "{}"),
            run(result = SyncRun.RESULT_FAILED, startedAt = NOW - 200 * MIN, errorKind = "E2", resultJson = e2(keyMismatch = true)),
        )

        assertEquals(SuppressReason.KeyMismatch, autoSuppression(runs, pairedAt = NOW - 300 * MIN, now = NOW))
    }

    @Test
    fun `TA-026 E2 の ended_at が paired_at より前なら再ペアリング後として再開する`() {
        val runs = listOf(run(result = SyncRun.RESULT_FAILED, startedAt = NOW - 200 * MIN, errorKind = "E2", resultJson = e2(keyMismatch = true)))

        assertNull(autoSuppression(runs, pairedAt = NOW - 100 * MIN, now = NOW))
    }

    @Test
    fun `TA-026 時計のずれ replay 429 の E2 は鍵の不一致でないので抑止しない`() {
        for (detail in listOf(ErrorDetail.ClockSkew, ErrorDetail.Replay, ErrorDetail.RateLimited)) {
            val runs = listOf(run(result = SyncRun.RESULT_FAILED, errorKind = "E2", resultJson = e2(keyMismatch = false, detail = detail)))

            assertNull(autoSuppression(runs, pairedAt = 0, now = NOW), detail.name)
        }
    }

    @Test
    fun `TA-045 最新の E3 の後に手動の E1 や中断だけなら抑止する`() {
        val runs = listOf(
            run(result = SyncRun.RESULT_INTERRUPTED, startedAt = NOW - 100 * MIN),
            run(result = SyncRun.RESULT_FAILED, startedAt = NOW - 150 * MIN, errorKind = "E1"),
            run(result = SyncRun.RESULT_NOT_STARTED, startedAt = NOW - 200 * MIN, errorKind = "E3"),
        )

        assertEquals(SuppressReason.StorageShortage, autoSuppression(runs, pairedAt = 0, now = NOW))
    }

    @Test
    fun `TA-045 最新の E3 の後に手動の success があれば再開する`() {
        for (result in listOf(SyncRun.RESULT_SUCCESS, SyncRun.RESULT_PARTIAL)) {
            val runs = listOf(
                run(result = result, startedAt = NOW - 100 * MIN),
                run(result = SyncRun.RESULT_NOT_STARTED, startedAt = NOW - 200 * MIN, errorKind = "E3"),
            )

            assertNull(autoSuppression(runs, pairedAt = 0, now = NOW), result)
        }
    }

    @Test
    fun `TA-045 E3 の行が無ければ当たらない`() {
        assertNull(autoSuppression(emptyList(), pairedAt = 0, now = NOW))
    }

    @Test
    fun `TA-056 前面へ昇格してから V17 より 1 秒短いうちは偽で V17 に達したら真`() {
        assertFalse(isNearLimit(foregroundSince = 0, now = SyncValues.NEAR_LIMIT_MS - 1000))
        assertTrue(isNearLimit(foregroundSince = 0, now = SyncValues.NEAR_LIMIT_MS))
    }

    @Test
    fun `TA-018 停止の理由が時間切れなら E7 で利用者の中断なら E なし`() {
        val timeout = decideStop(StopCause.Timeout)
        val user = decideStop(StopCause.UserCancel)

        assertEquals(SyncError.TimeLimit, timeout.error)
        assertFalse(timeout.userInterrupted)
        assertNull(user.error)
        assertTrue(user.userInterrupted)
    }

    @Test
    fun `TA-077 区分から T3 の result への写しは完了と一部と中断と失敗が対応する`() {
        val stale = SyncError.ManifestStale("x", "n", false)
        val transfer = SyncError.TransferInterrupted(null, 4)

        assertEquals(SyncOutcome.Completed, classifyOutcome(emptyList(), true, false, false))
        assertEquals(SyncOutcome.Partial, classifyOutcome(listOf(stale), true, false, false))
        assertEquals(SyncOutcome.Interrupted, classifyOutcome(listOf(transfer), true, false, false))
        assertEquals(SyncOutcome.Failed, classifyOutcome(listOf(SyncError.XmlUnavailable), false, false, false))
        // E なしの停止は、転送の前なら失敗、転送の後なら中断
        assertEquals(SyncOutcome.Failed, classifyOutcome(emptyList(), false, false, stoppedWithoutE = true))
        assertEquals(SyncOutcome.Interrupted, classifyOutcome(emptyList(), true, false, stoppedWithoutE = true))
    }

    @Test
    fun `TA-069 P4 の outcome は not_started だけ failed に畳み他の 4 値はそのまま`() {
        val json = encodeResult(sampleResult())
        val expected = mapOf(
            SyncRun.RESULT_SUCCESS to "success",
            SyncRun.RESULT_PARTIAL to "partial",
            SyncRun.RESULT_INTERRUPTED to "interrupted",
            SyncRun.RESULT_FAILED to "failed",
            SyncRun.RESULT_NOT_STARTED to "failed",
        )

        for ((result, outcome) in expected) {
            val body = Json.parseToJsonElement(reportBody(run(result = result, resultJson = json))!!).jsonObject
            assertEquals(outcome, body.getValue("outcome").jsonPrimitive.content, result)
        }
    }

    @Test
    fun `TA-069 P4 の本文は件数と容量と失敗の内訳を持ち曲名を含まない`() {
        val result = sampleResult(errors = listOf(SyncError.FileMissing("A", "秘密の曲名", FileMissingReason.NotFound), SyncError.WriteFailed("B", "別の曲名", WriteStep.Move)))

        val text = reportBody(run(resultJson = encodeResult(result)))!!
        val body = Json.parseToJsonElement(text).jsonObject

        assertEquals(5, body.getValue("counts").jsonObject.getValue("to_add").jsonPrimitive.int)
        assertEquals(1, body.getValue("failure_counts").jsonObject.getValue("E5").jsonPrimitive.int)
        assertEquals(1, body.getValue("failure_counts").jsonObject.getValue("E9").jsonPrimitive.int)
        assertEquals(2, body.getValue("excluded_count").jsonPrimitive.int)
        assertFalse("曲名" in text)
    }

    @Test
    fun `強制終了の跡の result_json が空の行は報告の本文を作らない`() {
        assertNull(reportBody(run(result = SyncRun.RESULT_INTERRUPTED, resultJson = null)))
    }

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
    fun `記録の library_id は NULL でない最新の行の値で無ければ初回で null`() {
        assertNull(recordedLibraryId(listOf(run(libraryId = null))))
        assertEquals("NEW", recordedLibraryId(listOf(run(libraryId = null), run(libraryId = "NEW"), run(libraryId = "OLD"))))
    }

    @Test
    fun `TA-021 手動は承認した件数以内なら Allowed でなくても実行し超えたら飛ばす`() {
        assertTrue(mayExecuteRemoval(SyncRun.TRIGGER_MANUAL, gateAllowed = false, removeCount = 2, deleteApproved = true, approvedDeleteCount = 2))
        assertFalse(mayExecuteRemoval(SyncRun.TRIGGER_MANUAL, gateAllowed = false, removeCount = 3, deleteApproved = true, approvedDeleteCount = 2))
        // Allowed でも承認が無ければ消さない（D19）
        assertFalse(mayExecuteRemoval(SyncRun.TRIGGER_MANUAL, gateAllowed = true, removeCount = 1, deleteApproved = false, approvedDeleteCount = 0))
    }

    @Test
    fun `TA-023 自動は Allowed だけ実行し承認の入力があっても飛ばす`() {
        assertTrue(mayExecuteRemoval(SyncRun.TRIGGER_AUTO, gateAllowed = true, removeCount = 3, deleteApproved = false, approvedDeleteCount = 0))
        assertFalse(mayExecuteRemoval(SyncRun.TRIGGER_AUTO, gateAllowed = false, removeCount = 3, deleteApproved = true, approvedDeleteCount = 9))
    }

    @Test
    fun `P4 の日時は常にミリ秒の 3 桁つきで UTC の Z`() {
        assertEquals("2026-11-07T00:00:00.000Z", isoMillis(1_794_009_600_000))
    }
}
