package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.SigningKeyRef
import kotlin.io.encoding.Base64

private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** 署名ヘッダの名前（Q1 で変わりうるためここだけに置く）。 */
object SignatureHeaderNames {
    const val DEVICE = "X-Psfox-Device"
    const val TIMESTAMP = "X-Psfox-Timestamp"
    const val NONCE = "X-Psfox-Nonce"
    const val SIGNATURE = "X-Psfox-Signature"
}

/**
 * 署名の入力（X4-3-1）。時計と乱数は呼び出し側が渡す。
 * 各文字列に LF を含まないことは、呼び出しの前に済んでいる前提。
 *
 * @param pathAndQuery 送った文字列のまま。再エンコードしない
 * @param timestamp UTC のエポック秒
 * @param nonce 乱数 16 バイト
 */
class SignedRequest(
    val method: String,
    val pathAndQuery: String,
    val timestamp: Long,
    val nonce: ByteArray,
    val deviceId: String,
    val body: ByteArray,
)

/** 要求に付ける 4 つのヘッダの値。名前は [SignatureHeaderNames]。 */
class SignatureHeaders(val device: String, val timestamp: String, val nonce: String, val signature: String)

fun encodeBase64Url(bytes: ByteArray): String = base64Url.encode(bytes)

/** 署名対象の 7 行。末尾に LF は付けない。 */
internal fun buildStringToSign(crypto: CryptoProvider, request: SignedRequest): String = listOf(
    "PSFOX1",
    request.method.uppercase(),
    request.pathAndQuery,
    request.timestamp.toString(),
    encodeBase64Url(request.nonce),
    request.deviceId,
    crypto.sha256(request.body).toHexString(),
).joinToString("\n")

/** 署名対象の文字列の HMAC-SHA256 を、パディングなしの base64url にする。 */
fun sign(crypto: CryptoProvider, key: SigningKeyRef, stringToSign: String): String =
    encodeBase64Url(crypto.hmacSha256(key, stringToSign.encodeToByteArray()))

fun signRequest(crypto: CryptoProvider, key: SigningKeyRef, request: SignedRequest): SignatureHeaders =
    SignatureHeaders(
        device = request.deviceId,
        timestamp = request.timestamp.toString(),
        nonce = encodeBase64Url(request.nonce),
        signature = sign(crypto, key, buildStringToSign(crypto, request)),
    )
