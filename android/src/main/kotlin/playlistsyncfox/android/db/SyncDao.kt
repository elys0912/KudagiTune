package playlistsyncfox.android.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
abstract class SyncDao {
    @Query("SELECT * FROM placed_tracks WHERE device_id = :deviceId")
    abstract suspend fun placedTracks(deviceId: String): List<PlacedTrack>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPlaced(row: PlacedTrack)

    @Query("DELETE FROM placed_tracks WHERE device_id = :deviceId AND persistent_id = :persistentId")
    abstract suspend fun deletePlaced(deviceId: String, persistentId: String)

    @Query("SELECT * FROM pending_transfers WHERE persistent_id = :persistentId")
    abstract suspend fun pending(persistentId: String): PendingTransfer?

    @Query("SELECT * FROM pending_transfers WHERE started_at < :cutoff")
    abstract suspend fun pendingStartedBefore(cutoff: Long): List<PendingTransfer>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPending(row: PendingTransfer)

    @Query("DELETE FROM pending_transfers WHERE persistent_id = :persistentId")
    abstract suspend fun deletePending(persistentId: String)

    /** 曲の完了の記録。T1 の追加と T5 の削除を 1 取引にする。 */
    @Transaction
    open suspend fun completeTrack(row: PlacedTrack) {
        upsertPlaced(row)
        deletePending(row.persistentId)
    }

    @Query("SELECT * FROM playlists WHERE device_id = :deviceId")
    abstract suspend fun playlists(deviceId: String): List<WrittenPlaylist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPlaylist(row: WrittenPlaylist)

    @Query("DELETE FROM playlists WHERE device_id = :deviceId AND playlist_id = :playlistId")
    abstract suspend fun deletePlaylist(deviceId: String, playlistId: String)

    @Query("SELECT * FROM sync_runs ORDER BY started_at DESC, run_id DESC")
    abstract suspend fun runs(): List<SyncRun>

    @Insert
    abstract suspend fun insertRun(row: SyncRun): Long

    @Update
    abstract suspend fun updateRun(row: SyncRun)

    @Query(
        "DELETE FROM sync_runs WHERE run_id NOT IN " +
            "(SELECT run_id FROM sync_runs ORDER BY started_at DESC, run_id DESC LIMIT :keep)",
    )
    abstract suspend fun pruneRuns(keep: Int)

    /** 実行の終了。V26 の超過分を T6 とともに同じ取引で消す。 */
    @Transaction
    open suspend fun finishRun(row: SyncRun, keep: Int) {
        updateRun(row)
        pruneRuns(keep)
    }

    @Insert
    abstract suspend fun insertIssues(rows: List<RunIssue>)

    @Query("SELECT * FROM run_issues WHERE run_id = :runId ORDER BY issue_id")
    abstract suspend fun issues(runId: Long): List<RunIssue>

    @Query("SELECT * FROM pairing LIMIT 1")
    abstract suspend fun pairing(): Pairing?

    @Update
    abstract suspend fun updatePairing(row: Pairing)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertPairing(row: Pairing)

    @Query("DELETE FROM pairing WHERE device_id = :deviceId")
    abstract suspend fun deletePairing(deviceId: String)

    @Query("SELECT * FROM pairing LIMIT 1")
    abstract fun observePairing(): Flow<Pairing?>

    @Query("SELECT * FROM sync_runs ORDER BY started_at DESC, run_id DESC")
    abstract fun observeRuns(): Flow<List<SyncRun>>

    @Query("SELECT * FROM playlists")
    abstract fun observePlaylists(): Flow<List<WrittenPlaylist>>

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    abstract suspend fun setting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSetting(row: AppSetting)
}

/**
 * 版は 1。版を上げるときは手書きの `Migration` を書く（破壊的な再作成は使わない。X6-1）。
 * スキーマの JSON は `android/schemas` に書き出す。
 */
@Database(
    entities = [
        PlacedTrack::class,
        WrittenPlaylist::class,
        SyncRun::class,
        Pairing::class,
        PendingTransfer::class,
        RunIssue::class,
        AppSetting::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
}
