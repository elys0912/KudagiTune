package playlistsyncfox.android.sync

import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.SyncApi
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.readPlaylistTree
import playlistsyncfox.shared.result.ErrorDetail

/** P7 の取得の結果（S6。X7-1-7）。 */
sealed interface CatalogResult {
    data class Loaded(val nodes: List<PlaylistNode>) : CatalogResult

    /** 保存した接続先のどれにも届かない（E1）。 */
    data object Unreachable : CatalogResult

    /** 署名や証明書の固定が合わない（E2）。再ペアリングが要る。 */
    data object KeyMismatch : CatalogResult

    /** E2 のうち、再ペアリングを要さない理由（時計のずれ、再送の検出、回数の制限）。[detail] で案内を変える。 */
    data class Rejected(val detail: ErrorDetail) : CatalogResult

    /** PC が XML を読めない（E4）。 */
    data object XmlUnavailable : CatalogResult

    /** そのほか（応答の形の違い、500 など）。 */
    data object Other : CatalogResult
}

/**
 * PC のプレイリストの一覧を取る（P7）。接続先は同期本体と同じ選び方（[reachPc]）で、最初に P7 に応えた接続先を使う。
 * E2、E4、500 を受けたら、その接続先で止める（PC には届いているため）。書くのは、つながった接続先の記録だけ。
 */
class PlaylistCatalog(private val env: SyncEnvironment) {
    suspend fun fetch(): CatalogResult {
        val pairing = env.store.pairing() ?: return CatalogResult.Other
        if (!env.aliasExists(pairing.keyAlias)) return CatalogResult.KeyMismatch

        val opened = mutableListOf<SyncApi>()
        try {
            return when (val reach = env.reachPc(pairing, opened) { it.playlists() }) {
                is Reach.Connected -> readPlaylistTree(reach.value)?.let { CatalogResult.Loaded(it) } ?: CatalogResult.Other
                is Reach.Stopped -> resultOf(reach.failure)
                is Reach.Unreachable -> if (reach.mismatch) CatalogResult.KeyMismatch else CatalogResult.Unreachable
            }
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    /** 届いた接続先の失敗。鍵の不一致（401 の `detail` なし）だけ再ペアリングへ誘導し、ほかの E2 は理由を持たせる。 */
    private fun resultOf(failure: ApiFailure): CatalogResult = when (failure) {
        is ApiFailure.Rejected -> when {
            failure.keyMismatch -> CatalogResult.KeyMismatch
            failure.detail != null -> CatalogResult.Rejected(failure.detail)
            else -> CatalogResult.Other
        }
        ApiFailure.XmlUnavailable -> CatalogResult.XmlUnavailable
        else -> CatalogResult.Other
    }
}
