package playlistsyncfox.android.ui

import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.sync.CatalogResult
import playlistsyncfox.shared.result.ErrorDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 画面に出す語（X7-1）。見た目は書かず、語だけを決める。

/** E2 の `detail` の表示（X7-1-3 の表）。S4 は [label] で、S2・S3 は [title] と [guidance] を出す。 */
data class Notice(val title: String, val guidance: String, val label: String, val rePair: Boolean)

private const val REPAIR_GUIDE = "PC の画面の「端末」とこのアプリの設定の両方で解除してから、もう一度ペアリングしてください"

// E の番号の語（[errorWord]）と、S6 の取得の失敗の語（[catalogWord]）で共通の文
private const val CONNECT_FAILED_WORD = "PC に接続できません"
private const val REPAIR_NEEDED_WORD = "ペアリングのやり直しが必要です"
private const val XML_UNAVAILABLE_WORD = "PC の iTunes ライブラリ（XML）を読めません"

fun noticeOf(detail: ErrorDetail): Notice = when (detail) {
    ErrorDetail.ClockSkew -> Notice("端末と PC の時刻がずれています", "時刻を合わせてから、やり直してください", "時計のずれ", rePair = false)
    ErrorDetail.Replay -> Notice("同じ通信が重複して届きました", "もう一度同期してください", "通信の重複", rePair = false)
    ErrorDetail.RateLimited ->
        Notice("短い間に接続が続いたため、PC が一時的に断りました", "少し時間をおいてから同期してください（ペアリングのやり直しは不要です）", "一時的な拒否", rePair = false)
    ErrorDetail.PinMismatch -> Notice("PC の証明書が変わりました", REPAIR_GUIDE, "証明書の変更", rePair = true)
    ErrorDetail.NoDetail -> Notice("ペアリングをやり直してください", REPAIR_GUIDE, "署名の不一致", rePair = true)
}

/** S2 だけに出る `pairing_closed`。 */
val PairingClosedNotice = Notice("PC にペアリングコードが表示されていません", "PC でペアリングコードを表示してから、やり直してください", "コード未表示", rePair = false)

// S2 のペアリングの途中の失敗（[DevicePairingGateway] が失敗の段へ載せる）
val ConnectFailedNotice = Notice("PC に接続できません", "アドレスが正しいか、PC でアプリが起動しているかを確認して、やり直してください", "接続失敗", rePair = false)
val BadReplyNotice = Notice("PC からの応答を読み取れません", "やり直してください", "応答の形", rePair = false)
val PcRefusedNotice = Notice("PC がペアリングを受け付けませんでした", "PC の画面を確認して、やり直してください", "PC の拒否", rePair = false)
val BadProofNotice = Notice("PC の確認に失敗しました", "PC の画面の「端末」で解除してから、やり直してください", "確認の印", rePair = true)
val CertificateChangedNotice = Notice("接続の途中で PC の証明書が変わりました", "ネットワークを確認して、やり直してください", "証明書の変更", rePair = false)

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
    "E1" -> CONNECT_FAILED_WORD
    "E2" -> REPAIR_NEEDED_WORD
    "E3" -> "空き容量が足りません"
    "E4" -> XML_UNAVAILABLE_WORD
    "E5" -> "PC に曲のファイルがありません"
    "E6" -> "通信が途中で切れました"
    "E7" -> "実行時間の上限に達しました。残りは次の同期で続けます"
    "E8" -> "転送中に PC の曲が変更されました"
    "E9" -> "端末への保存に失敗しました"
    else -> errorKind
}

/** 除外・再生不可の理由（T6 の `reason`）。知らない語はそのまま出す。 */
fun reasonWord(reason: String?): String = when (reason) {
    null -> ""
    "protected" -> "著作権保護された曲"
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

/** S6 で一覧を取れなかった理由の表示語。E2 の理由（[CatalogResult.Rejected]）は S3 と同じ [noticeOf] の見出し。 */
fun catalogWord(result: CatalogResult): String = when (result) {
    is CatalogResult.Loaded -> ""
    CatalogResult.Unreachable -> CONNECT_FAILED_WORD
    CatalogResult.KeyMismatch -> REPAIR_NEEDED_WORD
    is CatalogResult.Rejected -> noticeOf(result.detail).title
    CatalogResult.XmlUnavailable -> XML_UNAVAILABLE_WORD
    CatalogResult.Other -> "プレイリストの一覧を取得できませんでした"
}

/** S6 の取得の失敗に添える案内。[CatalogResult.Rejected] のときだけ、S3 と同じ [noticeOf] の案内を出す。 */
fun catalogGuidance(result: CatalogResult): String? = (result as? CatalogResult.Rejected)?.let { noticeOf(it.detail).guidance }
