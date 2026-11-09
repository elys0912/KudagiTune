package playlistsyncfox.android.ui

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.db.SelectedPlaylist
import playlistsyncfox.android.sync.CatalogResult
import playlistsyncfox.shared.manifest.PlaylistNode
import playlistsyncfox.shared.result.ErrorDetail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val FOLDER = "F000000000000001"
private const val LEAF_A = "A000000000000001"
private const val LEAF_B = "B000000000000002"
private const val LEAF_C = "C000000000000003"
private const val GONE = "D000000000000004"

class SelectionTest {
    private val tree = listOf(
        PlaylistNode(FOLDER, "フォルダ", children = listOf(PlaylistNode(LEAF_A, "A", trackCount = 1), PlaylistNode(LEAF_B, "B", trackCount = 2))),
        PlaylistNode(LEAF_C, "C", trackCount = 3),
    )

    private fun TestScope.presenter(store: InMemoryStore, result: CatalogResult = CatalogResult.Loaded(tree)) =
        SelectionPresenter(backgroundScope, store, { result }, now = { 100 }).also {
            it.load()
            runCurrent()
        }

    private fun loaded(presenter: SelectionPresenter) = assertIs<SelectionStage.Loaded>(presenter.stage.value)

    @Test
    fun `TA-221 チェックを変えると即座に保存され、フォルダは配下の葉だけを保存する`() = runTest {
        val store = InMemoryStore()
        val p = presenter(store)

        p.setLeaf(LEAF_C, true)
        runCurrent()
        p.setFolder(tree[0], true)
        runCurrent()

        assertEquals(listOf(LEAF_C, LEAF_A, LEAF_B), store.selected.map { it.playlistId })
        assertEquals(listOf("C", "A", "B"), store.selected.map { it.name })
        assertEquals(store.selected.map { it.playlistId }, loaded(p).selectedIds)
    }

    @Test
    fun `TA-221 一覧に無い選択は保存した名前で見つからないに残り、外すまで消えない`() = runTest {
        val store = InMemoryStore().also { it.selected = listOf(SelectedPlaylist(GONE, "消えた一覧", 1), SelectedPlaylist(LEAF_A, "A", 2)) }
        val p = presenter(store)

        assertEquals(listOf("消えた一覧"), loaded(p).missing.map { it.name })
        p.setLeaf(LEAF_B, true)
        runCurrent()
        assertEquals(listOf(GONE, LEAF_A, LEAF_B), store.selected.map { it.playlistId })

        p.remove(GONE)
        runCurrent()
        assertEquals(listOf(LEAF_A, LEAF_B), store.selected.map { it.playlistId })
        assertEquals(emptyList(), loaded(p).missing)
    }

    @Test
    fun `TA-221 一覧を取れなくても保存済みの選択は変わらず、外すだけできる`() = runTest {
        val store = InMemoryStore().also { it.selected = listOf(SelectedPlaylist(LEAF_A, "A", 1), SelectedPlaylist(LEAF_B, "B", 2)) }
        val p = presenter(store, CatalogResult.Unreachable)

        val failed = assertIs<SelectionStage.Failed>(p.stage.value)
        assertEquals(CatalogResult.Unreachable, failed.reason)
        assertEquals(listOf(LEAF_A, LEAF_B), store.selected.map { it.playlistId })

        p.remove(LEAF_A)
        runCurrent()
        assertEquals(listOf(LEAF_B), store.selected.map { it.playlistId })
    }

    @Test
    fun `TA-221 フォルダを外すと配下の葉だけが外れ他の選択は残る`() = runTest {
        val store = InMemoryStore().also {
            it.selected = listOf(SelectedPlaylist(LEAF_A, "A", 1), SelectedPlaylist(LEAF_B, "B", 2), SelectedPlaylist(LEAF_C, "C", 3))
        }
        val p = presenter(store)

        p.setFolder(tree[0], false)
        runCurrent()

        assertEquals(listOf(LEAF_C), store.selected.map { it.playlistId })
    }

    @Test
    fun `X6-13 取得が E2 で断られたときの語と案内は S3 と同じ noticeOf で、ほかの失敗には案内を足さない`() {
        val clockSkew = CatalogResult.Rejected(ErrorDetail.ClockSkew)
        val rateLimited = CatalogResult.Rejected(ErrorDetail.RateLimited)

        assertEquals(noticeOf(ErrorDetail.ClockSkew).title, catalogWord(clockSkew))
        assertEquals(noticeOf(ErrorDetail.ClockSkew).guidance, catalogGuidance(clockSkew))
        assertEquals(noticeOf(ErrorDetail.RateLimited).title, catalogWord(rateLimited))
        assertEquals("PC に接続できません", catalogWord(CatalogResult.Unreachable))
        assertNull(catalogGuidance(CatalogResult.Unreachable))
        assertNull(catalogGuidance(CatalogResult.KeyMismatch))
    }
}
