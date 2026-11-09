package playlistsyncfox.android.ui

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.encodeResult
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage
import playlistsyncfox.android.sync.TrackRow
import playlistsyncfox.android.sync.TrackState
import playlistsyncfox.shared.result.Capacity
import playlistsyncfox.shared.result.Counts
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncResult
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressTest {
    @Test
    fun `TA-103 進捗が finished になると S4 へ進む`() {
        assertEquals(ProgressNav.ToResult, progressNav(SyncProgress(SyncStage.Finished), awaitingStart = false))
        assertEquals(ProgressNav.Stay, progressNav(SyncProgress(SyncStage.Transfer), awaitingStart = false))
    }

    @Test
    fun `TA-104 同期が動いていなければ S1 へ戻り、開始の直後の待ちの間は留まる`() {
        assertEquals(ProgressNav.ToHome, progressNav(null, awaitingStart = false))
        assertEquals(ProgressNav.Stay, progressNav(null, awaitingStart = true))
    }

    @Test
    fun `TA-104 開始の直後の待ちの間は最初の進捗で待ちを終え、finished が先に届いていれば S4 へ進む`() {
        assertEquals(ProgressNav.Started, progressNav(SyncProgress(SyncStage.Connecting), awaitingStart = true))
        assertEquals(ProgressNav.ToResult, progressNav(SyncProgress(SyncStage.Finished), awaitingStart = true))
    }

    @Test
    fun `TA-104 押した時点から残っている前の完了は、待ちの間に S4 へ進む判定に使わない`() {
        val stale = SyncProgress(SyncStage.Finished)

        assertEquals(ProgressNav.Stay, progressNav(stale, awaitingStart = true, staleFinished = stale))
        assertEquals(ProgressNav.ToResult, progressNav(SyncProgress(SyncStage.Finished), awaitingStart = true, staleFinished = stale))
        assertEquals(ProgressNav.Started, progressNav(SyncProgress(SyncStage.Connecting), awaitingStart = true, staleFinished = stale))
    }

    @Test
    fun `TA-105 near_limit が真のときだけ注記が出る`() {
        assertNotNull(progressUi(SyncProgress(SyncStage.Transfer, nearLimit = true)).nearLimitNote)
        assertNull(progressUi(SyncProgress(SyncStage.Transfer, nearLimit = false)).nearLimitNote)
    }

    @Test
    fun `TA-105 容量不足の不足分と E2 の案内はそのまま渡す`() {
        val finished = progressUi(SyncProgress(SyncStage.Finished, shortageBytes = 100, errorDetail = ErrorDetail.ClockSkew))

        assertEquals(100, finished.shortageBytes)
        assertEquals(noticeOf(ErrorDetail.ClockSkew).title, finished.notice?.title)
    }
}

private fun resultJson(
    removalSkipped: Boolean = false,
    detail: ErrorDetail? = null,
    manifestVersionMismatch: Boolean = false,
) = encodeResult(
    SyncResult(
        startedAt = "2026-11-07T00:00:00.000Z",
        finishedAt = "2026-11-07T00:01:00.000Z",
        trigger = SyncTrigger.Manual,
        transport = Transport.Lan,
        outcome = SyncOutcome.Completed,
        hasMore = false,
        counts = Counts(1, 0, 0, 0, 1, 0, 0, 0),
        capacity = Capacity(100, 100, null, null),
        errors = emptyList(),
        excluded = emptyList(),
        unplayable = emptyList(),
        libraryId = "LIB1",
        contentDigest = "d",
        unresolvedPlaylists = emptyList(),
        duplicatesDropped = 0,
        removalSkipped = removalSkipped,
        keyMismatch = null,
        errorDetail = detail,
        manifestVersionMismatch = manifestVersionMismatch,
    ),
)

private fun finishedRun(
    id: Long,
    trigger: String = SyncRun.TRIGGER_MANUAL,
    result: String = SyncRun.RESULT_SUCCESS,
    errorKind: String? = null,
    json: String? = resultJson(),
) = SyncRun(
    runId = id, deviceId = DEVICE, trigger = trigger, startedAt = id * 1000, endedAt = id * 1000 + 500, result = result,
    errorKind = errorKind, added = 1, bytesTransferred = 100, resultJson = json,
)

