package playlistsyncfox.desktop.ui

import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.server.ActivityTracker
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.server.ProgressBoard
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.desktop.settings.Settings
import playlistsyncfox.desktop.watch.XmlStatus
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEVICE = "00000000-0000-4000-8000-000000000001"

private fun settings(devices: List<PairedDevice> = listOf(PairedDevice(DEVICE, "端末", "2026-01-01T00:00:00.000Z"))) =
    Settings(1, null, 7, true, 47800, "pc", null, devices)

class PcStateTest {
    private val clock = MutableClock(T0)
    private val activity = ActivityTracker(clock)

    private fun state(
        server: ServerStatus = ServerStatus.Running,
        xml: XmlStatus = XmlStatus.Normal,
        settings: Settings = settings(),
        keyMissing: Set<String> = emptySet(),
    ) = buildPcState(
        PcInputs(server, xml, settings, keyMissing, PairingView.Closed, activity, ProgressBoard(clock), false, false, emptyList(), null),
        clock.instant(),
    )

    @Test
    fun `TD-35 鍵の保管に失敗が未ペアより上に選ばれる`() {
        val s = state(server = ServerStatus.Failed(ServerFailureKind.KeyStorage), settings = settings(devices = emptyList()))

        assertEquals(TrayStatus.KeyStorageFailed, trayStatus(s))
    }

    @Test
    fun `TD-35 未ペアと XML が読めないが重なると未ペアが選ばれる`() {
        val s = state(xml = XmlStatus.Unreadable(true), settings = settings(devices = emptyList()))

        assertEquals(TrayStatus.NotPaired, trayStatus(s))
    }

    @Test
    fun `TD-35 XML が読めないと同期中が重なっても XML が読めないが選ばれる`() {
        activity.trackRequestStarted()

        assertEquals(TrayStatus.XmlUnreadable, trayStatus(state(xml = XmlStatus.Unreadable(false))))
    }

    @Test
    fun `TD-35 ポートの使用中も待機中と表示せず、未ペアより上、鍵の保管の失敗より下に選ばれる`() {
        val noDevice = settings(devices = emptyList())

        assertEquals(TrayStatus.ServerFailed, trayStatus(state(server = ServerStatus.Failed(ServerFailureKind.PortInUse))))
        assertEquals(TrayStatus.ServerFailed, trayStatus(state(server = ServerStatus.Failed(ServerFailureKind.PortInUse), settings = noDevice)))
        assertEquals(TrayStatus.KeyStorageFailed, trayStatus(state(server = ServerStatus.Failed(ServerFailureKind.KeyStorage), settings = noDevice)))
    }

    @Test
    fun `TD-35 警告の印は warnings が 1 件以上で付く`() {
        assertFalse(hasWarningMark(state()))
        assertTrue(hasWarningMark(state(xml = XmlStatus.Stale(8))))
        assertTrue(hasWarningMark(state(keyMissing = setOf(DEVICE))))
    }

    @Test
    fun `TD-36 署名つき要求の最後から V33 ちょうどは接続中、1 秒超は接続中でない`() {
        activity.signedRequest()

        clock.advance(Duration.ofSeconds(60))
        assertTrue(state().device!!.connected)

        clock.advance(Duration.ofSeconds(1))
        assertFalse(state().device!!.connected)
    }

    @Test
    fun `TD-57 P2 の最後の要求から V33 ちょうどは同期中、1 秒超は同期中でない`() {
        activity.trackRequestStarted()
        activity.trackRequestFinished()

        clock.advance(Duration.ofSeconds(60))
        assertEquals(TrayStatus.Syncing, trayStatus(state()))

        clock.advance(Duration.ofSeconds(1))
        assertEquals(TrayStatus.Idle, trayStatus(state()))
    }

    @Test
    fun `TD-58 署名つき要求だけでは同期中にならない`() {
        activity.signedRequest()

        assertEquals(TrayStatus.Idle, trayStatus(state()))
    }

    @Test
    fun `TD-58 P2 の送信中は最後の要求が V33 を超えても同期中`() {
        activity.trackRequestStarted()
        clock.advance(Duration.ofSeconds(600))

        assertEquals(TrayStatus.Syncing, trayStatus(state()))
    }
}
