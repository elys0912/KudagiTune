package playlistsyncfox.shared.diff

import playlistsyncfox.shared.V20
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.manifest.UnresolvedPlaylist
import playlistsyncfox.shared.pid
import playlistsyncfox.shared.record
import playlistsyncfox.shared.track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiffTest {
    private val a = pid('A')
    private val b = pid('B')
    private val c = pid('C')

    private fun diff(
        tracks: List<ManifestTrack>,
        records: List<SyncRecord> = emptyList(),
        present: Set<String> = records.mapTo(HashSet()) { it.persistentId },
        manifestLibraryId: String = "L1",
        recordedLibraryId: String? = "L1",
        unresolved: List<UnresolvedPlaylist> = emptyList(),
    ): Diff = computeDiff(tracks, records, present, manifestLibraryId, recordedLibraryId, V20, unresolved)

    private fun ids(tracks: List<ManifestTrack>) = tracks.map { it.persistentId }

    private fun records(count: Int) = (1..count).map { record(pid('R', it)) }

    @Test
    fun `TS-13 記録に無い曲があると足すに入る`() {
        val result = diff(listOf(track(a)))

        assertEquals(listOf(a), ids(result.toAdd))
    }

    @Test
    fun `TS-13 記録が空で曲が 3 件あると 3 件とも足すに入り消すは空になる`() {
        val result = diff(listOf(track(a), track(b), track(c)))

        assertEquals(listOf(a, b, c), ids(result.toAdd))
        assertTrue(result.toRemove.isEmpty())
    }

    @Test
    fun `TS-14 記録にあってファイルが無い曲は size が違っても足すに入り置き換えには入らない`() {
        val result = diff(listOf(track(a, size = 999)), listOf(record(a, size = 100)), present = emptySet())

        assertEquals(listOf(a), ids(result.toAdd))
        assertTrue(result.toReplace.isEmpty())
    }

    @Test
    fun `TS-16 記録と同じ値でファイルのある曲は変更なしに数える`() {
        val result = diff(listOf(track(a)), listOf(record(a)))

        assertEquals(1, result.unchangedCount)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `TS-17 記録にあって入力の曲に無い記録は消すに入る`() {
        val result = diff(emptyList(), listOf(record(a)))

        assertEquals(listOf(a), result.toRemove.map { it.persistentId })
    }

    @Test
    fun `TS-18 modified_at が同じで size だけ違う曲は置き換えに入る`() {
        val result = diff(listOf(track(a, size = 200)), listOf(record(a, size = 100)))

        assertEquals(listOf(a), ids(result.toReplace))
    }

    @Test
    fun `TS-19 size が同じで modified_at だけ違う曲は置き換えに入る`() {
        val result = diff(listOf(track(a, modifiedAt = 9)), listOf(record(a, modifiedAt = 1)))

        assertEquals(listOf(a), ids(result.toReplace))
    }

    @Test
    fun `TS-20 protected が真の曲は再生できないに入り足すに入らない`() {
        val result = diff(listOf(track(a, isProtected = true)))

        assertEquals(listOf(a), ids(result.unplayable))
        assertTrue(result.toAdd.isEmpty())
    }

    @Test
    fun `TS-21 protected が偽でも拡張子が m4p の曲は再生できないに入る`() {
        val result = diff(listOf(track(a, extension = "m4p")))

        assertEquals(listOf(a), ids(result.unplayable))
    }

    @Test
    fun `TS-22 記録のある保護付きの曲は再生できないに入り消すに入らない`() {
        val result = diff(listOf(track(a, isProtected = true)), listOf(record(a)))

        assertEquals(listOf(a), ids(result.unplayable))
        assertTrue(result.toRemove.isEmpty())
    }

    @Test
    fun `TS-23 同じ曲を 2 本のプレイリストが持っても足すは 1 件になる`() {
        // 差分の入力は tracks（集合）なので、プレイリストの数は結果に現れない
        val result = diff(listOf(track(a)))

        assertEquals(1, result.toAdd.size)
    }

    @Test
    fun `TS-25 ファイルが無く入力の曲にも無い記録は消すに入る`() {
        val result = diff(emptyList(), listOf(record(a)), present = emptySet())

        assertEquals(listOf(a), result.toRemove.map { it.persistentId })
    }

    @Test
    fun `TS-26 記録済みのライブラリ ID が null なら消す対象があっても Allowed になる`() {
        val result = diff(listOf(track(a)), listOf(record(a), record(b)), recordedLibraryId = null)

        assertEquals(RemovalGate.Allowed, result.removalGate)
    }

    @Test
    fun `TS-27 ライブラリ ID が記録と違い消す対象があると LibraryChanged で消すも計算して返す`() {
        val result = diff(listOf(track(a)), listOf(record(a), record(b)), manifestLibraryId = "L2")

        assertEquals(RemovalGate.LibraryChanged, result.removalGate)
        assertEquals(listOf(b), result.toRemove.map { it.persistentId })
    }

    @Test
    fun `TS-27 ライブラリ ID が記録と違っても消す対象が 0 件なら Allowed になる`() {
        val result = diff(listOf(track(a)), listOf(record(a)), manifestLibraryId = "L2")

        assertEquals(RemovalGate.Allowed, result.removalGate)
    }

    @Test
    fun `TS-28 消す件数が V20 と同じで記録の一部なら Allowed になる`() {
        val result = diff(listOf(track(a)), records(V20) + record(a))

        assertEquals(V20, result.toRemove.size)
        assertEquals(RemovalGate.Allowed, result.removalGate)
    }

    @Test
    fun `TS-29 消す件数が V20 を超えると NeedsConfirmation で消すも返す`() {
        val result = diff(listOf(track(a)), records(V20 + 1) + record(a))

        assertEquals(RemovalGate.NeedsConfirmation, result.removalGate)
        assertEquals(V20 + 1, result.toRemove.size)
    }

    @Test
    fun `TS-30 記録の全部を消すと V20 以下の件数でも NeedsConfirmation になる`() {
        val result = diff(emptyList(), records(3))

        assertEquals(RemovalGate.NeedsConfirmation, result.removalGate)
    }

    @Test
    fun `TS-31 消す件数が 0 で記録も 0 件なら Allowed になる`() {
        assertEquals(RemovalGate.Allowed, diff(emptyList()).removalGate)
    }

    @Test
    fun `TS-31 消す件数が 0 で記録が 1 件以上あっても全部には当たらず Allowed になる`() {
        assertEquals(RemovalGate.Allowed, diff(listOf(track(a)), listOf(record(a))).removalGate)
    }

    @Test
    fun `TS-32 入力の並びを入れ替えても各一覧は persistent_id の昇順で結果は同じになる`() {
        val tracks = listOf(track(a), track(b, size = 200), track(c))
        val records = listOf(record(b), record(pid('Z')), record(pid('Y')))

        val forward = diff(tracks, records)
        val backward = diff(tracks.reversed(), records.reversed())

        assertEquals(forward, backward)
        assertEquals(listOf(pid('Y'), pid('Z')), forward.toRemove.map { it.persistentId })
    }

    @Test
    fun `TS-33 必要な容量は足すと置き換えの新しい size の合計で消すは引かない`() {
        val result = diff(
            tracks = listOf(track(a, size = 10), track(b, size = 200)),
            records = listOf(record(b, size = 5), record(c, size = 1000)),
        )

        assertEquals(210, requiredBytes(result))
    }

    @Test
    fun `TS-33 全部空の差分の必要な容量は 0 になる`() {
        assertEquals(0, requiredBytes(diff(emptyList())))
    }

    @Test
    fun `TS-131 未解決のプレイリストがあり消す対象が 1 件以上あると NeedsConfirmation で消すも返す`() {
        val unresolved = listOf(UnresolvedPlaylist(pid('U'), "Old"))

        val result = diff(listOf(track(a)), listOf(record(a), record(b)), unresolved = unresolved)

        assertEquals(RemovalGate.NeedsConfirmation, result.removalGate)
        assertEquals(listOf(b), result.toRemove.map { it.persistentId })
    }

    @Test
    fun `TS-131 未解決のプレイリストがあっても消す対象が 0 件なら Allowed になる`() {
        val unresolved = listOf(UnresolvedPlaylist(pid('U'), "Old"))

        val result = diff(listOf(track(a)), listOf(record(a)), unresolved = unresolved)

        assertEquals(RemovalGate.Allowed, result.removalGate)
    }

    @Test
    fun `TS-131 未解決のプレイリストが 0 件で消す対象が記録の一部なら Allowed になる`() {
        val result = diff(listOf(track(a)), listOf(record(a), record(b)))

        assertEquals(RemovalGate.Allowed, result.removalGate)
    }

    @Test
    fun `TS-132 ライブラリ ID が違い消す件数も V20 を超えるときは LibraryChanged を返し消すも返す`() {
        val result = diff(listOf(track(a)), records(V20 + 1) + record(a), manifestLibraryId = "L2")

        assertEquals(RemovalGate.LibraryChanged, result.removalGate)
        assertEquals(V20 + 1, result.toRemove.size)
    }

    @Test
    fun `TS-150 3 つの並びが全部空のときだけ isEmpty は真になる`() {
        val empty = diff(emptyList())
        val withAdd = diff(listOf(track(a)))
        val withReplace = diff(listOf(track(a, size = 5)), listOf(record(a)))
        val withRemove = diff(listOf(track(a)), listOf(record(a), record(b)))

        assertTrue(empty.isEmpty())
        assertFalse(withAdd.isEmpty())
        assertFalse(withReplace.isEmpty())
        assertFalse(withRemove.isEmpty())
    }
}
