package playlistsyncfox.shared.manifest

import playlistsyncfox.shared.manifest.ManifestReadResult.Broken
import playlistsyncfox.shared.manifest.ManifestReadResult.Loaded
import playlistsyncfox.shared.manifest.ManifestReadResult.VersionMismatch
import playlistsyncfox.shared.sampleManifest
import playlistsyncfox.shared.playlist
import playlistsyncfox.shared.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManifestReaderTest {
    private val idA = "A000000000000001"
    private val idB = "B000000000000002"

    private fun read(text: String) = readManifest(text)

    private fun loaded(text: String): Loaded = assertIs<Loaded>(read(text))

    @Test
    fun `TS-01 全項目を持つマニフェストを書いて読むと元と同じ値になる`() {
        val full = track(idA).copy(
            trackNumber = 3,
            discNumber = 1,
            durationMs = 215_000,
            kind = "MPEG",
            contentHash = "hash",
        )
        val original = sampleManifest(listOf(full), listOf(playlist("D000000000000001", "L", listOf(idA)))).copy(
            unresolvedPlaylists = listOf(UnresolvedPlaylist("C000000000000001", "Old")),
            excluded = listOf(ExcludedTrack("N", "Ar", ExcludedReason.NOT_FOUND)),
        )

        assertEquals(original, loaded(writeManifest(original)).manifest)
    }

    @Test
    fun `TS-02 未知のキーと content_hash を足した本文を読むと既知の項目の値は変わらない`() {
        val original = sampleManifest(listOf(track(idA)))
        val text = writeManifest(original)
            .replace("{\"format_version\"", "{\"future\":{\"a\":[1,2]},\"format_version\"")
            .replace("\"tracks\":[{", "\"tracks\":[{\"unknown_key\":\"x\",\"content_hash\":\"abc\",")

        val read = loaded(text).manifest

        assertEquals(original.tracks, read.tracks.map { it.copy(contentHash = null) })
        assertEquals(original.libraryId, read.libraryId)
    }

    @Test
    fun `TS-03 任意の項目が無い曲は null で読まれ本文にキーが出ない`() {
        val bare = sampleManifest(listOf(track(idA, name = null, artist = null, album = null)))

        val text = writeManifest(bare)

        assertFalse(text.contains("album_artist"))
        assertFalse(text.contains("\"name\""))
        assertEquals(bare, loaded(text).manifest)
    }

    @Test
    fun `TS-03 任意の項目に値がある曲は書いて読むと同じ値になる`() {
        val filled = sampleManifest(listOf(track(idA, albumArtist = "AA")))

        assertEquals(filled, loaded(writeManifest(filled)).manifest)
    }

    @Test
    fun `TS-04 persistent_id が 15 桁か小文字か非 16 進なら壊れたとして理由を返す`() {
        val base = writeManifest(sampleManifest(listOf(track(idA))))

        for (badId in listOf("A00000000000001", "a000000000000001", "G000000000000001")) {
            val broken = assertIs<Broken>(read(base.replace(idA, badId)))
            assertTrue(broken.reason.isNotEmpty())
        }
    }

    @Test
    fun `TS-05 同じ ID の曲が 3 件あると最初の 1 件だけ残り落とした件数は 2 になる`() {
        val first = track(idA, name = "first")
        val dup = sampleManifest(listOf(first, track(idA, name = "second"), track(idA, name = "third")))

        val result = loaded(writeManifest(dup))

        assertEquals(listOf(first), result.manifest.tracks)
        assertEquals(2, result.duplicatesDropped)
    }

    @Test
    fun `同じ ID のプレイリストが 2 件あると最初の 1 件だけ残り曲の落とした件数は変わらない`() {
        val first = playlist("D000000000000001", "first", listOf(idA))
        val dup = sampleManifest(listOf(track(idA)), listOf(first, playlist("D000000000000001", "second")))

        val result = loaded(writeManifest(dup))

        assertEquals(listOf(first), result.manifest.playlists)
        assertEquals(0, result.duplicatesDropped)
    }

    @Test
    fun `同じ ID の未解決のプレイリストが 2 件あると最初の 1 件だけ残る`() {
        val first = UnresolvedPlaylist("C000000000000001", "first")
        val dup = sampleManifest().copy(unresolvedPlaylists = listOf(first, UnresolvedPlaylist("C000000000000001", "second")))

        assertEquals(listOf(first), loaded(writeManifest(dup)).manifest.unresolvedPlaylists)
    }

    @Test
    fun `TS-06 知る版の範囲の外の format_version は版が合わないとして受け取った版を返す`() {
        val base = writeManifest(sampleManifest())

        assertEquals(VersionMismatch(2), read(base.replace("\"format_version\":1", "\"format_version\":2")))
        assertEquals(VersionMismatch(0), read(base.replace("\"format_version\":1", "\"format_version\":0")))
    }

    @Test
    fun `TS-06 format_version が無い本文は壊れたになる`() {
        val text = writeManifest(sampleManifest()).replace("\"format_version\":1,", "")

        assertIs<Broken>(read(text))
    }

    @Test
    fun `TS-07 tracks に無い ID を持つプレイリストは壊れたになる`() {
        val dangling = sampleManifest(listOf(track(idA)), listOf(playlist("D000000000000001", trackIds = listOf(idB))))

        assertIs<Broken>(read(writeManifest(dangling)))
    }

    @Test
    fun `TS-153 size が無い曲は壊れたとして理由を返す`() {
        val text = writeManifest(sampleManifest(listOf(track(idA, size = 4242)))).replace("\"size\":4242,", "")

        assertTrue(assertIs<Broken>(read(text)).reason.isNotEmpty())
    }

    @Test
    fun `TS-153 modified_at が文字列の曲は壊れたとして理由を返す`() {
        val text = writeManifest(sampleManifest(listOf(track(idA, modifiedAt = 4242)))).replace("\"modified_at\":4242", "\"modified_at\":\"x\"")

        assertTrue(assertIs<Broken>(read(text)).reason.isNotEmpty())
    }
}
