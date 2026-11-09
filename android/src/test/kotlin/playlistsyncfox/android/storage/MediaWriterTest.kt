package playlistsyncfox.android.storage

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.Harness
import playlistsyncfox.android.ByteSource
import playlistsyncfox.android.ROOT
import playlistsyncfox.android.db.PendingTransfer
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.id
import playlistsyncfox.android.track
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.result.WriteStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PATH = "Artist/Album/Song 1.mp3"

private fun bytes(size: Int) = ByteArray(size) { it.toByte() }

class MediaWriterTest {
    private val h = Harness()
    private val song = track(1, size = 100)

    private suspend fun writeFresh(disconnectAfter: Int? = null): TrackWriteResult {
        val plan = h.writer.plan(song, PATH, placed = null, replace = false)
        return h.writer.write(plan, TrackBody(0, ByteSource(bytes(100), disconnectAfter)))
    }

    private fun pendingRow(uri: String, received: Long = 0, modified: Long = song.modifiedAt, expected: Long = song.size) =
        PendingTransfer(song.persistentId, uri, expected, received, modified, h.clock)

    @Test
    fun `TA-004 書き込みに成功すると T1 に読み戻した値が入り T5 は無く行は pending でない`() = runTest {
        val result = writeFresh()

        val row = assertIs<TrackWriteResult.Placed>(result).row
        assertEquals(PATH, row.relativePath)
        assertEquals(100, row.localSize)
        assertEquals(song.modifiedAt, row.sourceModified)
        assertEquals(row, h.store.placed[song.persistentId])
        assertNull(h.store.pendingRows[song.persistentId])
        assertTrue(h.media.entries.values.none { it.pending })
    }

    @Test
    fun `TA-005 行の作成に失敗すると書き込みは始まらず T1 も T5 も残らない`() = runTest {
        h.media.failInsert = true

        assertEquals(TrackWriteResult.Failed(WriteStep.MediaStoreRegistration), writeFresh())

        assertTrue(h.store.placed.isEmpty())
        assertTrue(h.store.pendingRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `TA-006 読み戻したサイズが size と違うと行を削除し T1 に入らない`() = runTest {
        h.media.readBackSizeDelta = -1

        assertIs<TrackWriteResult.Failed>(writeFresh())

        assertTrue(h.store.placed.isEmpty())
        assertTrue(h.store.pendingRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `TA-007 読み戻した相対パスが Music の保存先の外なら E9 で行を削除する`() = runTest {
        h.media.readBackRelativePath = "Download/Album/"

        assertIs<TrackWriteResult.Failed>(writeFresh())

        assertTrue(h.store.placed.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `TA-009 source_modified が違う T5 は使わず最初から書き直す`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(40))
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 40, modified = song.modifiedAt + 1)

        val plan = h.writer.plan(song, PATH, null, replace = false)

        assertIs<WritePlan.Fresh>(plan)
        assertTrue(uri !in h.media.entries)
        assertNull(h.store.pendingRows[song.persistentId])
    }

    @Test
    fun `TA-010 T5 の URI が MediaStore に無ければ T5 を消して最初から書く`() = runTest {
        h.store.pendingRows[song.persistentId] = pendingRow("content://media/test/missing", received = 40)

        assertIs<WritePlan.Fresh>(h.writer.plan(song, PATH, null, replace = false))

        assertNull(h.store.pendingRows[song.persistentId])
    }

    @Test
    fun `TA-011 置き換えは T5 が残っていても使わず先頭から書き T5 を作らない`() = runTest {
        val uri = h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3", bytes = 50)
        val placed = PlacedTrack(DEVICE, song.persistentId, PATH, uri, 50, 0, 50, PlacedTrack.STATE_PLACED, 0)
        h.store.placed[song.persistentId] = placed
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 30)

        val plan = h.writer.plan(song, PATH, placed, replace = true)
        assertIs<WritePlan.Replace>(plan)
        var pendingDuringWrite: PendingTransfer? = h.store.pendingRows[song.persistentId]
        h.media.onWrite = { pendingDuringWrite = h.store.pendingRows[song.persistentId] }
        val result = h.writer.write(plan, TrackBody(0, ByteSource(bytes(100))))

        assertIs<TrackWriteResult.Placed>(result)
        // 残っていた T5 の続きは使わず、書き込みの間に T5 を更新しない（置き換えの行を作らない）
        assertEquals(30, pendingDuringWrite?.receivedBytes)
        assertEquals(100, h.store.placed.getValue(song.persistentId).localSize)
        assertTrue(bytes(100).contentEquals(h.media.entries.getValue(uri).data.toByteArray()))
    }

    @Test
    fun `TA-040 再開の位置は pending のファイルの大きさで T5 とずれていれば T5 を直す`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(800))
        val big = track(1, size = 2000)
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 1000, expected = big.size)

