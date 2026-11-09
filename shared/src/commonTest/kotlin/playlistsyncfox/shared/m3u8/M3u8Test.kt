package playlistsyncfox.shared.m3u8

import playlistsyncfox.shared.V23
import playlistsyncfox.shared.V24
import playlistsyncfox.shared.path.resolveTrackPaths
import playlistsyncfox.shared.pid
import playlistsyncfox.shared.playlist
import playlistsyncfox.shared.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class M3u8Test {
    private val a = pid('A')
    private val b = pid('B')
    private val c = pid('C')

    private fun fileNames(vararg playlists: Pair<String, String>) =
        buildPlaylistFileName(playlists.map { (id, name) -> playlist(id, name) }, V24, V23)

    @Test
    fun `TS-62 曲 3 件のプレイリストは先頭が EXTM3U で各行が LF で終わり BOM も CR も含まない`() {
        val paths = mapOf(a to "x/1.mp3", b to "x/2.mp3", c to "x/3.mp3")

        val text = buildM3u8(playlist(pid('P'), trackIds = listOf(a, b, c)), paths)

        assertEquals("#EXTM3U\nx/1.mp3\nx/2.mp3\nx/3.mp3\n", text)
    }

    @Test
    fun `TS-63 曲の無いプレイリストは EXTM3U と LF の 1 行になる`() {
        assertEquals("#EXTM3U\n", buildM3u8(playlist(pid('P')), emptyMap()))
    }

    @Test
    fun `TS-64 置けていない曲は飛ばして載せない`() {
        val text = buildM3u8(playlist(pid('P'), trackIds = listOf(a, b)), mapOf(a to "x/1.mp3"))

        assertEquals("#EXTM3U\nx/1.mp3\n", text)
    }

    @Test
    fun `TS-65 同じ曲が 2 回あれば同じパスを 2 行書く`() {
        val text = buildM3u8(playlist(pid('P'), trackIds = listOf(a, a)), mapOf(a to "x/1.mp3"))

        assertEquals("#EXTM3U\nx/1.mp3\nx/1.mp3\n", text)
    }

    @Test
    fun `TS-67 日本語と記号を含むパスは変換されずそのまま 1 行になる`() {
        val path = "歌手/100% [Live] 1/曲 名.mp3"

        val text = buildM3u8(playlist(pid('P'), trackIds = listOf(a)), mapOf(a to path))

        assertEquals("#EXTM3U\n$path\n", text)
    }

    @Test
    fun `TS-68 シャープで始まる名前と行区切り文字を含む名前でも m3u8 にそれらの行は出ない`() {
        val tracks = listOf(
            track(a, name = "#title", artist = "#artist", album = "#album"),
            track(b, name = "x\u0085y\u2028z\u2029w"),
        )
        val paths = resolveTrackPaths(tracks, emptyList(), emptySet(), V24, V23)

        val text = buildM3u8(playlist(pid('P'), trackIds = listOf(a, b)), paths)

        assertEquals("#EXTM3U\n_artist/_album/_title.mp3\nAr/Al/xyzw.mp3\n", text)
        assertFalse(text.contains('\u0085') || text.contains('\u2028') || text.contains('\u2029'))
    }

    @Test
    fun `TS-69 同じ名前の 2 本は両方に接尾辞が付き別のファイル名になる`() {
        val names = fileNames(a to "Mix", b to "Mix")

        assertEquals("Mix [AAAAAAAA].m3u8", names[a])
        assertEquals("Mix [BBBBBBBB].m3u8", names[b])
    }

    @Test
    fun `TS-69 大文字小文字だけ違う名前の 2 本も両方に接尾辞が付く`() {
        val names = fileNames(a to "A", b to "a")

        assertEquals("A [AAAAAAAA].m3u8", names[a])
        assertEquals("a [BBBBBBBB].m3u8", names[b])
    }

    @Test
    fun `TS-70 空の名前は Untitled に接尾辞が付く`() {
        assertEquals("Untitled [AAAAAAAA].m3u8", fileNames(a to "")[a])
    }

    @Test
    fun `TS-71 接尾辞を付けても同じになる 2 本は接尾辞が全文になり別のファイル名になる`() {
        val first = pid('A', 1)
        val second = pid('A', 2)

        val names = fileNames(first to "Mix", second to "Mix")

        assertEquals("Mix [$first].m3u8", names[first])
        assertEquals("Mix [$second].m3u8", names[second])
    }

    @Test
    fun `TS-72 上限を超える長い名前は拡張子を含めて上限以内に切れる`() {
        val name = fileNames(a to "あ".repeat(100)).getValue(a)

        assertTrue(name.endsWith(".m3u8"))
        assertTrue(name.encodeToByteArray().size <= V23)
        assertNotEquals(".m3u8", name)
    }
}
