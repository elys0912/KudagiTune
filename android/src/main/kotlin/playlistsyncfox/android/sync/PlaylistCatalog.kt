package playlistsyncfox.android.sync

import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.readPlaylistTree

/** P7 の取得の結果（S6。X7-1-7）。 */
sealed interface CatalogResult {
    data class Loaded(val nodes: List<PlaylistNode>) : CatalogResult

    /** 保存した接続先のどれにも届かない（E1）。 */
    data object Unreachable : CatalogResult

    /** 署名や証明書の固定が合わない（E2）。再ペアリングが要る。 */
    data object KeyMismatch : CatalogResult

    /** PC が XML を読めない（E4）。 */
    data object XmlUnavailable : CatalogResult

    /** そのほか（応答の形の違い、500 など）。 */
    data object Other : CatalogResult
}

/**
 * PC のプレイリストの一覧を取る（P7）。接続先は到達確認と同じ順で試し、最初に P7 に応えた接続先を使う。
 * E2、E4、500 を受けたら、その接続先で止める（PC には届いているため）。何も書かない。
 */
class PlaylistCatalog(private val env: SyncEnvironment) {
    suspend fun fetch(): CatalogResult {
        val pairing = env.store.pairing() ?: return CatalogResult.Other
        if (!env.aliasExists(pairing.keyAlias)) return CatalogResult.KeyMismatch

        var mismatch = false
        for (kind in endpointOrder(pairing)) {
            val endpoint = env.endpointFor(pairing, kind) ?: continue
            val api = env.apis.open(endpoint)
            try {
                when (val result = api.playlists()) {
                    is ApiResult.Ok -> return readPlaylistTree(result.value)?.let { CatalogResult.Loaded(it) } ?: CatalogResult.Other
                    is ApiResult.Fail -> when (result.failure) {
                        ApiFailure.Disconnected -> Unit
                        ApiFailure.PinMismatch -> mismatch = true
                        is ApiFailure.Rejected -> return if (result.failure.keyMismatch) CatalogResult.KeyMismatch else CatalogResult.Other
                        ApiFailure.XmlUnavailable -> return CatalogResult.XmlUnavailable
                        else -> return CatalogResult.Other
                    }
                }
            } finally {
                runCatching { api.close() }
            }
        }
        return if (mismatch) CatalogResult.KeyMismatch else CatalogResult.Unreachable
    }
}