        val plan = h.writer.plan(big, PATH, null, replace = false)

        assertEquals(800, assertIs<WritePlan.Resume>(plan).resumeFrom)
        assertEquals(800, h.store.pendingRows.getValue(song.persistentId).receivedBytes)
    }

    @Test
    fun `TA-040 T5 と pending のファイルがどちらも 1000 バイトなら 1000 バイト目から要求する`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(1000))
        val big = track(1, size = 2000)
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 1000, expected = big.size)

        assertEquals(1000, assertIs<WritePlan.Resume>(h.writer.plan(big, PATH, null, replace = false)).resumeFrom)
    }

    @Test
    fun `TA-040 pending のファイルの大きさが expected_size と同じなら取得せず読み戻しと T1 の記録へ進む`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(100))
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 90)

        val plan = h.writer.plan(song, PATH, null, replace = false)
        assertIs<WritePlan.Complete>(plan)
        val result = h.writer.write(plan, null)

        assertIs<TrackWriteResult.Placed>(result)
        assertNull(h.store.pendingRows[song.persistentId])
        assertEquals(PATH, h.store.placed.getValue(song.persistentId).relativePath)
    }

    @Test
    fun `TA-052 expected_size が違う T5 は source_modified が同じでも再開せず新規に書く`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(40))
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 40, expected = 999)

        assertIs<WritePlan.Fresh>(h.writer.plan(song, PATH, null, replace = false))
    }

    @Test
    fun `再開は途中から追記し T1 に全体の大きさが入る`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(100), 0, 60)
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 60)

        val plan = h.writer.plan(song, PATH, null, replace = false)
        val result = h.writer.write(plan, TrackBody(60, ByteSource(bytes(100).copyOfRange(60, 100))))

        assertEquals(100, assertIs<TrackWriteResult.Placed>(result).row.localSize)
        assertTrue(bytes(100).contentEquals(h.media.entries.getValue(uri).data.toByteArray()))
    }

    @Test
    fun `追記で開けないときは pending の行と T5 を消して取り直しを求める`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(bytes(60))
        h.store.pendingRows[song.persistentId] = pendingRow(uri, received = 60)
        h.media.failAppend = true

        val plan = h.writer.plan(song, PATH, null, replace = false)
        val result = h.writer.write(plan, TrackBody(60, ByteSource(bytes(40))))

        assertEquals(TrackWriteResult.NeedRestart, result)
        assertNull(h.store.pendingRows[song.persistentId])
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `取得元が途中で切れると pending の行と T5 を受け取った分まで残す`() = runTest {
        assertEquals(TrackWriteResult.Disconnected, writeFresh(disconnectAfter = 50))

        assertEquals(50, h.store.pendingRows.getValue(song.persistentId).receivedBytes)
        assertEquals(1, h.media.entries.size)
        assertTrue(h.store.placed.isEmpty())
    }

    @Test
    fun `TA-059 曲を消すと T1 の行が消え MediaStore に行が既に無くても T1 の行は消える`() = runTest {
        val first = h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3")
        val gone = PlacedTrack(DEVICE, id(2), "Artist/Album/Song 2.mp3", "content://media/test/gone", 10, 0, 10, PlacedTrack.STATE_PLACED, 0)
        val present = PlacedTrack(DEVICE, id(1), PATH, first, 10, 0, 10, PlacedTrack.STATE_PLACED, 0)
        h.store.placed[present.persistentId] = present
        h.store.placed[gone.persistentId] = gone

        assertTrue(h.writer.delete(present))
        assertTrue(h.writer.delete(gone))

        assertTrue(h.store.placed.isEmpty())
    }

    @Test
    fun `TA-059 権限の例外では T1 の行を残して失敗を返す`() = runTest {
        val uri = h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3")
        val placed = PlacedTrack(DEVICE, id(1), PATH, uri, 10, 0, 10, PlacedTrack.STATE_PLACED, 0)
        h.store.placed[placed.persistentId] = placed
        h.media.deniedDeletes += uri

        assertEquals(false, h.writer.delete(placed))

        assertEquals(placed, h.store.placed[placed.persistentId])
    }

    @Test
    fun `TA-050 入れ直し後の別名 (1) は改名とみなさず既存の URI を書き直す`() = runTest {
        val uri = h.media.addExisting(ROOT, "Mix (1).m3u8", playlist = true)
        h.store.playlistRows[id(9)] = playlistRow(id(9), "Mix (1).m3u8", uri)

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(uri, assertIs<PlaylistWriteResult.Written>(result).row.mediaUri)
        assertTrue(h.media.deleted.isEmpty())
        assertEquals("#EXTM3U\n", h.media.entries.getValue(uri).data.toString(Charsets.UTF_8))
    }

    @Test
    fun `TA-050 期待した名前と別の名前のときだけ改名として古い m3u8 を消して作り直す`() = runTest {
        val old = h.media.addExisting(ROOT, "Old.m3u8", playlist = true)
        h.store.playlistRows[id(9)] = playlistRow(id(9), "Old.m3u8", old)

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "New", emptyList()), "New.m3u8", "#EXTM3U\n", 0)

        val row = assertIs<PlaylistWriteResult.Written>(result).row
        assertEquals("New.m3u8", row.relativePath)
        assertEquals(listOf(old), h.media.deleted)
    }

    @Test
    fun `m3u8 は本文を BOM なしの UTF-8 で pending から公開して T2 に記録する`() = runTest {
        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "日本語", emptyList()), "日本語.m3u8", "#EXTM3U\nArtist/曲.mp3\n", 1)

        val row = assertIs<PlaylistWriteResult.Written>(result).row
        val entry = h.media.entries.getValue(row.mediaUri)
        assertTrue(!entry.pending)
        assertEquals("#EXTM3U\nArtist/曲.mp3\n", entry.data.toString(Charsets.UTF_8))
        assertEquals(row, h.store.playlistRows[id(9)])
    }

    @Test
    fun `TA-046 どちらのリストにも無い T2 の行は m3u8 と一緒に消し未解決のものは残す`() = runTest {
        val gone = h.media.addExisting(ROOT, "Gone.m3u8", playlist = true)
        val kept = h.media.addExisting(ROOT, "Unresolved.m3u8", playlist = true)
        h.store.playlistRows[id(1)] = playlistRow(id(1), "Gone.m3u8", gone)
        h.store.playlistRows[id(2)] = playlistRow(id(2), "Unresolved.m3u8", kept)

        h.writer.removeStalePlaylists(keepIds = setOf(id(2)))

        assertEquals(setOf(id(2)), h.store.playlistRows.keys)
        assertTrue(gone !in h.media.entries)
        assertTrue(kept in h.media.entries)
    }

    @Test
    fun `TA-044 以前の曲の一覧に T2 に無い古い m3u8 も入り T1 の曲は入らない`() = runTest {
        val mine = h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3")
        h.store.placed[id(1)] = PlacedTrack(DEVICE, id(1), PATH, mine, 10, 0, 10, PlacedTrack.STATE_PLACED, 0)
        h.media.addExisting("${ROOT}Artist/Album/", "Old Song.mp3")
        h.media.addExisting(ROOT, "Old.m3u8", playlist = true)

        val names = h.writer.leftovers().map { it.displayName }.sorted()

        assertEquals(listOf("Old Song.mp3", "Old.m3u8"), names)
    }

    @Test
    fun `m3u8 の行を作れないと書き込みは始まらず失敗を返し T2 にも行を残さない`() = runTest {
        h.media.failInsert = true

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(PlaylistWriteResult.Failed, result)
        assertTrue(h.store.playlistRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `m3u8 を書き込み用に開けないと作った pending の行を消して失敗を返す`() = runTest {
        h.media.failTruncate = true

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(PlaylistWriteResult.Failed, result)
        assertTrue(h.store.playlistRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `m3u8 の書き込み中に容量が尽きると作った pending の行を消して失敗を返す`() = runTest {
        h.media.diskFullAtTotalBytes = 0

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(PlaylistWriteResult.Failed, result)
        assertTrue(h.store.playlistRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `m3u8 を書いた後に公開できないと作った pending の行を消して失敗を返し T2 に行を残さない`() = runTest {
        // 書き込みと公開の間に行が消えた状態（publish が null を返す）
        var createdUri: String? = null
        h.media.onWrite = {
            createdUri = it.uri
            h.media.entries.remove(it.uri)
        }

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(PlaylistWriteResult.Failed, result)
        assertEquals(listOf(createdUri), h.media.deleted)
        assertTrue(h.store.playlistRows.isEmpty())
    }

    @Test
    fun `既存の m3u8 を書き直して公開できなくても既存の行は消さず T2 の行も残す`() = runTest {
        val uri = h.media.addExisting(ROOT, "Mix.m3u8", playlist = true)
        h.store.playlistRows[id(9)] = playlistRow(id(9), "Mix.m3u8", uri)
        h.media.onWrite = { h.media.entries.remove(it.uri) }

        val result = h.writer.writePlaylist(ManifestPlaylist(id(9), "Mix", emptyList()), "Mix.m3u8", "#EXTM3U\n", 0)

        assertEquals(PlaylistWriteResult.Failed, result)
        assertTrue(h.media.deleted.isEmpty())
        assertEquals(uri, h.store.playlistRows.getValue(id(9)).mediaUri)
    }

    @Test
    fun `新規の曲の本文が size より長いと E9 の書き込みの失敗で、pending の行と T5 を消す`() = runTest {
        val plan = h.writer.plan(song, PATH, placed = null, replace = false)

        val result = h.writer.write(plan, TrackBody(0, ByteSource(bytes(150))))

        assertEquals(TrackWriteResult.Failed(WriteStep.TempFileWrite), result)
        assertTrue(h.store.placed.isEmpty())
        assertTrue(h.store.pendingRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `置き換えの本文が size より長いと失敗を返し、元の行は消さず T1 は replacing のまま残る`() = runTest {
        val uri = h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3", bytes = 50)
        val placed = PlacedTrack(DEVICE, song.persistentId, PATH, uri, 50, 0, 50, PlacedTrack.STATE_PLACED, 0)
        h.store.placed[song.persistentId] = placed
        val plan = h.writer.plan(song, PATH, placed, replace = true)

        val result = h.writer.write(plan, TrackBody(0, ByteSource(bytes(150))))

        assertEquals(TrackWriteResult.Failed(WriteStep.TempFileWrite), result)
        assertTrue(uri in h.media.entries)
        assertEquals(PlacedTrack.STATE_REPLACING, h.store.placed.getValue(song.persistentId).state)
    }

    @Test
    fun `期限切れの T5 を回収すると pending の行と T5 が消える`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.store.pendingRows[song.persistentId] = pendingRow(uri).copy(startedAt = h.clock - SEVEN_DAYS - 1)

        h.writer.collectExpired()

        assertTrue(h.store.pendingRows.isEmpty())
        assertTrue(h.media.entries.isEmpty())
    }

    @Test
    fun `7 日ちょうどの T5 はまだ回収しない`() = runTest {
        val uri = h.media.insertPendingTrack("Song 1.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.store.pendingRows[song.persistentId] = pendingRow(uri).copy(startedAt = h.clock - SEVEN_DAYS)

        h.writer.collectExpired()

        assertNotNull(h.store.pendingRows[song.persistentId])
    }

    private fun playlistRow(playlistId: String, fileName: String, uri: String) =
        playlistsyncfox.android.db.WrittenPlaylist(DEVICE, playlistId, "n", fileName, uri, 0, 0)

    private companion object {
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
    }
}
