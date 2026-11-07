package playlistsyncfox.android.ui

import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.shared.result.ErrorDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 画面に出す語（X7-1）。見た目は書かず、語だけを決める。

/** E2 の `detail` の表示（X7-1-3 の表）。S4 は [label] で、S2・S3 は [title] と [guidance] を出す。 */
data class Notice(val title: String, val guidance: String, val label: String, val rePair: Boolean)

private const val REPAIR_GUIDE = "PC の W3 と端末の S5 の両方で解除してから再ペアリングする"

fun noticeOf(detail: ErrorDetail): Notice = when (detail) {
    ErrorDetail.ClockSkew -> Notice("端末と PC の時計が違う", "時刻を合わせてやり直す", "時計のずれ", rePair = false)
    ErrorDetail.Replay -> Notice("通信が重複した", "もう一度試す", "通信の重複", rePair = false)
    ErrorDetail.RateLimited ->
        Notice("要求が続いたため PC が一時的に断った", "時間をおいて同期する（再ペアリングは要らない）", "一時的な拒否", rePair = false)
    ErrorDetail.PinMismatch -> Notice("証明書が変わった", REPAIR_GUIDE, "証明書の変更", rePair = true)
    ErrorDetail.NoDetail -> Notice("ペアリングをやり直してください", REPAIR_GUIDE, "署名の不一致", rePair = true)
}

/** S2 だけに出る `pairing_closed`。 */
val PairingClosedNotice = Notice("PC がコードを表示していない", "PC でコードを出してからやり直す", "コード未表示", rePair = false)

/** T3 の `result` に 1 対 1 の表示語（X3-5）。 */
fun resultWord(result: String): String = when (result) {
    SyncRun.RESULT_SUCCESS -> "完了"
    SyncRun.RESULT_PARTIAL -> "一部失敗"
    SyncRun.RESULT_INTERRUPTED -> "中断"
    SyncRun.RESULT_FAILED -> "失敗"
    SyncRun.RESULT_NOT_STARTED -> "開始できず"
    else -> result
}

/** `manual`、`auto` の 2 つだけ（J21）。 */
fun triggerWord(trigger: String): String = if (trigger == SyncRun.TRIGGER_MANUAL) "手動" else "自動"

/** E の番号の表示語。空なら出さない。 */
fun errorWord(errorKind: String?): String? = when (errorKind) {
    null, "" -> null
    "E1" -> "PC に届かない"
    "E2" -> "ペアリングし直す必要がある"
    "E3" -> "空き容量が足りない"
    "E4" -> "PC の XML が読めない"
    "E5" -> "PC に曲のファイルが無い"
    "E6" -> "途中で切れた"
    "E7" -> "時間の上限。続きは次回"
    "E8" -> "曲が変わった"
    "E9" -> "保存に失敗した"
    else -> errorKind
}

/** 除外・再生不可の理由（T6 の `reason`）。知らない語はそのまま出す。 */
fun reasonWord(reason: String?): String = when (reason) {
    null -> ""
    "protected" -> "保護された曲"
    "unsupported_format", "unsupportedformat" -> "再生できない形式"
    else -> reason
}

/** 人が読む単位。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GiB".format(bytes.toDouble() / (1L shl 30))
    bytes >= 1L shl 20 -> "%.1f MiB".format(bytes.toDouble() / (1L shl 20))
    bytes >= 1L shl 10 -> "%.1f KiB".format(bytes.toDouble() / (1L shl 10))
    else -> "$bytes B"
}

private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

fun formatTime(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    dateFormat.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

/** T4 の `spki_hash`（小文字の 16 進 64 桁）から、画面に出す指紋を作る。X4-4 の表示形式（先頭 12 バイトを 4 桁ごと）。 */
fun fingerprintOf(spkiHash: String): String = spkiHash.take(24).chunked(4).joinToString(" ")
