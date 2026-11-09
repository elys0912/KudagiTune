package playlistsyncfox.android.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import playlistsyncfox.android.sync.SyncValues

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

    @Query("SELECT * FROM selected_playlists ORDER BY selected_at, playlist_id")
    abstract suspend fun selectedPlaylists(): List<SelectedPlaylist>

    @Query("SELECT * FROM selected_playlists ORDER BY selected_at, playlist_id")
    abstract fun observeSelectedPlaylists(): Flow<List<SelectedPlaylist>>

    @Query("DELETE FROM selected_playlists")
    abstract suspend fun clearSelection()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertSelection(rows: List<SelectedPlaylist>)

    /** 選択は全置き換え（1 取引）。 */
    @Transaction
    open suspend fun replaceSelection(rows: List<SelectedPlaylist>) {
        clearSelection()
        insertSelection(rows)
    }

    @Query("SELECT * FROM relocated_files")
    abstract suspend fun relocatedFiles(): List<RelocatedFile>

    @Query("DELETE FROM relocated_files WHERE media_uri = :mediaUri")
    abstract suspend fun deleteRelocated(mediaUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertRelocated(rows: List<RelocatedFile>)

    @Query("DELETE FROM placed_tracks WHERE device_id = :deviceId")
    abstract suspend fun deleteAllPlaced(deviceId: String)

    @Query("DELETE FROM playlists WHERE device_id = :deviceId")
    abstract suspend fun deleteAllPlaylists(deviceId: String)

    @Query("DELETE FROM pending_transfers")
    abstract suspend fun deleteAllPending()

    /** 保存先の切り替え。T1、T2 の行を T9 へ移し、T5 を消し、`placed_volume` を新しい値にする（1 取引）。 */
    @Transaction
    open suspend fun relocate(deviceId: String, volume: String) {
        insertRelocated(
            placedTracks(deviceId).map { RelocatedFile(it.mediaUri) } + playlists(deviceId).map { RelocatedFile(it.mediaUri) },
        )
        deleteAllPlaced(deviceId)
        deleteAllPlaylists(deviceId)
        deleteAllPending()
        putSetting(AppSetting(SyncValues.PLACED_VOLUME_KEY, volume))
        putSetting(AppSetting(SyncValues.RELOCATING_KEY, SyncValues.SETTING_ON))
    }

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    abstract suspend fun setting(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSetting(row: AppSetting)
}

/**
 * 版は 2。版を上げるときは手書きの `Migration` を書く（破壊的な再作成は使わない。X6-1）。
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
        SelectedPlaylist::class,
        RelocatedFile::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncDao(): SyncDao
}

/** 版 1 → 2：T8、T9 を足す（D15、D16）。既存の表は変えない。 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `selected_playlists` (`playlist_id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                "`selected_at` INTEGER NOT NULL, PRIMARY KEY(`playlist_id`))",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS `relocated_files` (`media_uri` TEXT NOT NULL, PRIMARY KEY(`media_uri`))")
    }
}
