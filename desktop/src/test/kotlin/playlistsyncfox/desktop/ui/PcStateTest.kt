package playlistsyncfox.desktop.ui

import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.emptyLibrary
import playlistsyncfox.desktop.server.ActivityTracker
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.desktop.settings.Settings
import playlistsyncfox.desktop.watch.XmlStatus
import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val DEVICE = "00000000-0000-4000-8000-000000000001"

private fun settings(devices: List<PairedDevice> = listOf(PairedDevice(DEVICE, "端末", "2026-01-01T00:00:00.000Z")), selected: List<String> = listOf("A")) =
    Settings(1, null, selected, 7, true, 47800, "pc", null, devices)

class PcStateTest {
    private val clock = MutableClock(T0)
    private val activity = ActivityTracker(clock)

    private fun state(
        server: ServerStatus = ServerStatus.Running,
        xml: XmlStatus = XmlStatus.Normal,
        settings: Settings = settings(),
        keyMissing: Set<String> = emptySet(),
        library: LibraryReadResult? = emptyLibrary(),
    ) = buildPcState(
        PcInputs(server, xml, library, settings, keyMissing, PairingView.Closed, activity, false, false, emptyList(), null),
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
    fun `TD-35 警告の印は warnings が 1 件以上で付く`() {
        assertFalse(hasWarningMark(state()))
        assertTrue(hasWarningMark(state(settings = settings(selected = emptyList()))))
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

    @Test
    fun `TD-77 鍵が読めない端末は W3 に鍵なしの印と警告が出る`() {
        val s = state(keyMissing = setOf(DEVICE))

        assertTrue(s.device!!.keyMissing)
        assertTrue(Warning.KeyMissing in s.warnings)
    }

    // ---- W2 の選択（TD-74） ----

    private fun playlist(id: String, parent: String? = null, kind: PlaylistKind = PlaylistKind.Normal) =
        LibraryPlaylist(id, "名前$id", parent, kind, listOf(TrackRef.Identified("AAAAAAAAAAAAAAAA")))

    private val folder = PlaylistKind.Excluded(PlaylistExclusion.Folder)

    private fun tree() = buildPlaylistTree(
        listOf(
            playlist("F1", kind = folder),
            playlist("L1", parent = "F1"),
            playlist("L2", parent = "F1"),
            playlist("L3"),
            playlist("MASTER", kind = PlaylistKind.Excluded(PlaylistExclusion.Master)),
        ),
    )

    @Test
    fun `TD-74 フォルダのチェックは配下の葉を一括で選び、フォルダ自身は保存しない`() {
        val f1 = tree().first { it.id == "F1" }

        val selected = withFolderChecked(listOf("L3"), f1, checked = true)

        assertEquals(listOf("L3", "L1", "L2"), selected)
        assertFalse("F1" in selected)
    }

    @Test
    fun `TD-74 フォルダのチェックを外すと配下の葉だけが外れる`() {
        val f1 = tree().first { it.id == "F1" }

        assertEquals(listOf("L3"), withFolderChecked(listOf("L1", "L2", "L3"), f1, checked = false))
    }

    @Test
    fun `TD-74 組み込みの一覧は木に出さない`() {
        assertEquals(listOf("F1", "L3"), tree().map { it.id })
    }

    @Test
    fun `TD-74 保存した ID が XML に無くても自動では消えず、外す操作で除かれる`() {
        val library = LibraryReadResult(null, emptyList(), emptyList(), listOf(playlist("L1")), emptyLibrary().diagnostics)
        val s = state(settings = settings(selected = listOf("L1", "GONE")), library = library)

        assertEquals(listOf("L1", "GONE"), s.selectedIds)
        assertEquals(listOf("GONE"), s.missingIds)
        assertEquals(listOf("L1"), withLeafChecked(s.selectedIds, "GONE", checked = false))
    }
}
