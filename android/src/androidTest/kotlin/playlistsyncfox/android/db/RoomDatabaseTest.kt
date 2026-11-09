package playlistsyncfox.android.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.result.Capacity
import playlistsyncfox.shared.result.Counts
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncResult
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

// 端末の実行環境が要る薄い統合の試験（TA-001〜003、TA-036〜038）。CI の対象外で、束 5 の後に端末かエミュレーターで 1 回回す。
// 試料はすべて合成。

private const val DEVICE = "00000000-0000-4000-8000-000000000001"

private fun placed(n: Int) = PlacedTrack(DEVICE, "%016X".format(n), "A/B/$n.mp3", "content://media/test/$n", 10L * n, 1L, 10L * n, PlacedTrack.STATE_PLACED, 5L)

private fun pending(n: Int) = PendingTransfer("%016X".format(n), "content://media/test/p$n", 100, 40, 1, 5)

@RunWith(AndroidJUnit4::class)
class RoomDatabaseTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private lateinit var db: AppDatabase
    private val dao get() = db.syncDao()

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun close() = db.close()

    @Test
    fun `TA-001 旧スキーマの DB を現行の版で開いても T1 と T5 の行が同じ値で残る`() {
        val name = "migration-test"
        migrations.createDatabase(name, 1).apply {
            execSQL("INSERT INTO placed_tracks VALUES ('$DEVICE','0000000000000001','A/B/1.mp3','content://m/1',10,1,10,'placed',5)")
            execSQL("INSERT INTO placed_tracks VALUES ('$DEVICE','0000000000000002','A/B/2.mp3','content://m/2',20,1,20,'placed',5)")
            execSQL("INSERT INTO placed_tracks VALUES ('$DEVICE','0000000000000003','A/B/3.mp3','content://m/3',30,1,30,'replacing',5)")
            execSQL("INSERT INTO pending_transfers VALUES ('0000000000000009','content://m/p',100,40,1,5)")
            close()
        }
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        // 破壊的な再作成は指定しない。版を上げるときは Migration を渡す
        val reopened = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        try {
            runBlocking {
                assertEquals(3, reopened.syncDao().placedTracks(DEVICE).size)
                assertEquals("replacing", reopened.syncDao().placedTracks(DEVICE).first { it.persistentId.endsWith("3") }.state)
                assertEquals(40, reopened.syncDao().pending("0000000000000009")?.receivedBytes)
                // 版 1 → 2 で足した T8、T9 は空で作られる（TA-225）
                assertEquals(0, reopened.syncDao().selectedPlaylists().size)
                assertEquals(0, reopened.syncDao().relocatedFiles().size)
            }
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `TA-002 T1 の追加と T5 の削除は 1 取引で途中で失敗すると T1 は増えず T5 は残る`() = runBlocking {
        dao.upsertPending(pending(1))

        assertFailsWith<IllegalStateException> {
            db.withTransaction {
                dao.completeTrack(placed(1))
                throw IllegalStateException("取引の途中の失敗")
            }
        }

        assertEquals(0, dao.placedTracks(DEVICE).size)
        assertNotNull(dao.pending("%016X".format(1)))
    }

    @Test
    fun `TA-002 完了の記録は T1 を追加して同じ曲の T5 を消す`() = runBlocking {
        dao.upsertPending(pending(1))
        dao.upsertPending(pending(2))

        dao.completeTrack(placed(1))

        assertEquals(1, dao.placedTracks(DEVICE).size)
        assertNull(dao.pending("%016X".format(1)))
        assertNotNull(dao.pending("%016X".format(2)))
    }

    @Test
    fun `TA-003 T3 が V26 を超えると超過した実行とその T6 だけが消える`() = runBlocking {
        val ids = (1..102).map { n ->
            val id = dao.insertRun(SyncRun(deviceId = DEVICE, trigger = SyncRun.TRIGGER_MANUAL, startedAt = n.toLong(), endedAt = n.toLong()))
            dao.insertIssues(listOf(RunIssue(runId = id, title = "曲 $n", kind = RunIssue.KIND_FAILED, errorKind = "E5")))
            id
        }

        dao.finishRun(dao.runs().first(), keep = 100)

        assertEquals(100, dao.runs().size)
        assertEquals(emptyList(), dao.issues(ids[0]))
        assertEquals(emptyList(), dao.issues(ids[1]))
        assertEquals(1, dao.issues(ids[2]).size)
        assertEquals(1, dao.issues(ids[101]).size)
    }

    @Test
    fun `TA-036 SyncResult の全体は result_json に往復し library_id と content_digest は検索条件に使える`() = runBlocking {
        val result = SyncResult(
            startedAt = "2026-11-07T00:00:00.000Z", finishedAt = "2026-11-07T00:01:00.000Z", trigger = SyncTrigger.Manual,
            transport = Transport.Lan, outcome = SyncOutcome.Completed, hasMore = false,
            counts = Counts(1, 2, 3, 4, 1, 2, 3, 0), capacity = Capacity(10, 5, 100, null), errors = emptyList(),
            excluded = emptyList(), unplayable = emptyList(), libraryId = "LIB", contentDigest = "DIG",
            unresolvedPlaylists = listOf("未解決"), duplicatesDropped = 0, removalSkipped = true, keyMismatch = null, errorDetail = null,
        )
        val id = dao.insertRun(
            SyncRun(deviceId = DEVICE, trigger = SyncRun.TRIGGER_AUTO, startedAt = 1, endedAt = 2, result = SyncRun.RESULT_SUCCESS,
                libraryId = "LIB", contentDigest = "DIG", resultJson = encodeResult(result)),
        )

        assertEquals(result, decodeResult(dao.runs().single { it.runId == id }.resultJson!!))
        db.openHelper.readableDatabase.query("SELECT run_id FROM sync_runs WHERE library_id = 'LIB' AND content_digest = 'DIG'").use {
            assertEquals(1, it.count)
        }
    }

    @Test
    fun `TA-037 T6 は連番の主キーで同じ曲の別の reason と ID 無しの除外が入り 3 列の値が往復する`() = runBlocking {
        val run = dao.insertRun(SyncRun(deviceId = DEVICE, trigger = SyncRun.TRIGGER_MANUAL, startedAt = 1))
        val rows = listOf(
            RunIssue(runId = run, persistentId = "0000000000000001", title = "曲", artist = "歌手", reason = "protected", format = "m4p", kind = RunIssue.KIND_UNPLAYABLE),
            RunIssue(runId = run, persistentId = "0000000000000001", title = "曲", kind = RunIssue.KIND_FAILED, errorKind = "E5"),
            RunIssue(runId = run, persistentId = null, title = "名前だけ", artist = "歌手", reason = "no_id", kind = RunIssue.KIND_EXCLUDED),
        )

        dao.insertIssues(rows)

        val saved = dao.issues(run)
        assertEquals(3, saved.size)
        assertEquals(3, saved.map { it.issueId }.toSet().size)
        assertEquals(rows.map { Triple(it.artist, it.reason, it.format) }, saved.map { Triple(it.artist, it.reason, it.format) })
    }

    @Test
    fun `TA-038 last_endpoint は成功した方式に更新され解除で T4 は消えても T7 の device_id は残る`() = runBlocking {
        dao.putSetting(AppSetting(SyncValues.DEVICE_ID_KEY, DEVICE))
        val pairing = Pairing(DEVICE, "pc", "pc.example.invalid", "psfox-01234567", null, null, null, "00".repeat(32), "alias", 1)
        dao.upsertPairing(pairing)

        for (kind in listOf("lan", "tailscale", "usb")) {
            dao.updatePairing(dao.pairing()!!.copy(lastEndpoint = kind))
            assertEquals(kind, dao.pairing()?.lastEndpoint)
        }
        dao.deletePairing(DEVICE)

        assertNull(dao.pairing())
        assertEquals(DEVICE, dao.setting(SyncValues.DEVICE_ID_KEY))
    }
}
