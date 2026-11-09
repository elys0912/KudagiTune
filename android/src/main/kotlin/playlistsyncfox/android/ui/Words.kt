package playlistsyncfox.android.ui

import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.sync.CatalogResult
import playlistsyncfox.shared.result.ErrorDetail
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

// 画面に出す語（X7-1）。見た目は書かず、語だけを決める。

/** E2 の `detail` の表示（X7-1-3 の表）。S4 は [label] で、S2・S3 は [title] と [guidance] を出す。 */
data class Notice(val title: String, val guidance: String, val label: String, val rePair: Boolean)

private const val REPAIR_GUIDE = "PC の画面の「端末」とこのアプリの設定の両方で解除してから、もう一度ペアリングしてください"

// E の番号の語（[errorWord]）、S6 の取得の失敗の語（[catalogWord]）、接続失敗の案内（[ConnectFailedNotice]）で共通の文
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
val ConnectFailedNotice = Notice(CONNECT_FAILED_WORD, "アドレスが正しいか、PC でアプリが起動しているかを確認して、やり直してください", "接続失敗", rePair = false)
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

private val BYTE_UNITS = listOf("B", "KiB", "MiB", "GiB")
private const val BYTE_UNIT_STEP = 1024.0

/**
 * 人が読む単位。小数 1 桁に丸めて 1024 に達するときは、上の単位で出す。例：1 MiB の 1 バイト手前は「1.0 MiB」（「1024.0 KiB」にしない）。
 */
fun formatBytes(bytes: Long): String {
    var value = bytes.toDouble()
    var unitIndex = 0
    while (unitIndex < BYTE_UNITS.lastIndex && roundedToTenths(value) >= BYTE_UNIT_STEP) {
        value /= BYTE_UNIT_STEP
        unitIndex++
    }
    return if (unitIndex == 0) "$bytes B" else "%.1f %s".format(value, BYTE_UNITS[unitIndex])
}

private fun roundedToTenths(value: Double): Double = (value * 10).roundToLong() / 10.0

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

// 画面の下や S3 に出す一文
const val NO_SELECTION_NOTICE = "同期するプレイリストを選んでください"
const val STORAGE_MISSING_NOTICE = "保存先の SD カードが見つかりません。SD カードを確認するか、設定で保存先を変更してください"
const val STORAGE_CHANGED_NOTICE = "保存先を変更しました。次の同期で、新しい保存先へ全曲を転送し直します"
const val STORAGE_LEFTOVER_NOTICE = "前の場所に曲が残っています。次の同期の前に、「前の保存先の曲を削除する」で、先に前の場所の曲を消してください"
const val LOG_SAVED_NOTICE = "ログをファイルに保存しました"
const val LOG_SAVE_FAILED_NOTICE = "ログを保存できませんでした"
const val APPROVAL_UNUSED_NOTICE = "自動の同期が実行中だったため、今回は削除しません。終わってから、もう一度「同期」を押してください"

/** マニフェストの版違い（X6-9）。壊れたマニフェストは対象外で、汎用の表示のまま。 */
const val VERSION_MISMATCH_NOTICE = "PC と端末のアプリの版を合わせてください"

const val AUTO_SYNC_FAILED_NOTICE = "自動同期の設定に失敗しました"
const val SYNCING_NO_DELETE_NOTICE = "同期中は削除できません"
const val UNPAIR_FAILED_NOTICE = "鍵の削除に失敗したため、解除できませんでした"
