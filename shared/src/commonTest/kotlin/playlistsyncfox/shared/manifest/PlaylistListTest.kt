package playlistsyncfox.shared.manifest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistListTest {
    private val a = "A000000000000001"
    private val b = "B000000000000002"
    private val c = "C000000000000003"

    private fun ids(text: String, max: Int = 5000) = parsePlaylistIds(text, max)

    @Test
    fun `TS-158 正しい配列は重複を除いて入力の順で返る`() {
        assertEquals(PlaylistIdsResult.Ids(listOf(b, a)), ids("""{"playlist_ids":["$b","$a","$b"]}"""))
    }

    @Test
    fun `TS-158 空の配列は有効`() {
        assertEquals(PlaylistIdsResult.Ids(emptyList()), ids("""{"playlist_ids":[]}"""))
    }

    @Test
    fun `TS-158 個数は上限ちょうどまで通り1つ超で外れる`() {
        val two = """{"playlist_ids":["$a","$b"]}"""
        assertEquals(PlaylistIdsResult.Ids(listOf(a, b)), ids(two, max = 2))
        assertEquals(PlaylistIdsResult.Invalid, ids(two, max = 1))
    }

    @Test
    fun `TS-158 形の誤りは外れる`() {
        val bad = listOf(
            "",
            "not json",
            "{}",
            """{"playlist_ids":"$a"}""",
            """{"playlist_ids":[1]}""",
            """{"playlist_ids":[null]}""",
        )
        for (text in bad) assertEquals(PlaylistIdsResult.Invalid, ids(text), text)
    }

    @Test
    fun `TS-158 ID の書式の誤りは外れる`() {
        val bad = listOf(a.lowercase(), a.dropLast(1), a + "0", "G000000000000001", "")
        for (id in bad) assertEquals(PlaylistIdsResult.Invalid, ids("""{"playlist_ids":["$id"]}"""), id)
    }

    @Test
    fun `TS-158 書き出した本文は検査を通る`() {
        assertEquals(PlaylistIdsResult.Ids(listOf(a, c)), ids(writePlaylistIds(listOf(a, c))))
    }

    @Test
    fun `TS-159 木を書いて読むと元と同じ値になる`() {
        val tree = listOf(
            PlaylistNode(a, "フォルダ", children = listOf(PlaylistNode(b, "入れ子", children = listOf(PlaylistNode(c, "葉の曲", trackCount = 12))))),
            PlaylistNode("D000000000000004", "親なし", trackCount = 0),
        )

        assertEquals(tree, readPlaylistTree(writePlaylistTree(tree)))
    }

    @Test
    fun `TS-159 壊れた木は読めない`() {
        val bad = listOf(
            "",
            "not json",
            """{"playlists":"x"}""",
            """{"playlists":[{"persistent_id":"$a"}]}""",
            """{"playlists":[{"persistent_id":"zz","name":"n","track_count":1}]}""",
            """{"playlists":[{"persistent_id":"$a","name":"f","children":[{"persistent_id":"zz","name":"n","track_count":1}]}]}""",
        )
        for (text in bad) assertNull(readPlaylistTree(text), text)
    }

    private val folder = PlaylistNode(a, "folder", children = listOf(PlaylistNode(b, "b", trackCount = 1), PlaylistNode(c, "c", trackCount = 1)))

    @Test
    fun `TS-160 フォルダのチェックは配下の葉だけを重複なく選ぶ`() {
        assertEquals(listOf(b, c), withFolderChecked(listOf(b), folder, true))
    }

    @Test
    fun `TS-160 フォルダを外すと配下の葉だけが除かれ他の選択は残る`() {
        val other = "D000000000000004"

        assertEquals(listOf(other), withFolderChecked(listOf(b, c, other), folder, false))
    }

    @Test
    fun `TS-160 葉を外すと他の選択は残り選んだ葉を再び選んでも増えない`() {
        assertEquals(listOf(c), withLeafChecked(listOf(b, c), b, false))
        assertEquals(listOf(b), withLeafChecked(listOf(b), b, true))
    }

    @Test
    fun `TS-160 一覧に無い ID も外せる`() {
        val missing = "E000000000000005"

        assertEquals(listOf(b), withLeafChecked(listOf(b, missing), missing, false))
    }
}
