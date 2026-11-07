package playlistsyncfox.desktop.server

import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.shared.manifest.PlaylistNode

/**
 * P7 の木。葉は通常のプレイリスト、フォルダは `Folder` の印のもの。組み込みの一覧（Master、Distinguished Kind）は出さない。
 * 合計サイズとスマートの印は M2 の結果に無いので持たない。
 */
fun buildPlaylistTree(playlists: List<LibraryPlaylist>): List<PlaylistNode> {
    val shown = playlists.filter {
        it.kind == PlaylistKind.Normal || it.kind == PlaylistKind.Excluded(PlaylistExclusion.Folder)
    }
    val ids = shown.map { it.persistentId }.toSet()
    val childrenOf = shown.groupBy { it.parentPersistentId?.takeIf { parent -> parent in ids } }

    fun node(playlist: LibraryPlaylist): PlaylistNode =
        if (playlist.kind == PlaylistKind.Normal) {
            PlaylistNode(playlist.persistentId, playlist.name, trackCount = playlist.items.size)
        } else {
            PlaylistNode(playlist.persistentId, playlist.name, children = childrenOf[playlist.persistentId].orEmpty().map(::node))
        }
    return childrenOf[null].orEmpty().map(::node)
}
