package playlistsyncfox.android.sync

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.Harness
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.manifest.writePlaylistTree
import playlistsyncfox.shared.result.ErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals

private val TREE = listOf(PlaylistNode("A000000000000001", "A", trackCount = 2))

class PlaylistCatalogTest {
    private fun fetch(h: Harness) = PlaylistCatalog(h.environment())

    @Test
    fun `TA-221 応えない接続先は飛ばして次の接続先の一覧を使う`() = runTest {
        val h = Harness()
        h.lanApi.playlistsResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.playlistsResult = ApiResult.Ok(writePlaylistTree(TREE))

        assertEquals(CatalogResult.Loaded(TREE), fetch(h).fetch())
    }

    @Test
    fun `TA-221 全部の接続先が応えなければ届かない、固定の不一致が混じれば鍵の不一致`() = runTest {
        val h = Harness()
        for (api in listOf(h.lanApi, h.tailApi)) api.playlistsResult = ApiResult.Fail(ApiFailure.Disconnected)
        assertEquals(CatalogResult.Unreachable, fetch(h).fetch())

        h.tailApi.playlistsResult = ApiResult.Fail(ApiFailure.PinMismatch)
        assertEquals(CatalogResult.KeyMismatch, fetch(h).fetch())
    }

    @Test
    fun `TA-221 届いた接続先の拒否と XML 不可はそこで止めて次を試さない`() = runTest {
        val h = Harness()
        h.lanApi.playlistsResult = ApiResult.Fail(ApiFailure.XmlUnavailable)
        h.tailApi.playlistsResult = ApiResult.Ok(writePlaylistTree(TREE))
        assertEquals(CatalogResult.XmlUnavailable, fetch(h).fetch())

        h.lanApi.playlistsResult = ApiResult.Fail(ApiFailure.Rejected(ErrorDetail.NoDetail, keyMismatch = true))
        assertEquals(CatalogResult.KeyMismatch, fetch(h).fetch())
    }

    @Test
    fun `TA-221 壊れた一覧は読めなかったとして扱う`() = runTest {
        val h = Harness()
        h.lanApi.playlistsResult = ApiResult.Ok("not json")

        assertEquals(CatalogResult.Other, fetch(h).fetch())
    }
}
