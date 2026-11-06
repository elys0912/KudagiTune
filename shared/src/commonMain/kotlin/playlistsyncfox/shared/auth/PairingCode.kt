package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider

private const val DRAW_BYTES = 4
private const val DRAW_RANGE = 1L shl 32

/**
 * ペアリングコードを作る（X4-2-5）。偏りを避けるため、10 の桁数乗の倍数に切り下げた値以上の乱数は捨てる。
 * 捨てた回数が [AuthValues.CODE_MAX_DRAWS] に達したら null。
 */
fun generatePairingCode(crypto: CryptoProvider): String? {
    var modulus = 1L
    repeat(AuthValues.CODE_DIGITS) { modulus *= 10 }
    val threshold = DRAW_RANGE / modulus * modulus

    repeat(AuthValues.CODE_MAX_DRAWS) {
        val draw = crypto.randomBytes(DRAW_BYTES).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xFF) }
        if (draw < threshold) return (draw % modulus).toString().padStart(AuthValues.CODE_DIGITS, '0')
    }
    return null
}
