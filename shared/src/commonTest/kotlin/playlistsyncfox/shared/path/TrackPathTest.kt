package playlistsyncfox.shared.path

import playlistsyncfox.shared.V23
import playlistsyncfox.shared.V24
import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.pid
import playlistsyncfox.shared.record
import playlistsyncfox.shared.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackPathTest {
    private val a = pid('A')
    private val b = pid('B')
    private val c = pid('C')

    private fun candidate(track: ManifestTrack) = buildTrackCandidate(track, V23)

    private fun resolve(
        tracks: List<ManifestTrack>,
        records: List<SyncRecord> = emptyList(),
        used: Set<String> = emptySet(),
    ) = resolveTrackPaths(tracks, records, used, V24, V23)

    private fun lastSegmentBytes(path: String) = path.substringAfterLast('/').encodeToByteArray().size

    @Test
    fun `TS-49 切った結果が空白で終わる名前は末尾の空白が削られる`() {
        val parts = candidate(track(a, artist = "a".repeat(V23 - 1) + " b"))

        assertEquals("a".repeat(V23 - 1), parts.artistFolder)
    }

    @Test
    fun `TS-49 切った結果が点で終わる名前は末尾の点が削られる`() {
        val parts = candidate(track(a, artist = "a".repeat(V23 - 1) + ".b"))

        assertEquals("a".repeat(V23 - 1), parts.artistFolder)
    }

    @Test
    fun `TS-49 切った結果が空になる名前は代わりの文字列になる`() {
        val parts = buildTrackCandidate(track(a, artist = "😀😀"), 3)

        assertEquals("Unknown Artist", parts.artistFolder)
    }

    @Test
    fun `TS-50 長い曲名でも拡張子と接尾辞を含めた要素は上限以内に収まる`() {
        val parts = candidate(track(a, name = "あ".repeat(300)))

        assertTrue(lastSegmentBytes(assemblePath(parts, "")) <= V23)
        assertTrue(lastSegmentBytes(assemblePath(parts, " [AAAAAAAA]")) <= V23)
    }

    @Test
    fun `TS-50 拡張子が上限ちょうどならそのまま残り 1 バイト超えると上限に切れる`() {
        // 上限は V23 から、代わりの名前 Untitled の 8 バイト、全文の接尾辞の 19 バイト、先頭の点の 1 バイトを引いた値
        val maxExtensionBytes = V23 - 8 - 19 - 1

        val exact = candidate(track(a, extension = "e".repeat(maxExtensionBytes)))
        val over = candidate(track(a, extension = "e".repeat(maxExtensionBytes + 1)))

        assertEquals("." + "e".repeat(maxExtensionBytes), exact.extension)
        assertEquals("." + "e".repeat(maxExtensionBytes), over.extension)
    }

    @Test
    fun `TS-50 拡張子が異常に長く曲名が空でも全文の接尾辞を付けた要素は上限以内に収まる`() {
        val parts = candidate(track(a, name = null, extension = "e".repeat(V23 * 2)))

        assertTrue(lastSegmentBytes(assemblePath(parts, "")) <= V23)
        assertTrue(lastSegmentBytes(assemblePath(parts, suffixOf(a))) <= V23)
    }

    @Test
    fun `TS-51 アルバムアーティストがあればそれを無ければアーティストをフォルダ名に使う`() {
        assertEquals("AA", candidate(track(a, albumArtist = "AA", artist = "Ar")).artistFolder)
        assertEquals("Ar", candidate(track(a, albumArtist = null, artist = "Ar")).artistFolder)
        assertEquals("Ar", candidate(track(a, albumArtist = "", artist = "Ar")).artistFolder)
    }

    @Test
    fun `TS-51 アルバムアーティストが空白だけなら空でない扱いで選び空になって Unknown Artist になる`() {
        assertEquals("Unknown Artist", candidate(track(a, albumArtist = "  ", artist = "Ar")).artistFolder)
    }

    @Test
    fun `TS-51 アーティストとアルバムと曲名がすべて空なら代わりの文字列になる`() {
        val parts = candidate(track(a, name = null, artist = null, album = null))

        assertEquals("Unknown Artist/Unknown Album/Untitled.mp3", assemblePath(parts, ""))
    }

    @Test
    fun `TS-53 拡張子が空文字列なら末尾に点が付かない`() {
        val parts = candidate(track(a, extension = ""))

        assertEquals("Ar/Al/T", assemblePath(parts, ""))
    }

    @Test
    fun `TS-54 同じ候補の 2 曲は ID の昇順で先頭が接尾辞なしで後が接尾辞つきになる`() {
        val paths = resolve(listOf(track(b), track(a)))

        assertEquals("Ar/Al/T.mp3", paths[a])
        assertEquals("Ar/Al/T [BBBBBBBB].mp3", paths[b])
    }

    @Test
    fun `TS-54 同じ候補の 3 曲は先頭が接尾辞なしで残りの 2 曲は互いに別のパスになる`() {
        val paths = resolve(listOf(track(c), track(b), track(a)))

        assertEquals("Ar/Al/T.mp3", paths[a])
        assertEquals(3, paths.values.toSet().size)
    }

    @Test
    fun `TS-56 使用中の集合に候補と同じパスがあると接尾辞つきのパスになる`() {
        val paths = resolve(listOf(track(a)), used = setOf("Ar/Al/T.mp3"))

        assertEquals("Ar/Al/T [AAAAAAAA].mp3", paths[a])
    }

    @Test
    fun `TS-57 接尾辞つきのパスも使用中なら接尾辞を全文にしたパスになる`() {
        val used = setOf("Ar/Al/T.mp3", "Ar/Al/T [AAAAAAAA].mp3")

        assertEquals("Ar/Al/T [${a}].mp3", resolve(listOf(track(a)), used = used)[a])
    }

    @Test
    fun `TS-57 全文の接尾辞のパスも使用中なら解けないとせずそのパスをそのまま返す`() {
        val full = "Ar/Al/T [${a}].mp3"
        val used = setOf("Ar/Al/T.mp3", "Ar/Al/T [AAAAAAAA].mp3", full)

        assertEquals(full, resolve(listOf(track(a)), used = used)[a])
    }

    @Test
    fun `TS-58 置き換えの曲は後から現れた ID の小さい新しい曲があっても記録のパスのまま動かない`() {
        val existing = track(b)
        val newcomer = track(a)
        val records = listOf(record(b, placedPath = "Ar/Al/T.mp3"))

        val paths = resolve(listOf(newcomer, existing), records)

        assertEquals("Ar/Al/T.mp3", paths[b])
        assertEquals("Ar/Al/T [AAAAAAAA].mp3", paths[a])
    }

    @Test
    fun `TS-59 同じ曲の集合の並びを替えても結果は同じになる`() {
        val tracks = listOf(track(a), track(b), track(c))

        assertEquals(resolve(tracks), resolve(tracks.reversed()))
    }

    @Test
    fun `TS-60 大文字小文字だけが違うパスは衝突として扱い後の曲に接尾辞を付け綴りは元のまま返す`() {
        val paths = resolve(listOf(track(a, name = "A"), track(b, name = "a")))

        assertEquals("Ar/Al/A.mp3", paths[a])
        assertEquals("Ar/Al/a [BBBBBBBB].mp3", paths[b])
    }

    @Test
    fun `TS-61 見た目が同じで正規化形が違う名前は衝突せず接尾辞なしの別のパスになる`() {
        val precomposed = "が"
        val decomposed = "が"

        val paths = resolve(listOf(track(a, name = precomposed), track(b, name = decomposed)))

        assertEquals("Ar/Al/$precomposed.mp3", paths[a])
        assertEquals("Ar/Al/$decomposed.mp3", paths[b])
        assertNotEquals(paths[a], paths[b])
    }

    @Test
    fun `TS-143 m3u8 は固定の MIME を返し aif と aiff は同じ値を返す`() {
        assertEquals("audio/x-mpegurl", mimeTypeOf("m3u8"))
        assertEquals(mimeTypeOf("aif"), mimeTypeOf("aiff"))
        assertTrue(mimeTypeOf("aif") != null)
    }

    @Test
    fun `TS-143 表に無い拡張子の MIME は無しになる`() {
        assertNull(mimeTypeOf("xyz"))
    }
}
