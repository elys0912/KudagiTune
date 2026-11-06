package playlistsyncfox.desktop.ui

import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind

/**
 * W2 の木。葉は通常のプレイリスト、フォルダは `Folder` の印のもの。組み込みの一覧（Master、Distinguished Kind）は出さない。
 * 合計サイズとスマートの印は M2 の結果に無いので持たない。
 */
fun buildPlaylistTree(playlists: List<LibraryPlaylist>): List<PlaylistNode> {
    val shown = playlists.filter {
        it.kind == PlaylistKind.Normal || it.kind == PlaylistKind.Excluded(PlaylistExclusion.Folder)
    }
    val ids = shown.map { it.persistentId }.toSet()
    val childrenOf = shown.groupBy { it.parentPersistentId?.takeIf { parent -> parent in ids } }

    fun node(playlist: LibraryPlaylist): PlaylistNode {
        val isFolder = playlist.kind != PlaylistKind.Normal
        return PlaylistNode(
            id = playlist.persistentId,
            name = playlist.name,
            children = if (isFolder) childrenOf[playlist.persistentId].orEmpty().map(::node) else null,
            trackCount = playlist.items.size,
        )
    }
    return childrenOf[null].orEmpty().map(::node)
}

fun leafIds(node: PlaylistNode): List<String> =
    node.children?.flatMap(::leafIds) ?: listOf(node.id)

/** フォルダのチェック：配下の葉を一括で選ぶ・外す。フォルダ自身は保存しない。 */
fun withFolderChecked(selected: List<String>, folder: PlaylistNode, checked: Boolean): List<String> {
    val leaves = leafIds(folder)
    return if (checked) selected + leaves.filter { it !in selected } else selected.filter { it !in leaves }
}

/** 葉のチェック。XML に無い ID の「外す」は、チェックを外す操作と同じ。 */
fun withLeafChecked(selected: List<String>, id: String, checked: Boolean): List<String> = when {
    !checked -> selected - id
    id in selected -> selected
    else -> selected + id
}
