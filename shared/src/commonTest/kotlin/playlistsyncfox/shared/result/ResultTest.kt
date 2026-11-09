package playlistsyncfox.shared.result

import playlistsyncfox.shared.manifest.ExcludedReason
import playlistsyncfox.shared.manifest.ExcludedTrack
import playlistsyncfox.shared.pid
import playlistsyncfox.shared.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResultTest {
    private val e1 = SyncError.ConnectionFailed(ConnectionFailure.Refused)
    private val e2 = SyncError.KeyMismatch(KeyMismatchCause.SignatureMismatch)
    private val e3 = SyncError.InsufficientStorage(1, StoragePhase.PreCheck)
    private val e4 = SyncError.XmlUnavailable
    private val e5 = SyncError.FileMissing("A", "n5", FileMissingReason.NotFound)
    private val e5b = SyncError.FileMissing("B", "n5b", FileMissingReason.Unreadable)
    private val e6NoTrack = SyncError.TransferInterrupted(null, 3)
    private val e7 = SyncError.TimeLimit
    private val e8 = SyncError.ManifestStale("C", "n8", retried = true)
    private val e9 = SyncError.WriteFailed("D", "n9", WriteStep.Move)

    private fun classify(
        errors: List<SyncError>,
        transferStarted: Boolean = false,
        userInterrupted: Boolean = false,
        stoppedWithoutE: Boolean = false,
    ) = classifyOutcome(errors, transferStarted, userInterrupted, stoppedWithoutE)

    private fun syncResult(outcome: SyncOutcome, errors: List<SyncError> = emptyList()) = SyncResult(
        startedAt = "2026-01-01T00:00:00.000Z",
        finishedAt = "2026-01-01T00:01:00.000Z",
        trigger = SyncTrigger.Manual,
        transport = Transport.Lan,
        outcome = outcome,
        hasMore = false,
        counts = Counts(1, 2, 3, 4, 5, 6, 7, 8),
        capacity = Capacity(10, 20, 30, null),
        errors = errors,
        excluded = listOf(
            ExcludedTrack("x", "y", ExcludedReason.NOT_FOUND),
            ExcludedTrack(null, null, ExcludedReason.NO_ID),
        ),
        unplayable = emptyList(),
        libraryId = "LIB",
        contentDigest = "digest",
        unresolvedPlaylists = emptyList(),
        duplicatesDropped = 0,
        removalSkipped = false,
        keyMismatch = null,
        errorDetail = null,
    )

    @Test
    fun `TS-73 エラーが空なら完了になる`() {
        assertEquals(SyncOutcome.Completed, classify(emptyList(), transferStarted = true))
    }

    @Test
    fun `TS-74 曲単位のエラーだけなら転送の開始に関わらず一部になる`() {
        val trackErrors = listOf(e5, e8, e9)

        assertEquals(SyncOutcome.Partial, classify(trackErrors, transferStarted = true))
        assertEquals(SyncOutcome.Partial, classify(trackErrors, transferStarted = false))
    }

    @Test
    fun `TS-75 同期全体のエラーが単独で転送前なら失敗になる`() {
        for (error in listOf(e1, e2, e3, e4, e6NoTrack)) {
            assertEquals(SyncOutcome.Failed, classify(listOf(error), transferStarted = false), error.toString())
        }
    }

    @Test
    fun `TS-76 同期全体のエラーと曲単位のエラーがあり転送を始めていたら中断になる`() {
        assertEquals(SyncOutcome.Interrupted, classify(listOf(e6NoTrack, e5), transferStarted = true))
    }

    @Test
    fun `TS-77 時間切れのエラーだけなら転送前でも中断になる`() {
        assertEquals(SyncOutcome.Interrupted, classify(listOf(e7), transferStarted = false))
    }

    @Test
    fun `TS-78 消してよいのは完了と一部のときだけ`() {
        assertEquals(
            listOf(true, true, false, false),
            listOf(SyncOutcome.Completed, SyncOutcome.Partial, SyncOutcome.Interrupted, SyncOutcome.Failed)
                .map { mayRemove(it) },
        )
    }

    @Test
    fun `TS-133 続きがあるのは中断で足す・置き換えに未完了が残るときだけ`() {
        assertTrue(hasMore(SyncOutcome.Interrupted, completedCount = 1, plannedCount = 3))
        assertFalse(hasMore(SyncOutcome.Interrupted, completedCount = 3, plannedCount = 3))
        assertFalse(hasMore(SyncOutcome.Failed, completedCount = 1, plannedCount = 3))
    }

    @Test
    fun `TS-134 利用者の中断が真なら誤りの有無と転送の開始に関わらず中断になる`() {
        for (errors in listOf(emptyList(), listOf(e5), listOf(e1))) {
            for (started in listOf(false, true)) {
                assertEquals(SyncOutcome.Interrupted, classify(errors, started, userInterrupted = true))
            }
        }
    }

    @Test
    fun `TS-157 エラーなしの停止は転送前なら失敗で転送後なら中断になる`() {
        assertEquals(SyncOutcome.Failed, classify(emptyList(), transferStarted = false, stoppedWithoutE = true))
        assertEquals(SyncOutcome.Interrupted, classify(emptyList(), transferStarted = true, stoppedWithoutE = true))
    }

    @Test
    fun `TS-157 エラーなしの停止に曲単位のエラーが重なっても転送後なら中断になる`() {
        assertEquals(SyncOutcome.Interrupted, classify(listOf(e5), transferStarted = true, stoppedWithoutE = true))
    }

    @Test
    fun `TS-147 エラーを番号ごとに数えて最初の曲名を返す`() {
        val entries = breakdown(listOf(e8, e5, e5b))

        assertEquals(
            listOf(BreakdownEntry(5, 2, "n5"), BreakdownEntry(8, 1, "n8")),
            entries,
        )
    }

    @Test
    fun `TS-147 エラーなしの停止だけの結果は内訳に何も載らない`() {
        assertTrue(breakdown(syncResult(SyncOutcome.Failed).errors).isEmpty())
    }

    @Test
    fun `保護付きの曲は曲名と種類と拡張子を保ち理由を Protected にして写す`() {
        val protectedTrack = track(pid('A'), name = "曲", extension = "m4p").copy(kind = "Protected AAC")

        assertEquals(
            listOf(UnplayableTrack("曲", "Protected AAC", "m4p", UnplayableReason.Protected)),
            toUnplayable(listOf(protectedTrack)),
        )
    }

    @Test
    fun `再生できない形式の曲は理由を UnsupportedFormat にして保護付きの後ろに写す`() {
        val protectedTrack = track(pid('A'), name = "保護", extension = "m4p")
        val unsupportedTrack = track(pid('B'), name = "形式", extension = "aiff").copy(kind = "AIFF")

        assertEquals(
            listOf(
                UnplayableTrack("保護", null, "m4p", UnplayableReason.Protected),
                UnplayableTrack("形式", "AIFF", "aiff", UnplayableReason.UnsupportedFormat),
            ),
            toUnplayable(listOf(protectedTrack), listOf(unsupportedTrack)),
        )
    }

    @Test
    fun `TS-149 報告は区分を 4 値のまま写し曲名を含まず除外の件数を持つ`() {
        for (outcome in SyncOutcome.entries) {
            val result = syncResult(outcome, listOf(e5, e5b, e8))

            val report = toReport(result)

            assertEquals(outcome, report.outcome)
            assertEquals(mapOf(5 to 2, 8 to 1), report.failureCounts)
            assertEquals(2, report.excludedCount)
            assertEquals(result.counts, report.counts)
            assertEquals(result.capacity, report.capacity)
            assertFalse(report.toString().contains("n5"))
        }
    }
}
