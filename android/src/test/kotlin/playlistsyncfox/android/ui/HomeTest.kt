package playlistsyncfox.android.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.pairing
import playlistsyncfox.android.sync.ManualStart
import playlistsyncfox.android.sync.RemovalCheck
import playlistsyncfox.android.sync.StartBlock
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private fun run(
    id: Long,
    startedAt: Long,
    endedAt: Long? = startedAt + 1000,
    result: String = SyncRun.RESULT_SUCCESS,
) = SyncRun(runId = id, deviceId = DEVICE, trigger = SyncRun.TRIGGER_MANUAL, startedAt = startedAt, endedAt = endedAt, result = result)

private val playlist = WrittenPlaylist(DEVICE, "P1", "Mix", "Mix.m3u8", "content://x", trackCount = 12, writtenAt = 0)

class HomeStateTest {
    @Test
    fun `TA-101 T4 が無ければ未ペアリングで、同期ボタンは S2 へ進む`() {
        val state = homeState(null, runs = listOf(run(1, 100)), playlists = emptyList(), selectedCount = 0, syncing = false)

        assertEquals(HomeKind.Unpaired, state.kind)
        assertTrue(state.syncButtonGoesToPairing)
    }

    @Test
    fun `TA-101 T4 だけ有れば未実行で、T4 と T3 が有れば実行済みになる`() {
        val notRun = homeState(pairing(), emptyList(), emptyList(), selectedCount = 0, syncing = false)
        val done = homeState(pairing(), listOf(run(1, 100)), listOf(playlist), selectedCount = 1, syncing = false)

        assertEquals(HomeKind.NotRun, notRun.kind)
        assertNull(notRun.last)
        assertFalse(notRun.syncButtonGoesToPairing)
        assertEquals(HomeKind.Done, done.kind)
        assertEquals("完了", done.last?.word)
        assertEquals(listOf(PlaylistRow("Mix", 12)), done.playlists)
    }

    @Test
    fun `TA-250 同期ボタンで同期が始まるのは、ペアリング済みで選択があるときだけ`() {
        val unpaired = homeState(null, emptyList(), emptyList(), selectedCount = 1, syncing = false)
        val noSelection = homeState(pairing(), emptyList(), emptyList(), selectedCount = 0, syncing = false)
        val selected = homeState(pairing(), emptyList(), emptyList(), selectedCount = 1, syncing = false)

        assertFalse(unpaired.startsSync)
        assertFalse(noSelection.startsSync)
        assertTrue(selected.startsSync)
    }

    @Test
    fun `TA-124 接続先は last_endpoint が指す方式の値で、空なら未接続と出す`() {
        val usb = pairing(lastEndpoint = "usb", usb = "192.0.2.20:47800")

        assertEquals("192.0.2.20:47800", homeState(usb, emptyList(), emptyList(), 0, false).endpoint)
        assertEquals("pc.example.invalid:47800", homeState(pairing(lastEndpoint = "tailscale"), emptyList(), emptyList(), 0, false).endpoint)
        assertEquals("未接続", homeState(pairing(lastEndpoint = null), emptyList(), emptyList(), 0, false).endpoint)
    }

    @Test
    fun `TA-124 同期が動いていれば同期中になり、finished を過ぎた状態は動いていない`() {
        assertTrue(isSyncing(SyncProgress(SyncStage.Transfer)))
        assertFalse(isSyncing(SyncProgress(SyncStage.Finished)))
        assertFalse(isSyncing(null))
        assertEquals(HomeKind.Syncing, homeState(pairing(), emptyList(), emptyList(), selectedCount = 0, syncing = true).kind)
    }

    @Test
    fun `TA-130 同期中は ended_at が NULL の行を出さず、その前の success の行を最新として出す`() {
        val runs = listOf(run(2, 2000, endedAt = null, result = SyncRun.RESULT_FAILED), run(1, 1000))

        val last = latestRun(runs, syncing = true)!!

        assertEquals(1, last.run.runId)
        assertEquals("完了", last.word)
        assertFalse(last.interruptedTrace)
    }

