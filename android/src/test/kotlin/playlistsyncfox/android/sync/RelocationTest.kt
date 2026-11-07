package playlistsyncfox.android.sync

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.Harness
import playlistsyncfox.android.ROOT
import playlistsyncfox.android.SELECTED_ID
import playlistsyncfox.android.TrackScript
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.SelectedPlaylist
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.id
import playlistsyncfox.android.manifestOf
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.track
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.result.SyncTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SD = "1234-ABCD"

private fun Harness.sync(tracks: List<ManifestTrack>) {
    setManifest(manifestOf(tracks))
    serveTracks(tracks)
}

/** 切り替え前の場所に置いてある曲を作る。FakeMediaStore は 1 つの集合なので、URI で区別する。 */
private fun Harness.seedOldPlaced(tracks: List<ManifestTrack>): List<String> = tracks.map { t ->
    val uri = media.addExisting("${ROOT}Artist/Album/", t.fileName, bytes = t.size.toInt())
    store.placed[t.persistentId] = PlacedTrack(DEVICE, t.persistentId, "Artist/Album/${t.fileName}", uri, t.size, t.modifiedAt, t.size, PlacedTrack.STATE_PLACED, 0)
    uri
}

class RelocationTest {
    private val tracks = (1..3).map { track(it) }

    @Test
    fun `TA-216 選択が空なら手動も自動も接続せず T3 に残さず終わる`() = runTest {
        for (trigger in SyncTrigger.entries) {
            val h = Harness(trigger)
            h.sync(tracks)
            h.store.selected = emptyList()

            assertNull(h.run())
            assertEquals(0, h.lanApi.digestCalls)
            assertTrue(h.runs.isEmpty())
        }
    }

    @Test
    fun `TA-216 選んだ保存先が無ければ接続せず T3 に残さず、保存先は本体に替わらない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.volume = SD
        h.volumeAvailable = false
        h.store.settings[SyncValues.STORAGE_VOLUME_KEY] = SD

        assertNull(h.run())

        assertEquals(0, h.lanApi.digestCalls)
        assertTrue(h.runs.isEmpty())
        assertEquals(SD, h.store.settings[SyncValues.STORAGE_VOLUME_KEY])
    }

    @Test
    fun `TA-217 P3 と P1 の本文に T8 の選択をそのまま渡す`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.store.selected = listOf(SelectedPlaylist(SELECTED_ID, "a", 1), SelectedPlaylist(id(77), "b", 2))

        h.run()

        assertEquals(listOf(SELECTED_ID, id(77)), h.lanApi.digestIds.first())
        assertEquals(listOf(SELECTED_ID, id(77)), h.lanApi.manifestIds.first())
    }

    @Test
    fun `TA-218 保存先を替えると全曲を取り直し、完了のあとに古い場所のファイルが消える`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val old = h.seedOldPlaced(tracks)
        h.volume = SD

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals(3, row.added)
        assertEquals(3, h.lanApi.trackRequests.size)
        assertTrue(old.all { it !in h.media.entries })
        assertTrue(h.store.relocatedRows.isEmpty())
        assertTrue(h.store.placed.values.none { it.mediaUri in old })
        assertEquals(SD, h.store.settings[SyncValues.PLACED_VOLUME_KEY])
    }

    @Test
    fun `TA-218 保存先が変わった間は自動の内容が同じという省略をしない`() = runTest {
        val h = Harness(SyncTrigger.Auto)
        h.sync(tracks)
        h.seedOldPlaced(tracks)
        h.store.addRun(
            SyncRun(
                deviceId = DEVICE, trigger = SyncRun.TRIGGER_MANUAL, startedAt = h.clock - 600 * 60_000L, endedAt = h.clock - 599 * 60_000L,
                result = SyncRun.RESULT_SUCCESS, contentDigest = "digest-1", libraryId = "LIB1", reported = 1,
            ),
        )
        h.volume = SD

        val row = assertNotNull(h.run())

        assertEquals(3, row.added)
    }

    @Test
    fun `TA-219 取り直しが一部失敗で終わった実行では古い場所のファイルを消さず、次の完了で消す`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val old = h.seedOldPlaced(tracks)
        h.volume = SD
        h.lanApi.trackScript = { request ->
            if (request.persistentId == tracks[1].persistentId) {
                TrackScript.Fail(ApiFailure.FileMissing)
            } else {
                TrackScript.Body(Harness.contentOf(tracks.first { it.persistentId == request.persistentId }), startAt = request.rangeFrom)
            }
        }

        assertEquals(SyncRun.RESULT_PARTIAL, assertNotNull(h.run()).result)
        assertTrue(old.all { it in h.media.entries })
        assertEquals(old.toSet(), h.store.relocatedRows.toSet())

        h.serveTracks(tracks)
        assertEquals(SyncRun.RESULT_SUCCESS, assertNotNull(h.run()).result)
        assertTrue(old.all { it !in h.media.entries })
        assertTrue(h.store.relocatedRows.isEmpty())
    }

    @Test
    fun `TA-220 容量は新しい場所で判定し、足りないときは古い場所のファイルを消さない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val old = h.seedOldPlaced(tracks)
        h.volume = SD
        h.freeBytes = 300 + SyncValues.STORAGE_MARGIN_BYTES - 1

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_NOT_STARTED, row.result)
        assertTrue(old.all { it in h.media.entries })
        assertTrue(h.lanApi.trackRequests.isEmpty())
    }

    @Test
    fun `TA-218 保存先が同じなら T1 を動かさず取り直さない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.seedOldPlaced(tracks)

        val row = assertNotNull(h.run())

        assertEquals(0, row.added)
        assertTrue(h.lanApi.trackRequests.isEmpty())
        assertEquals(3, h.store.placed.size)
    }
}
