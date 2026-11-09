package playlistsyncfox.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import playlistsyncfox.android.db.SelectedPlaylist
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.sync.CatalogResult
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.leafIds
import playlistsyncfox.shared.manifest.withFolderChecked
import playlistsyncfox.shared.manifest.withLeafChecked

// S6 プレイリストの選択（X7-1-7）。選択は T8 に即保存する。

sealed interface SelectionStage {
    data object Loading : SelectionStage

    /** PC の一覧を取れた。[rows] は選択（T8）。 */
    data class Loaded(val nodes: List<PlaylistNode>, val rows: List<SelectedPlaylist>) : SelectionStage {
        val selectedIds: List<String> get() = rows.map { it.playlistId }

        /** 選択のうち、PC の一覧に無いもの。保存した名前で「見つからない」と出す。 */
        val missing: List<SelectedPlaylist>
            get() {
                val known = nodes.flatMap(::leafIds).toSet()
                return rows.filter { it.playlistId !in known }
            }
    }

    /** 一覧を取れなかった。保存済みの選択 [rows] は変えず、「外す」だけできる。 */
    data class Failed(val reason: CatalogResult, val rows: List<SelectedPlaylist>) : SelectionStage
}

class SelectionPresenter(
    private val scope: CoroutineScope,
    private val store: SyncStore,
    private val fetch: suspend () -> CatalogResult,
    private val now: () -> Long,
) {
    private val mutableStage = MutableStateFlow<SelectionStage>(SelectionStage.Loading)
    val stage: StateFlow<SelectionStage> = mutableStage

    private val changing = Mutex()

    fun load() {
        scope.launch {
            mutableStage.value = SelectionStage.Loading
            val rows = store.selectedPlaylists()
            mutableStage.value = when (val result = fetch()) {
                is CatalogResult.Loaded -> SelectionStage.Loaded(result.nodes, rows)
                else -> SelectionStage.Failed(result, rows)
            }
        }
    }

    fun setLeaf(id: String, checked: Boolean) = change { selected -> withLeafChecked(selected, id, checked) }

    fun setFolder(folder: PlaylistNode, checked: Boolean) = change { selected -> withFolderChecked(selected, folder, checked) }

    /** 「見つからない」を外す。 */
    fun remove(id: String) = change { selected -> withLeafChecked(selected, id, false) }

    private fun change(next: (List<String>) -> List<String>) {
        scope.launch {
            // 保存が終わる前に次の変更が来ても、前の変更の結果に重ねる。順に 1 件ずつ行う
            changing.withLock {
                val current = mutableStage.value
                val (nodes, rows) = when (current) {
                    is SelectionStage.Loaded -> current.nodes to current.rows
                    is SelectionStage.Failed -> emptyList<PlaylistNode>() to current.rows
                    SelectionStage.Loading -> return@launch
                }
                val kept = rows.associateBy { it.playlistId }
                val names = nodes.flatMap(::leaves).associate { it.id to it.name }
                val ids = next(rows.map { it.playlistId })
                val updated = ids.map { id -> kept[id] ?: SelectedPlaylist(id, names[id].orEmpty(), now()) }
                store.replaceSelection(updated)
                mutableStage.value = when (current) {
                    is SelectionStage.Loaded -> current.copy(rows = updated)
                    is SelectionStage.Failed -> current.copy(rows = updated)
                    SelectionStage.Loading -> current
                }
            }
        }
    }

    private fun leaves(node: PlaylistNode): List<PlaylistNode> = node.children?.flatMap(::leaves) ?: listOf(node)
}