    @Test
    fun `TA-130 同期中でなければ ended_at が NULL の行を中断として started_at の日時で出す`() {
        val runs = listOf(run(2, 2000, endedAt = null, result = SyncRun.RESULT_FAILED), run(1, 1000))

        val last = latestRun(runs, syncing = false)!!

        assertEquals(2, last.run.runId)
        assertEquals("中断", last.word)
        assertEquals(2000, last.atMillis)
        assertTrue(last.interruptedTrace)
    }

    @Test
    fun `TA-130 前の行が無く NULL の行だけで同期中なら、行を出さずまだ同期していませんにする`() {
        val runs = listOf(run(1, 1000, endedAt = null))

        assertNull(latestRun(runs, syncing = true))
        assertNull(homeState(pairing(), runs, emptyList(), selectedCount = 0, syncing = true).last)
    }
}

class ManualSyncFlowTest {
    private class Calls {
        var autoRunning = false
        var block: StartBlock? = null
        var check: RemovalCheck = RemovalCheck.Allowed
        var startResult = ManualStart.Started
        var checks = 0
        val starts = mutableListOf<Pair<Int?, Boolean>>()
    }

    private fun flowOf(calls: Calls) = ManualSyncFlow(
        autoRunning = { calls.autoRunning },
        block = { calls.block },
        check = {
            calls.checks++
            calls.check
        },
        start = { count, reach ->
            calls.starts += count to reach
            calls.startResult
        },
    )

    @Test
    fun `TA-101 未ペアリングで押すと S2 へ進み、同期は始めない`() = runTest {
        val calls = Calls()

        assertEquals(PressOutcome.ToPairing, flowOf(calls).press(paired = false))
        assertTrue(calls.starts.isEmpty())
    }

    @Test
    fun `TA-122 Allowed は確認を出さずにワーカーを起動する`() = runTest {
        val calls = Calls()

        assertEquals(PressOutcome.ToProgress(), flowOf(calls).press(paired = true))
        assertEquals(listOf<Pair<Int?, Boolean>>(null to false), calls.starts)
    }

    @Test
    fun `TA-122 確認が要るときは件数つきで確認を出し、承認で件数を添えて起動する`() = runTest {
        val calls = Calls().also { it.check = RemovalCheck.NeedsConfirmation(25) }
        val flow = flowOf(calls)

        val confirm = assertIs<PressOutcome.Confirm>(flow.press(paired = true))
        assertEquals(25, confirm.toRemove)
        assertTrue(calls.starts.isEmpty())

        flow.answer(confirm, approved = true)
        assertEquals(listOf<Pair<Int?, Boolean>>(25 to false), calls.starts)
    }

    @Test
    fun `TA-122 確認を取り消すと消すだけ飛ばして同期を始める`() = runTest {
        val calls = Calls().also { it.check = RemovalCheck.NeedsConfirmation(25) }
        val flow = flowOf(calls)
        val confirm = assertIs<PressOutcome.Confirm>(flow.press(paired = true))

        assertEquals(PressOutcome.ToProgress(), flow.answer(confirm, approved = false))
        assertEquals(listOf<Pair<Int?, Boolean>>(null to false), calls.starts)
    }

    @Test
    fun `TA-122 P1 が届かない失敗は reach_failed を添え、ほかの失敗は添えずに確認を飛ばして起動する`() = runTest {
        val unreachable = Calls().also { it.check = RemovalCheck.Failed(reachFailed = true) }
        flowOf(unreachable).press(paired = true)
        assertEquals(listOf<Pair<Int?, Boolean>>(null to true), unreachable.starts)

        val other = Calls().also { it.check = RemovalCheck.Failed(reachFailed = false) }
        flowOf(other).press(paired = true)
        assertEquals(listOf<Pair<Int?, Boolean>>(null to false), other.starts)
    }

    @Test
    fun `TA-126 自動が実行中なら確認も起動もせずに S3 へ入る`() = runTest {
        val calls = Calls().also {
            it.autoRunning = true
            it.check = RemovalCheck.NeedsConfirmation(25)
        }

        assertEquals(PressOutcome.ToProgress(), flowOf(calls).press(paired = true))
        assertEquals(0, calls.checks)
        assertTrue(calls.starts.isEmpty())
    }

