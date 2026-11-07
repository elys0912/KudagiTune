package playlistsyncfox.android.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// T1〜T7（X6-1）。日時は UTC のエポックのミリ秒。
// 列挙の text（state、result、trigger、kind）は文字列のまま持つ。未知の語は読むだけで書き換えない。

/** T1 置いた曲。この表の行が「自分が置いた」の定義。 */
@Entity(
    tableName = "placed_tracks",
    primaryKeys = ["device_id", "persistent_id"],
    indices = [Index(value = ["device_id", "relative_path"], unique = true)],
)
data class PlacedTrack(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "persistent_id") val persistentId: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "source_size") val sourceSize: Long,
    @ColumnInfo(name = "source_modified") val sourceModified: Long,
    @ColumnInfo(name = "local_size") val localSize: Long,
    val state: String,
    @ColumnInfo(name = "placed_at") val placedAt: Long,
) {
    companion object {
        const val STATE_PLACED = "placed"
        const val STATE_REPLACING = "replacing"
    }
}

/** T2 書いたプレイリスト。 */
@Entity(
    tableName = "playlists",
    primaryKeys = ["device_id", "playlist_id"],
    indices = [Index(value = ["device_id", "relative_path"], unique = true)],
)
data class WrittenPlaylist(
    @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "track_count") val trackCount: Int,
    @ColumnInfo(name = "written_at") val writtenAt: Long,
)

/** T3 同期の実行の記録。実行中の行は `result`＝failed で作り、`ended_at` が NULL。 */
@Entity(tableName = "sync_runs", indices = [Index("started_at")])
data class SyncRun(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "run_id") val runId: Long = 0,
    @ColumnInfo(name = "device_id") val deviceId: String,
    val trigger: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val result: String = RESULT_FAILED,
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
    val added: Int = 0,
    val replaced: Int = 0,
    val removed: Int = 0,
    val failed: Int = 0,
    @ColumnInfo(name = "bytes_transferred") val bytesTransferred: Long = 0,
    val reported: Int = 0,
    @ColumnInfo(name = "library_id") val libraryId: String? = null,
    @ColumnInfo(name = "content_digest") val contentDigest: String? = null,
    @ColumnInfo(name = "result_json") val resultJson: String? = null,
) {
    companion object {
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTO = "auto"
        const val RESULT_SUCCESS = "success"
        const val RESULT_PARTIAL = "partial"
        const val RESULT_INTERRUPTED = "interrupted"
        const val RESULT_FAILED = "failed"
        const val RESULT_NOT_STARTED = "not_started"
    }
}

/** T4 ペアリング。鍵の本体は持たず、別名だけ持つ。 */
@Entity(tableName = "pairing")
data class Pairing(
    @PrimaryKey @ColumnInfo(name = "device_id") val deviceId: String,
    @ColumnInfo(name = "pc_id") val pcId: String,
    @ColumnInfo(name = "pc_name") val pcName: String,
    @ColumnInfo(name = "endpoint_lan") val endpointLan: String? = null,
    @ColumnInfo(name = "endpoint_tailscale") val endpointTailscale: String? = null,
    @ColumnInfo(name = "endpoint_usb") val endpointUsb: String? = null,
    @ColumnInfo(name = "last_endpoint") val lastEndpoint: String? = null,
    @ColumnInfo(name = "spki_hash") val spkiHash: String,
    @ColumnInfo(name = "key_alias") val keyAlias: String,
    @ColumnInfo(name = "paired_at") val pairedAt: Long,
)

/** T5 取得の途中。 */
@Entity(tableName = "pending_transfers")
data class PendingTransfer(
    @PrimaryKey @ColumnInfo(name = "persistent_id") val persistentId: String,
    @ColumnInfo(name = "media_uri") val mediaUri: String,
    @ColumnInfo(name = "expected_size") val expectedSize: Long,
    @ColumnInfo(name = "received_bytes") val receivedBytes: Long,
    @ColumnInfo(name = "source_modified") val sourceModified: Long,
    @ColumnInfo(name = "started_at") val startedAt: Long,
)

/** T6 実行ごとの除外・再生不可・失敗。T3 の行を消すと一緒に消える。 */
@Entity(
    tableName = "run_issues",
    foreignKeys = [
        ForeignKey(
            entity = SyncRun::class,
            parentColumns = ["run_id"],
            childColumns = ["run_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("run_id")],
)
data class RunIssue(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "issue_id") val issueId: Long = 0,
    @ColumnInfo(name = "run_id") val runId: Long,
    @ColumnInfo(name = "persistent_id") val persistentId: String? = null,
    val title: String,
    val artist: String? = null,
    val reason: String? = null,
    val format: String? = null,
    val kind: String,
    @ColumnInfo(name = "error_kind") val errorKind: String? = null,
) {
    companion object {
        const val KIND_EXCLUDED = "excluded"
        const val KIND_UNPLAYABLE = "unplayable"
        const val KIND_FAILED = "failed"
    }
}

/** T8 選んだプレイリスト（D15）。葉だけ。[name] は選んだ時点の名前で、PC の一覧から消えたとき「見つからない」に出す。 */
@Entity(tableName = "selected_playlists")
data class SelectedPlaylist(
    @PrimaryKey @ColumnInfo(name = "playlist_id") val playlistId: String,
    val name: String,
    @ColumnInfo(name = "selected_at") val selectedAt: Long,
)

/** T9 保存先を切り替える前の場所に残る自分のファイル（D16）。取り直しが完了で終わったときだけ消す。 */
@Entity(tableName = "relocated_files")
data class RelocatedFile(
    @PrimaryKey @ColumnInfo(name = "media_uri") val mediaUri: String,
)

/** T7 設定。既知の `key` は auto_sync_enabled、log_enabled、device_id、storage_volume、placed_volume。 */
@Entity(tableName = "app_settings")
data class AppSetting(
    @PrimaryKey val key: String,
    val value: String,
)
