package playlistsyncfox.android.sync

/** B1-7 の値 V のうち、保存と同期が使うもの。時間はミリ秒。 */
object SyncValues {
    /** V10 転送の 1 曲の再試行回数。待ちは 2 秒、4 秒、8 秒。 */
    const val MAX_RETRIES = 3
    val RETRY_WAITS_MS = longArrayOf(2_000, 4_000, 8_000)

    /** V11 空き容量の余裕（512 MiB）。 */
    const val STORAGE_MARGIN_BYTES = 512L * 1024 * 1024

    /** V14 NSD の引き直しの待ち。 */
    const val NSD_RESOLVE_WAIT_MS = 3_000L

    /** V15 自動の同期の最小の間隔（6 時間）。 */
    const val AUTO_INTERVAL_HOURS = 6L

    /** V17 注意を出すまでの経過（1 時間）。 */
    const val NEAR_LIMIT_MS = 60L * 60 * 1000

    /** V19 手動の同期の後の、自動の抑止時間（30 分）。 */
    const val AUTO_SUPPRESS_MS = 30L * 60 * 1000

    /** V20 「消す」の件数の確認の閾値。 */
    const val REMOVAL_CONFIRM_THRESHOLD = 20

    /** V22 保存先のフォルダ名。 */
    const val APP_FOLDER = "playlist-sync-fox"

    /** `Music/<V22>/`。MediaStore の `RELATIVE_PATH` の前置き。 */
    const val MEDIA_ROOT = "Music/$APP_FOLDER/"

    /** V23 1 要素の上限（UTF-8 で 240 バイト）。 */
    const val MAX_ELEMENT_BYTES = 240

    /** V24 衝突の接尾辞に使う Persistent ID の文字数。 */
    const val SUFFIX_LENGTH = 8

    /** V25 取得の途中（T5）の期限（7 日）。 */
    const val PENDING_TTL_MS = 7L * 24 * 60 * 60 * 1000

    /** V26 T3 に残す実行の件数。 */
    const val RUN_HISTORY_LIMIT = 100

    /**
     * V27 書き込みの 1 区切りの上限。設計は「1 秒相当」。端末の書き込み速度を 1 MiB/s 以上と見て 1 MiB とする
     * （E7 は数秒以内に止める必要があるため、遅い端末でもこの大きさを超えない）。
     */
    const val WRITE_CHUNK_BYTES = 1024 * 1024

    /** V28 通知の更新の最小の間隔。 */
    const val NOTIFICATION_INTERVAL_MS = 1_000L

    /** V29 接続先 1 件の接続の確立の待ち。 */
    const val CONNECT_WAIT_MS = 5_000L

    /** V53 P3 の応答の待ち（仮。C-13 で計る）。 */
    const val DIGEST_RESPONSE_WAIT_MS = 60_000L

    /** E7・利用者の中断の後に、m3u8 を書くために待てる時間（「数秒以内」に戻る必要があるため）。 */
    const val PLAYLIST_ON_STOP_WAIT_MS = 3_000L
}
