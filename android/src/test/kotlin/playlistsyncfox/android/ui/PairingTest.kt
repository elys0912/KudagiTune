package playlistsyncfox.android.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.discovery.DiscoveredPc
import playlistsyncfox.android.discovery.checkManualEndpoint
import playlistsyncfox.android.pairing
import playlistsyncfox.shared.result.ErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingPresenterTest {
    private val pc = DiscoveredPc("psfox-01234567", "192.0.2.10", 47800, 1, "01234567", pairingOpen = true)

    private class Gateway(val events: suspend (kotlinx.coroutines.flow.FlowCollector<PairingEvent>) -> Unit) : PairingGateway {
        var cancelled = false
        var calls = 0

        override fun pair(target: PairingTarget, code: String): Flow<PairingEvent> = flow {
            calls++
            try {
                events(this)
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
    }

    private fun TestScope.presenter(store: InMemoryStore, gateway: PairingGateway, discover: () -> Flow<List<DiscoveredPc>> = { emptyFlow() }) =
        PairingPresenter(backgroundScope, store, gateway, discover, ::checkManualEndpoint)

    private fun TestScope.targeted(store: InMemoryStore, gateway: PairingGateway): PairingPresenter =
        presenter(store, gateway).also { it.select(pc) }

    @Test
    fun `TA-102 P5 の応答の時点で指紋を表示するだけで、保存の前は T4 に何も無い`() = runTest {
        val store = InMemoryStore()
        val gateway = Gateway { it.emit(PairingEvent.Fingerprint("0a1b 2c3d")); awaitCancellation() }
        val presenter = targeted(store, gateway)

        presenter.submitCode("123456")
        runCurrent()

        val stage = assertIs<PairingStage.Target>(presenter.state.value.stage)
        assertEquals("0a1b 2c3d", stage.fingerprint)
        assertNull(store.pairingRow)
    }

    @Test
    fun `TA-102 P6 の成功の前に画面を離れると、接続を切って T4 に何も残らない`() = runTest {
        val store = InMemoryStore()
        val gateway = Gateway { it.emit(PairingEvent.Fingerprint("0a1b")); awaitCancellation() }
        val presenter = targeted(store, gateway)
        presenter.submitCode("123456")
        runCurrent()

        presenter.close()
        runCurrent()

        assertTrue(gateway.cancelled)
        assertNull(store.pairingRow)
    }

    @Test
    fun `TA-102 P6 が失敗すると失敗の段になり、T4 に何も残らない`() = runTest {
        val store = InMemoryStore()
        val gateway = Gateway { it.emit(PairingEvent.Failed(noticeOf(ErrorDetail.ClockSkew))) }
        val presenter = targeted(store, gateway)

        presenter.submitCode("123456")
        runCurrent()

        val stage = assertIs<PairingStage.Failed>(presenter.state.value.stage)
        assertEquals("端末と PC の時計が違う", stage.notice.title)
        assertNull(store.pairingRow)
    }

    @Test
    fun `TA-102 P6 の成功の後に画面を離れても、保存は取り消さない`() = runTest {
        val store = InMemoryStore()
        val gateway = Gateway {
            store.savePairing(pairing())
            it.emit(PairingEvent.Completed(DEVICE, "pc.example.invalid"))
        }
        val presenter = targeted(store, gateway)
        presenter.submitCode("123456")
        runCurrent()

        presenter.close()
        runCurrent()

        assertIs<PairingStage.Done>(presenter.state.value.stage)
        assertNotNull(store.pairingRow)
    }

    @Test
    fun `TA-127 完了の段は T1 と T2 がともに空のときだけ以前の曲の整理の案内を出す`() = runTest {
        val empty = InMemoryStore()
        val emptyPresenter = targeted(empty, Gateway { it.emit(PairingEvent.Completed(DEVICE, "pc")) })
        emptyPresenter.submitCode("123456")
        runCurrent()

        val withTrack = InMemoryStore().also {
            it.placed["A"] = PlacedTrack(DEVICE, "A", "a/b.mp3", "content://x", 1, 1, 1, PlacedTrack.STATE_PLACED, 0)
        }
        val trackPresenter = targeted(withTrack, Gateway { it.emit(PairingEvent.Completed(DEVICE, "pc")) })
        trackPresenter.submitCode("123456")
        runCurrent()

        val withPlaylist = InMemoryStore().also { it.playlistRows["P"] = WrittenPlaylist(DEVICE, "P", "Mix", "Mix.m3u8", "content://y", 1, 0) }
        val playlistPresenter = targeted(withPlaylist, Gateway { it.emit(PairingEvent.Completed(DEVICE, "pc")) })
        playlistPresenter.submitCode("123456")
        runCurrent()

        assertTrue(assertIs<PairingStage.Done>(emptyPresenter.state.value.stage).showCleanupHint)
        assertFalse(assertIs<PairingStage.Done>(trackPresenter.state.value.stage).showCleanupHint)
        assertFalse(assertIs<PairingStage.Done>(playlistPresenter.state.value.stage).showCleanupHint)
    }

    @Test
    fun `TA-121 試行の上限と待ちと入れ直しの案内は、それぞれ専用の段になる`() = runTest {
        fun stageFor(event: PairingEvent): PairingStage {
            val presenter = targeted(InMemoryStore(), Gateway { it.emit(event) })
            presenter.submitCode("123456")
            runCurrent()
            return presenter.state.value.stage
        }

        assertEquals(PairingStage.AttemptsExhausted, stageFor(PairingEvent.AttemptsExhausted))
        assertEquals(PairingStage.Waiting, stageFor(PairingEvent.Waiting))
        assertEquals(PairingStage.DeviceExists, stageFor(PairingEvent.DeviceExists))
        assertEquals(PairingStage.Unavailable, stageFor(PairingEvent.Unavailable))
    }

    @Test
    fun `TA-121 コードの不一致が V2 に達すると試行の上限の段になる`() = runTest {
        val presenter = targeted(InMemoryStore(), Gateway { it.emit(PairingEvent.CodeMismatch) })

        repeat(CODE_ATTEMPTS - 1) {
            presenter.submitCode("123456")
            runCurrent()
            assertEquals("コードが違う", assertIs<PairingStage.Target>(presenter.state.value.stage).codeError)
        }
        presenter.submitCode("123456")
        runCurrent()

        assertEquals(PairingStage.AttemptsExhausted, presenter.state.value.stage)
    }

    @Test
    fun `TA-102 桁数か数字でないコードは送らずに入力欄の下へ理由を出す`() = runTest {
        val gateway = Gateway { it.emit(PairingEvent.Completed(DEVICE, "pc")) }
        val presenter = targeted(InMemoryStore(), gateway)

        presenter.submitCode("12345")
        presenter.submitCode("12345a")
        runCurrent()

        assertEquals(0, gateway.calls)
        assertNotNull(assertIs<PairingStage.Target>(presenter.state.value.stage).codeError)
    }

    @Test
    fun `TA-109 手入力が検査を通らなければ理由を出し、通れば宛先の確定に進む`() = runTest {
        val presenter = presenter(InMemoryStore(), Gateway { })

        presenter.submitManual("https://example.invalid")
        assertEquals("使えない文字がある", presenter.state.value.manualError)
        assertEquals(PairingStage.Discovering, presenter.state.value.stage)

        presenter.submitManual("192.0.2.10")
        val target = assertIs<PairingStage.Target>(presenter.state.value.stage).target
        assertEquals("usb", target.kind)
        assertEquals("192.0.2.10:47800", target.savedValue)
    }

    @Test
    fun `TA-112 発見の待ち V13 が過ぎても 1 台も無ければ見つからない表示になる`() = runTest {
        val presenter = presenter(InMemoryStore(), Gateway { }, discover = { flow { emit(emptyList()); awaitCancellation() } })

        presenter.open()
        advanceTimeBy(DISCOVERY_WAIT_MS - 1)
        assertFalse(presenter.state.value.notFound)
        advanceTimeBy(2)

        assertTrue(presenter.state.value.notFound)
    }
}

class NoticeTest {
    @Test
    fun `TA-121 E2 の detail ごとに表示と再ペアリングの誘導が表のとおりになる`() {
        assertEquals("端末と PC の時計が違う", noticeOf(ErrorDetail.ClockSkew).title)
        assertEquals("通信が重複した", noticeOf(ErrorDetail.Replay).title)
        assertEquals("要求が続いたため PC が一時的に断った", noticeOf(ErrorDetail.RateLimited).title)
        assertEquals("証明書が変わった", noticeOf(ErrorDetail.PinMismatch).title)
        assertEquals("ペアリングをやり直してください", noticeOf(ErrorDetail.NoDetail).title)
        assertEquals("PC がコードを表示していない", PairingClosedNotice.title)
    }

    @Test
    fun `TA-121 再ペアリングへ誘導するのは証明書の不一致と detail なしの 401 だけで、rate_limited は誘導しない`() {
        assertTrue(noticeOf(ErrorDetail.PinMismatch).rePair)
        assertTrue(noticeOf(ErrorDetail.NoDetail).rePair)
        assertFalse(noticeOf(ErrorDetail.RateLimited).rePair)
        assertFalse(noticeOf(ErrorDetail.ClockSkew).rePair)
        assertFalse(noticeOf(ErrorDetail.Replay).rePair)
        assertTrue(noticeOf(ErrorDetail.RateLimited).guidance.contains("再ペアリングは要らない"))
    }
}