private fun issue(kind: String, n: Int, errorKind: String? = null) =
    RunIssue(runId = 1, title = "Song $n", artist = "Artist", reason = "protected", format = "m4p", kind = kind, errorKind = errorKind)

class TrackRowUiTest {
    private fun row(state: TrackState, received: Long = 0, errorNumber: Int? = null) =
        TrackRow("0000000000000001", "Song 1", 10L shl 20, state, received, errorNumber)

    @Test
    fun `TA-135 行の印と地の色と大きさの文字と理由は状態ごとに決まる`() {
        val done = trackRowUi(row(TrackState.Done))
        val transferring = trackRowUi(row(TrackState.Transferring, received = 5L shl 20))
        val waiting = trackRowUi(row(TrackState.Waiting))
        val failed = trackRowUi(row(TrackState.Failed, errorNumber = 9))

        assertEquals(listOf(TrackMark.Done, TrackMark.Transferring, TrackMark.Waiting, TrackMark.Failed), listOf(done, transferring, waiting, failed).map { it.mark })
        // 地の色が付くのは転送中だけ。薄い文字は完了だけ
        assertEquals(listOf(false, true, false, false), listOf(done, transferring, waiting, failed).map { it.highlighted })
        assertEquals(listOf(true, false, false, false), listOf(done, transferring, waiting, failed).map { it.dimmed })
        assertEquals("10.0 MiB", done.sizeText)
        assertEquals("5.0 MiB / 10.0 MiB", transferring.sizeText)
        assertEquals(0.5f, transferring.fraction)
        assertNull(waiting.fraction)
        assertEquals("端末への保存に失敗しました", failed.reason)
        assertNull(done.reason)
    }

    @Test
    fun `TA-135 全体の割合は total が 0 なら 0 で done が total を超えても 100 で切れる`() {
        assertEquals(0f, fraction(0, 0))
        assertEquals(0f, fraction(100, 0))
        assertEquals(0.53f, fraction(53, 100))
        assertEquals(1f, fraction(150, 100))
    }
}

class FormatBytesTest {
    @Test
    fun `1 KiB 未満は B、1 KiB から KiB、1 MiB から MiB で、小数 1 桁に丸める`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1023 B", formatBytes(1023))
        assertEquals("1.0 KiB", formatBytes(1024))
        assertEquals("1.0 MiB", formatBytes(1L shl 20))
        assertEquals("1.0 GiB", formatBytes(1L shl 30))
    }

    @Test
    fun `小数 1 桁に丸めて 1024 になる値は、上の単位で出す`() {
        assertEquals("1023.9 KiB", formatBytes(1048524))
        assertEquals("1.0 MiB", formatBytes(1048525))
        assertEquals("1.0 MiB", formatBytes((1L shl 20) - 1))
        assertEquals("1.0 GiB", formatBytes((1L shl 30) - 1))
    }
}

class ResultUiTest {
    @Test
    fun `TA-106 一覧は V21 件なら全件、1 件超えると V21 件と、ほか 1 件にする`() {
        val exact = resultUi(finishedRun(1), (1..ISSUE_LIMIT).map { issue(RunIssue.KIND_EXCLUDED, it) })
        val over = resultUi(finishedRun(1), (1..ISSUE_LIMIT + 1).map { issue(RunIssue.KIND_EXCLUDED, it) })

        assertEquals(ISSUE_LIMIT, exact.excluded.lines.size)
        assertEquals(0, exact.excluded.more)
        assertEquals(ISSUE_LIMIT, over.excluded.lines.size)
        assertEquals(1, over.excluded.more)
    }

    @Test
    fun `TA-125 除外は曲名とアーティストと理由、再生不可は曲名と形式と理由、失敗は曲名と E の番号を出す`() {
        val state = resultUi(
            finishedRun(1),
            listOf(issue(RunIssue.KIND_EXCLUDED, 1), issue(RunIssue.KIND_UNPLAYABLE, 2), issue(RunIssue.KIND_FAILED, 3, "E5")),
        )

        assertEquals(IssueLine("Song 1", "Artist / 著作権保護された曲"), state.excluded.lines.single())
        assertEquals(IssueLine("Song 2", "m4p / 著作権保護された曲"), state.unplayable.lines.single())
        assertEquals(IssueLine("Song 3", "E5"), state.failed.lines.single())
    }

