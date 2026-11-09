package playlistsyncfox.desktop.server

import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaylistTreeTest {
    private fun playlist(id: String, parent: String? = null, kind: PlaylistKind = PlaylistKind.Normal, tracks: Int = 1) =
        LibraryPlaylist(id, "名前$id", parent, kind, List(tracks) { TrackRef.Identified("AAAAAAAAAAAAAAAA") })

    private val folder = PlaylistKind.Excluded(PlaylistExclusion.Folder)

    @Test
    fun `TD-80 フォルダは子を持ち、葉は曲数を持つ`() {
        val tree = buildPlaylistTree(listOf(playlist("F1", kind = folder, tracks = 0), playlist("L1", parent = "F1", tracks = 3), playlist("L2")))

        val f1 = tree.first { it.id == "F1" }
        assertNull(f1.trackCount)
        assertEquals(listOf("L1"), f1.children!!.map { it.id })
        assertEquals(3, f1.children!!.single().trackCount)
        assertNull(tree.first { it.id == "L2" }.children)
    }

    @Test
    fun `X5-21 フォルダの中のフォルダは入れ子の children になり、一番下の葉が曲数を持つ`() {
        val tree = buildPlaylistTree(
            listOf(
                playlist("F1", kind = folder, tracks = 0),
                playlist("F2", parent = "F1", kind = folder, tracks = 0),
                playlist("L1", parent = "F2", tracks = 2),
            ),
        )

        val outer = tree.single()
        assertEquals(listOf("F2"), outer.children!!.map { it.id })
        val inner = outer.children!!.single()
        assertEquals(listOf("L1"), inner.children!!.map { it.id })
        assertEquals(2, inner.children!!.single().trackCount)
    }

    @Test
    fun `TD-80 組み込みの一覧は木に出さず、親が一覧に無い子は最上位に出る`() {
        val tree = buildPlaylistTree(
            listOf(
                playlist("MASTER", kind = PlaylistKind.Excluded(PlaylistExclusion.Master)),
                playlist("DK", kind = PlaylistKind.Excluded(PlaylistExclusion.DistinguishedKind)),
                playlist("ORPHAN", parent = "MISSING"),
            ),
        )

        assertEquals(listOf("ORPHAN"), tree.map { it.id })
    }
}
