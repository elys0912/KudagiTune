package playlistsyncfox.android.net

import playlistsyncfox.android.storage.TrackSource
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.Transport

/** 接続先 1 件。ポートを含む。 */
data class Endpoint(val transport: Transport, val host: String, val port: Int)

/** 通信の失敗の分類（X6-5 の対応表）。E の番号への写しは同期の手順が行う。 */
sealed interface ApiFailure {
    /** 接続できない、切れた、時間切れ。到達確認では「応えない」、その後は E6。 */
    data object Disconnected : ApiFailure

    /** 証明書の固定が合わない。 */
    data object PinMismatch : ApiFailure

    /** E2。[keyMismatch] は自動を再ペアリングまで止める印（J35）。[detail] が null は 403。 */
    data class Rejected(val detail: ErrorDetail?, val keyMismatch: Boolean) : ApiFailure

    /** 503。E4。 */
    data object XmlUnavailable : ApiFailure

    /** 404（P2）。E5。 */
    data object FileMissing : ApiFailure

    /** 409。E8。 */
    data object Stale : ApiFailure

    /** 416。最初から取り直す。 */
    data object RangeNotSatisfiable : ApiFailure

    /** 206 の `Content-Range` の開始が送った位置と違う。最初から取り直す。 */
    data object RangeMismatch : ApiFailure

    /** E なしで同期を止める（400、404（ルート不明）、405、413、500、応答の形の違い）。 */
    data object ServerFault : ApiFailure
}

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>

    data class Fail(val failure: ApiFailure) : ApiResult<Nothing>
}

/** P2 の応答。[startAt] は本文がファイルのどの位置から始まるか（200 なら 0、206 なら要求した位置）。 */
class TrackResponse(
    val startAt: Long,
    val sizeHeader: Long?,
    val modifiedAtHeader: Long?,
    val source: TrackSource,
)

/** 接続先 1 件に対する P1〜P4、P7、P8。実体は [PsfoxClient]、試験では偽物。 */
interface SyncApi : AutoCloseable {
    /** P3：`content_digest`。[playlistIds] は端末が選んだプレイリストの ID（本文で渡す。D15）。 */
    suspend fun digest(playlistIds: List<String>): ApiResult<String>

    /** P1：マニフェストの JSON の本文。[playlistIds] は P3 と同じ。 */
    suspend fun manifest(playlistIds: List<String>): ApiResult<String>

    /** P7：プレイリストの木の JSON の本文。 */
    suspend fun playlists(): ApiResult<String>

    /** P2：[rangeFrom] が 0 なら `Range` なし。本文は [block] の中でだけ読める。 */
    suspend fun <T> track(
        persistentId: String,
        size: Long,
        modifiedAt: Long,
        rangeFrom: Long,
        block: suspend (TrackResponse) -> T,
    ): ApiResult<T>

    /** P4：要約の報告。 */
    suspend fun report(body: String): ApiResult<Unit>

    /** P8：進み具合の知らせ（D22）。応答の待ちは V29。 */
    suspend fun progress(body: String): ApiResult<Unit>
}

/** 保存した接続先と固定した SPKI から [SyncApi] を作る。 */
fun interface ApiProvider {
    fun open(endpoint: Endpoint): SyncApi
}
