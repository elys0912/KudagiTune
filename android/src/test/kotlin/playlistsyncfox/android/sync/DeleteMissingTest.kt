package playlistsyncfox.android.sync

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.Harness
import playlistsyncfox.android.ROOT
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.manifestOf
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.track
import playlistsyncfox.android.ui.resultUi
import playlistsyncfox.shared.manifest.ManifestTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 端末に 1..[placedCount] 曲を置いた状態で、PC のマニフェストは 1..[keep] 曲だけにする。 */
private fun Harness.setup(placedCount: Int, keep: Int): List<ManifestTrack> {
    val all = (1..placedCount).map { track(it) }
    for (t in all) {
        val uri = media.addExisting("${ROOT}Artist/Album/", t.fileName, bytes = t.size.toInt())
        store.placed[t.persistentId] = PlacedTrack(DEVICE, t.persistentId, "Artist/Album/${t.fileName}", uri, t.size, t.modifiedAt, t.size, PlacedTrack.STATE_PLACED, 0)
    }
    val kept = all.take(keep)
    setManifest(manifestOf(kept))
    serveTracks(kept)
    return all
}

class DeleteMissingTest {
    @Test
    fun `TA-230 OFF のときは toRemove があっても消さず、件数が結果に残る`() = runTest {
        val h = Harness()
        h.deleteMissing = false
        h.setup(placedCount = 3, keep = 2)

        val row = assertNotNull(h.run())

        assertEquals(3, h.store.placed.size)
        assertEquals(0, row.removed)
        assertEquals(1, decodeResult(row.resultJson!!).counts.toRemove)
        assertEquals(1, resultUi(row, emptyList()).notRemoved)
    }

    @Test
    fun `TA-230 OFF のときは消す曲が多くても確認を出さない`() = runTest {
        val h = Harness()
        h.deleteMissing = false
        h.setup(placedCount = 30, keep = 2)

        assertEquals(RemovalCheck.Allowed, SyncPreview(h.environment()).check())
    }

    @Test
    fun `TA-231 ON のときは手動の確認を経て消え、確認を断れば消えない`() = runTest {
        val approved = Harness()
        approved.setup(placedCount = 30, keep = 2)
        assertEquals(RemovalCheck.NeedsConfirmation(28), SyncPreview(approved.environment()).check())
        approved.run(deleteApproved = true, approved = 28)
        assertEquals(2, approved.store.placed.size)

        val declined = Harness()
        declined.setup(placedCount = 30, keep = 2)
        val row = assertNotNull(declined.run())
        assertEquals(30, declined.store.placed.size)
        assertEquals(28, resultUi(row, emptyList()).notRemoved)
    }

    @Test
    fun `TA-243 画面側の確認も、つながった接続先を last_endpoint に記録し NSD の名が変わっていれば endpoint_lan も更新する`() = runTest {
        val viaTailscale = Harness()
        val kept = viaTailscale.setup(placedCount = 3, keep = 2).take(2)
        viaTailscale.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        viaTailscale.setManifest(manifestOf(kept), viaTailscale.tailApi)
        SyncPreview(viaTailscale.environment()).check()
        assertEquals("tailscale", viaTailscale.store.pairingRow?.lastEndpoint)

        val renamed = Harness()
        renamed.setup(placedCount = 3, keep = 2)
        renamed.lanServices = listOf(LanService("psfox-01234567-2", Harness.LAN_HOST, 47800, "01234567"))
        SyncPreview(renamed.environment()).check()
        assertEquals("lan", renamed.store.pairingRow?.lastEndpoint)
        assertEquals("psfox-01234567-2", renamed.store.pairingRow?.endpointLan)
    }

    @Test
    fun `TA-231 ON のとき手動は 1 件だけ消す場合も確認を出し、断れば消えない`() = runTest {
        val h = Harness()
        h.setup(placedCount = 3, keep = 2)
        assertEquals(RemovalCheck.NeedsConfirmation(1), SyncPreview(h.environment()).check())

        h.run()
        assertEquals(3, h.store.placed.size)
    }
}