    @Test
    fun `TA-133 確認の間に自動が始まったときは承認を使わず、S3 に案内を出す`() = runTest {
        val calls = Calls().also { it.check = RemovalCheck.NeedsConfirmation(25) }
        val flow = flowOf(calls)
        val confirm = assertIs<PressOutcome.Confirm>(flow.press(paired = true))
        calls.startResult = ManualStart.AutoRunning

        assertEquals(PressOutcome.ToProgress(APPROVAL_UNUSED_NOTICE), flow.answer(confirm, approved = true))
    }

    @Test
    fun `TA-222 選択が空なら同期を始めず S6 へ案内し、到達確認もしない`() = runTest {
        val calls = Calls().also { it.block = StartBlock.NoSelection }

        assertEquals(PressOutcome.ToSelection, flowOf(calls).press(paired = true))
        assertEquals(0, calls.checks)
        assertTrue(calls.starts.isEmpty())
    }

    @Test
    fun `TA-222 保存先が無いときは同期を始めず案内する`() = runTest {
        val calls = Calls().also { it.block = StartBlock.StorageMissing }

        assertEquals(PressOutcome.StorageMissing, flowOf(calls).press(paired = true))
        assertTrue(calls.starts.isEmpty())
    }

    @Test
    fun `TA-222 未ペアリングは選択が空でも S2 が先`() = runTest {
        val calls = Calls().also { it.block = StartBlock.NoSelection }

        assertEquals(PressOutcome.ToPairing, flowOf(calls).press(paired = false))
    }
}

class StartManualSyncTest {
    private val finished = SyncProgress(SyncStage.Finished)
    private val running = SyncProgress(SyncStage.Transfer)

    @Test
    fun `新しく始めたときは、前回の終了の進捗を消す`() = runTest {
        val progress = MutableStateFlow<SyncProgress?>(finished)

        val result = startManualSync(progress) { ManualStart.Started }

        assertEquals(ManualStart.Started, result)
        assertNull(progress.value)
    }

    @Test
    fun `TA-133 自動が実行中で新しい仕事を作らなかったときは、走っているワーカーの進捗を消さない`() = runTest {
        val progress = MutableStateFlow<SyncProgress?>(running)

        val result = startManualSync(progress) { ManualStart.AutoRunning }

        assertEquals(ManualStart.AutoRunning, result)
        assertEquals(running, progress.value)
    }

    @Test
    fun `手動が実行中で新しい仕事を作らなかったときも、走っているワーカーの進捗を消さない`() = runTest {
        val progress = MutableStateFlow<SyncProgress?>(running)

        val result = startManualSync(progress) { ManualStart.AlreadyRunning }

        assertEquals(ManualStart.AlreadyRunning, result)
        assertEquals(running, progress.value)
    }

    @Test
    fun `新しいワーカーが先に進捗を出していたら、その進捗は消さない`() = runTest {
        val progress = MutableStateFlow<SyncProgress?>(finished)

        startManualSync(progress) {
            progress.value = running
            ManualStart.Started
        }

        assertEquals(running, progress.value)
    }
}

class StaleFinishedTest {
    private val finished = SyncProgress(SyncStage.Finished)

    @Test
    fun `TA-104 押した時点の完了が同じまま残っているときは、その完了を返す`() {
        assertSame(finished, staleFinished(atPress = finished, now = finished))
    }

    @Test
    fun `TA-104 押した後に進捗が消えたか入れ替わったときは、前の完了として扱わない`() {
        assertNull(staleFinished(atPress = finished, now = null))
        assertNull(staleFinished(atPress = finished, now = SyncProgress(SyncStage.Connecting)))
    }

    @Test
    fun `TA-104 内容が同じでも別の完了は、新しい実行の完了として扱う`() {
        assertNull(staleFinished(atPress = finished, now = SyncProgress(SyncStage.Finished)))
    }

    @Test
    fun `TA-104 押した時点で完了でなければ、何も無視しない`() {
        val running = SyncProgress(SyncStage.Transfer)

        assertNull(staleFinished(atPress = running, now = running))
        assertNull(staleFinished(atPress = null, now = null))
    }
}
