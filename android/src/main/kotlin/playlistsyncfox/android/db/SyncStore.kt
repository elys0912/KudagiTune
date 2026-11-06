package playlistsyncfox.android.db

import playlistsyncfox.android.sync.SyncValues

/** X6-1 の「アクセスの単位」。同期の手順と MediaWriter はここを通して T1〜T7 を読み書きする。 */
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

    override suspend fun setting(key: String) = dao.setting(key)

    override suspend fun putSetting(key: String, value: String) = dao.putSetting(AppSetting(key, value))
}