    @Test
    fun `TA-107 一覧が空なら空のまま出る`() {
        val state = resultUi(finishedRun(1), emptyList())

        assertTrue(state.excluded.lines.isEmpty() && state.unplayable.lines.isEmpty() && state.failed.lines.isEmpty())
    }

    @Test
    fun `TA-107 消すを飛ばした案内は removal_skipped が真のときだけ出て、手動の文は 4 つの場合とも同じ`() {
        val skipped = resultUi(finishedRun(1, json = resultJson(removalSkipped = true)), emptyList())
        val kept = resultUi(finishedRun(1, json = resultJson(removalSkipped = false)), emptyList())
        val auto = resultUi(finishedRun(2, trigger = SyncRun.TRIGGER_AUTO, json = resultJson(removalSkipped = true)), emptyList())

        assertEquals("削除の確認が済んでいないため、削除しませんでした。もう一度同期すると確認が出ます", skipped.removalNote)
        assertNull(kept.removalNote)
        assertEquals("削除が必要な曲があります。手動で同期すると、確認のうえ削除します", auto.removalNote)
    }

    @Test
    fun `TA-251 マニフェストの版違いで止まった行だけ、版を合わせる案内を出す`() {
        val mismatch = resultUi(finishedRun(1, result = SyncRun.RESULT_FAILED, json = resultJson(manifestVersionMismatch = true)), emptyList())
        val other = resultUi(finishedRun(1, result = SyncRun.RESULT_FAILED, json = resultJson()), emptyList())

        assertEquals("PC と端末のアプリの版を合わせてください", mismatch.versionMismatchNote)
        assertNull(other.versionMismatchNote)
    }

    @Test
    fun `TA-107 強制終了の跡の行は件数と容量を出さず、結果の区分だけを出す`() {
        val trace = finishedRun(1, result = SyncRun.RESULT_INTERRUPTED, json = null)

        val state = resultUi(trace, emptyList())

        assertNull(state.counts)
        assertNull(state.bytes)
        assertEquals("中断", state.word)
    }

    @Test
    fun `TA-107 過去の実行を選ぶと、その run_id の T3 と T6 を読む`() = runTest {
        val store = InMemoryStore()
        val older = store.addRun(finishedRun(0).copy(startedAt = 1000, endedAt = 1500))
        val newer = store.addRun(finishedRun(0, result = SyncRun.RESULT_PARTIAL).copy(startedAt = 2000, endedAt = 2500))
        store.issueRows += issue(RunIssue.KIND_FAILED, 9, "E9").copy(runId = older.runId)

        val latest = loadResult(store, runId = null, syncing = false)
        val past = loadResult(store, runId = older.runId, syncing = false)

        assertEquals(newer.runId, latest.current?.runId)
        assertEquals(older.runId, past.current?.runId)
        assertEquals("Song 9", past.current?.failed?.lines?.single()?.title)
        assertEquals(2, past.past.size)
    }

    @Test
    fun `TA-131 E2 の表示語は result_json の error_detail で選ぶ`() {
        fun label(detail: ErrorDetail) = resultUi(finishedRun(1, result = SyncRun.RESULT_FAILED, errorKind = "E2", json = resultJson(detail = detail)), emptyList()).noticeLabel

        assertEquals("時計のずれ", label(ErrorDetail.ClockSkew))
        assertEquals("証明書の変更", label(ErrorDetail.PinMismatch))
        assertEquals("一時的な拒否", label(ErrorDetail.RateLimited))
        assertEquals("署名の不一致", label(ErrorDetail.NoDetail))
    }

    @Test
    fun `X7-2 E2 の原因欄も error_detail ごとの語で出し、時計のずれを再ペアリングの案内にしない`() {
        fun cause(detail: ErrorDetail?) =
            resultUi(finishedRun(1, result = SyncRun.RESULT_FAILED, errorKind = "E2", json = resultJson(detail = detail)), emptyList()).errorWord

        ErrorDetail.entries.forEach { assertEquals(noticeOf(it).title, cause(it), it.name) }
        // error_detail を持たない行は E の番号の語
        assertEquals("ペアリングのやり直しが必要です", cause(null))
    }

    @Test
    fun `TA-131 E2 でない行には E2 の表示語を出さない`() {
        val state = resultUi(finishedRun(1, json = resultJson(detail = ErrorDetail.ClockSkew)), emptyList())

        assertFalse(state.noticeLabel != null)
    }
}
