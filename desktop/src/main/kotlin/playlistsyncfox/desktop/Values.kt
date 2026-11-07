package playlistsyncfox.desktop

import java.time.Duration

/** B1-7 の値 V のうち、desktop が使うもの（認証の値は shared の AuthValues、設定の既定は settings、証明書は secrets）。 */
object DesktopValues {
    /** V6：XML の読み直しの回数。 */
    const val LOAD_ATTEMPTS = 3

    /** V6：XML の読み直しの間隔。 */
    val LOAD_RETRY_DELAY: Duration = Duration.ofSeconds(1)

    /** V18：XML の安定待ち。フォルダの消失の後の存在の確認の間隔（V37）にも使う。 */
    val XML_STABLE_WAIT: Duration = Duration.ofSeconds(2)

    /** V31：要求の本文の上限。 */
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    /** V54：P1・P3 の本文に置ける Persistent ID の個数の上限。 */
    const val MAX_PLAYLIST_IDS = 5000

    /** V32：署名失敗の待ち時間。 */
    val SIGNATURE_BLOCK: Duration = Duration.ofSeconds(60)

    /** V33：接続中・同期中の判定の経過時間。 */
    val ACTIVITY_WINDOW: Duration = Duration.ofSeconds(60)

    /** V43：署名失敗の連続の閾値。 */
    const val SIGNATURE_FAILURE_LIMIT = 10

    /** V45：ネットワークの変化の確認の間隔。 */
    val NETWORK_POLL: Duration = Duration.ofSeconds(30)

    /** V46：終了時の送信中の接続の猶予。 */
    val SHUTDOWN_GRACE: Duration = Duration.ofSeconds(5)

    /** V55：画面のログの欄がメモリに持つ行数。超えたら古い行から捨てる（X5-4）。 */
    const val LOG_LINES = 200

    /** V56：P8 の進み具合を持ち続ける期限。最後の P8 からこの時間だけ更新が無ければ消す（X5-1）。 */
    val PROGRESS_TTL: Duration = Duration.ofSeconds(30)
}
