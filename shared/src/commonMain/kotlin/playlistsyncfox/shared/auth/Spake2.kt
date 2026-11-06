package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint

// SPAKE2 の手順（RFC 9382、P-256・SHA-256・HKDF・HMAC）。点演算と HMAC などは CryptoProvider に任せ、
// ここは手順の組み立てだけを持つ。ほかのファイルからは使うが、外へは公開しない。
// 余因子 h は P-256 で 1 のため掛けない。

// RFC 9382 の 6 章 Table 1 の P-256 の M と N（先頭 0x04 の非圧縮形式）。
// RFC は圧縮形式（02886e…、03d8bb…）で載せる。それを展開した値で、テストが圧縮形式との一致を確かめる
internal val M = EcPoint(
    (
        "04886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f" +
            "5ff355163e43ce224e0b0e65ff02ac8e5c7be09419c785e0ca547d55a12e2d20"
        ).hexToByteArray(),
)
internal val N = EcPoint(
    (
        "04d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49" +
            "07d60aa6bfade45008a636337f5168c64d9bd36034808cd564490b1e656edbe7"
        ).hexToByteArray(),
)

private const val PASSWORD_INFO = "psfox v1 pake-password"
private const val SIGNING_KEY_INFO = "psfox v1 request-signing"
private const val CONFIRMATION_INFO = "ConfirmationKeys"
private const val SCALAR_BYTES = 32
private const val HALF_HASH_BYTES = 16

internal val NO_SALT = ByteArray(0)

/** 確認の印と鍵の元になる、転写 TT から導いた値。 */
internal class PakeKeys(
    val transcript: ByteArray,
    val ke: ByteArray,
    val kcA: ByteArray,
    val kcB: ByteArray,
) {
    override fun toString() = "PakeKeys"
}

/** コードからパスワードのスカラー w を作る（X4-2-4 の 1）。RFC の MHF とは違う独自の選択。 */
internal fun passwordScalar(crypto: CryptoProvider, code: String): ByteArray =
    crypto.hkdfSha256(code.encodeToByteArray(), NO_SALT, PASSWORD_INFO.encodeToByteArray(), SCALAR_BYTES)

/** 端末（A）の値 pA = x・基点 + w・M。 */
internal fun initiatorShare(crypto: CryptoProvider, x: ByteArray, w: ByteArray): EcPoint =
    crypto.add(crypto.multiplyBase(x), crypto.multiply(M, w))

/** PC（B）の値 pB = y・基点 + w・N。 */
internal fun responderShare(crypto: CryptoProvider, y: ByteArray, w: ByteArray): EcPoint =
    crypto.add(crypto.multiplyBase(y), crypto.multiply(N, w))

/** 端末側の共有点 K = x・(pB − w・N)。 */
internal fun initiatorSharedPoint(crypto: CryptoProvider, x: ByteArray, w: ByteArray, pB: EcPoint): EcPoint =
    crypto.multiply(crypto.add(pB, crypto.negate(crypto.multiply(N, w))), x)

/** PC 側の共有点 K = y・(pA − w・M)。 */
internal fun responderSharedPoint(crypto: CryptoProvider, y: ByteArray, w: ByteArray, pA: EcPoint): EcPoint =
    crypto.multiply(crypto.add(pA, crypto.negate(crypto.multiply(M, w))), y)

/** 転写 TT。w は 32 バイトの固定長（RFC のとおり位数の長さに揃える）。 */
internal fun transcript(
    a: ByteArray,
    b: ByteArray,
    pA: EcPoint,
    pB: EcPoint,
    k: EcPoint,
    w: ByteArray,
): ByteArray = lengthPrefixed(a) + lengthPrefixed(b) +
    lengthPrefixed(pA.encoded) + lengthPrefixed(pB.encoded) +
    lengthPrefixed(k.encoded) + lengthPrefixed(w)

/** Ke || Ka = SHA-256(TT)、KcA || KcB = HKDF(Ka, 塩なし, "ConfirmationKeys" || AAD, 32)。 */
internal fun deriveKeys(crypto: CryptoProvider, tt: ByteArray, aad: ByteArray): PakeKeys {
    val hash = crypto.sha256(tt)
    val ke = hash.copyOfRange(0, HALF_HASH_BYTES)
    val ka = hash.copyOfRange(HALF_HASH_BYTES, hash.size)
    val confirmation = crypto.hkdfSha256(ka, NO_SALT, CONFIRMATION_INFO.encodeToByteArray() + aad, HALF_HASH_BYTES * 2)
    return PakeKeys(
        transcript = tt,
        ke = ke,
        kcA = confirmation.copyOfRange(0, HALF_HASH_BYTES),
        kcB = confirmation.copyOfRange(HALF_HASH_BYTES, confirmation.size),
    )
}

/** 付加データ AAD。境界の曖昧さによる衝突を避けるため、長さ付きで連ねる（X4-2-4 の 7）。 */
internal fun associatedData(deviceId: String, pcId: String, spkiHash: ByteArray): ByteArray =
    lengthPrefixed(deviceId.encodeToByteArray()) + lengthPrefixed(pcId.encodeToByteArray()) + lengthPrefixed(spkiHash)

/** 両側の印がそろった後の署名用の鍵 K（X4-2-4 の 11）。 */
internal fun signingKey(crypto: CryptoProvider, keys: PakeKeys): ByteArray =
    crypto.hkdfSha256(keys.ke, NO_SALT, SIGNING_KEY_INFO.encodeToByteArray(), SCALAR_BYTES)

/** 長さ（8 バイトのリトルエンディアン）を前置する。 */
private fun lengthPrefixed(bytes: ByteArray): ByteArray {
    val length = ByteArray(Long.SIZE_BYTES) { index -> (bytes.size.toLong() shr (8 * index)).toByte() }
    return length + bytes
}
