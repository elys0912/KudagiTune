package playlistsyncfox.shared.auth

/** B1-7 の値 V のうち、認証の手順が使うもの。時間の単位は秒。 */
object AuthValues {
    /** V1 ペアリングコードの桁数。 */
    const val CODE_DIGITS = 6

    /** V2 ペアリングコードの試行回数の上限。 */
    const val MAX_ATTEMPTS = 5

    /** V3 ペアリングコードの有効時間。 */
    const val CODE_VALID_SECONDS = 300L

    /** V4 試行回数の超過後の待ち。 */
    const val LOCK_SECONDS = 30L

    /** V12 署名の時刻の許容の幅（前後）。 */
    const val CLOCK_TOLERANCE_SECONDS = 300L

    /** V41 nonce の記録の件数の上限。 */
    const val NONCE_RECORD_LIMIT = 10_000

    /** V42 コードの生成の偏り除外の最大の繰り返し。 */
    const val CODE_MAX_DRAWS = 3
}
