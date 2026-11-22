package playlistsyncfox.android.db

import kotlinx.coroutines.flow.Flow
import playlistsyncfox.android.sync.SyncValues

/** X6-1 の「アクセスの単位」。同期の手順と MediaWriter はここを通して T1〜T9 を読み書きする。 */
interface SyncStore {
    suspend fun placedTracks(deviceId: String): List<PlacedTrack>

    /** 置き換えの開始などで T1 の行だけを書く。 */
    suspend fun savePlaced(row: PlacedTrack)

    /** 曲の完了の記録。T1 の追加または更新と T5 の削除を 1 取引で行う。 */
    suspend fun recordCompleted(row: PlacedTrack)

    suspend fun removePlaced(deviceId: String, persistentId: String)

    suspend fun pending(persistentId: String): PendingTransfer?

    suspend fun savePending(row: PendingTransfer)

    suspend fun deletePending(persistentId: String)

    /** [startedBefore] より前に始めた T5 の行（期限切れ）。消すのは呼び出し側（URI の削除の後）。 */
    suspend fun expiredPending(startedBefore: Long): List<PendingTransfer>

    suspend fun playlists(deviceId: String): List<WrittenPlaylist>

    suspend fun savePlaylist(row: WrittenPlaylist)

    suspend fun deletePlaylist(deviceId: String, playlistId: String)

    /** 新しい順（`started_at`、同じなら `run_id`）。V26 件までしか残らない。 */
    suspend fun runs(): List<SyncRun>

    suspend fun startRun(row: SyncRun): Long

    /** 実行の更新。終わった行なら V26 の超過分を T6 とともに消す。 */
    suspend fun finishRun(row: SyncRun)

    suspend fun addIssues(rows: List<RunIssue>)

    suspend fun issues(runId: Long): List<RunIssue>

    suspend fun pairing(): Pairing?

    suspend fun updatePairing(row: Pairing)

    /** ペアリングの完了で T4 の行を作る（X6-6）。 */
    suspend fun savePairing(row: Pairing)

    /** 解除。T1、T2 は残す。 */
    suspend fun deletePairing(deviceId: String)

    // 画面の購読（X7-1-1 の 2）。書き込みの完了で自動に流れる
    fun observePairing(): Flow<Pairing?>

    fun observeRuns(): Flow<List<SyncRun>>

    fun observePlaylists(): Flow<List<WrittenPlaylist>>

    /** T8。選んだ順。 */
    suspend fun selectedPlaylists(): List<SelectedPlaylist>

    fun observeSelectedPlaylists(): Flow<List<SelectedPlaylist>>

    /** T8 の全置き換え（1 取引）。 */
    suspend fun replaceSelection(rows: List<SelectedPlaylist>)

    /** 保存先の切り替え。T1、T2 を T9 へ移し、T5 を消し、`placed_volume` を [volume] にする（1 取引）。 */
    suspend fun relocate(deviceId: String, volume: String)

    /** T9：切り替え前の場所に残る自分のファイルの URI。 */
    suspend fun relocatedUris(): List<String>

    suspend fun removeRelocated(mediaUri: String)

    suspend fun setting(key: String): String?

    suspend fun putSetting(key: String, value: String)
}

class RoomSyncStore(private val dao: SyncDao) : SyncStore {
    override suspend fun placedTracks(deviceId: String) = dao.placedTracks(deviceId)

    override suspend fun savePlaced(row: PlacedTrack) = dao.upsertPlaced(row)

    override suspend fun recordCompleted(row: PlacedTrack) = dao.completeTrack(row)

    override suspend fun removePlaced(deviceId: String, persistentId: String) = dao.deletePlaced(deviceId, persistentId)

    override suspend fun pending(persistentId: String) = dao.pending(persistentId)

    override suspend fun savePending(row: PendingTransfer) = dao.upsertPending(row)

    override suspend fun deletePending(persistentId: String) = dao.deletePending(persistentId)

    override suspend fun expiredPending(startedBefore: Long) = dao.pendingStartedBefore(startedBefore)

    override suspend fun playlists(deviceId: String) = dao.playlists(deviceId)

    override suspend fun savePlaylist(row: WrittenPlaylist) = dao.upsertPlaylist(row)

    override suspend fun deletePlaylist(deviceId: String, playlistId: String) = dao.deletePlaylist(deviceId, playlistId)

    override suspend fun runs() = dao.runs()

    override suspend fun startRun(row: SyncRun) = dao.insertRun(row)

    override suspend fun finishRun(row: SyncRun) = dao.finishRun(row, SyncValues.RUN_HISTORY_LIMIT)

    override suspend fun addIssues(rows: List<RunIssue>) = dao.insertIssues(rows)

    override suspend fun issues(runId: Long) = dao.issues(runId)

    override suspend fun pairing() = dao.pairing()

    override suspend fun updatePairing(row: Pairing) = dao.updatePairing(row)

    override suspend fun savePairing(row: Pairing) = dao.upsertPairing(row)

    override suspend fun deletePairing(deviceId: String) = dao.deletePairing(deviceId)

    override fun observePairing() = dao.observePairing()

    override fun observeRuns() = dao.observeRuns()

    override fun observePlaylists() = dao.observePlaylists()

    override suspend fun selectedPlaylists() = dao.selectedPlaylists()

    override fun observeSelectedPlaylists() = dao.observeSelectedPlaylists()

    override suspend fun replaceSelection(rows: List<SelectedPlaylist>) = dao.replaceSelection(rows)

    override suspend fun relocate(deviceId: String, volume: String) = dao.relocate(deviceId, volume)

    override suspend fun relocatedUris() = dao.relocatedFiles().map { it.mediaUri }

    override suspend fun removeRelocated(mediaUri: String) = dao.deleteRelocated(mediaUri)

    override suspend fun setting(key: String) = dao.setting(key)

    override suspend fun putSetting(key: String, value: String) = dao.putSetting(AppSetting(key, value))
}
