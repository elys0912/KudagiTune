package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint

// 端末側（A）のペアリングの手順。状態は M4 が持ち、ここは各段の純粋な計算だけ（X4-2-1、X4-2-4）。

/** P5 の要求を作った段（D1）。[x] を P5 の `x` に載せる。 */
class PairingInitiation internal constructor(
    val deviceId: String,
    val x: EcPoint,
    internal val scalar: ByteArray,
    internal val w: ByteArray,
) {
    override fun toString() = "PairingInitiation"
}

/** P6 の要求を作った段（D2）。[macD] を P6 の `mac_d` に載せる。 */
class DeviceConfirmation internal constructor(
    val macD: ByteArray,
    internal val expectedMacP: ByteArray,
    internal val keys: PakeKeys,
) {
    override fun toString() = "DeviceConfirmation"
}

/** コードを受けて、乱数のスカラー x と値 X を作る。乱数を引くのはここが先（次に PC の y）。 */
fun startPairing(crypto: CryptoProvider, deviceId: String, code: String): PairingInitiation {
    val w = passwordScalar(crypto, code)
    val x = crypto.randomBytes(SCALAR_BYTES)
    return PairingInitiation(deviceId, initiatorShare(crypto, x, w), x, w)
}

/**
 * P5 の応答（pc_id、Y）と、P5 の接続で見た PC の SPKI のハッシュから、確認の印 MAC_d を作る。
 * Y が曲線上の点でなければ null。
 */
fun confirmPairing(
    crypto: CryptoProvider,
    initiation: PairingInitiation,
    pcId: String,
    y: ByteArray,
    spkiHash: ByteArray,
): DeviceConfirmation? {
    val pB = crypto.decodePoint(y) ?: return null
    val k = initiatorSharedPoint(crypto, initiation.scalar, initiation.w, pB)
    val tt = transcript(
        a = initiation.deviceId.encodeToByteArray(),
        b = pcId.encodeToByteArray(),
        pA = initiation.x,
        pB = pB,
        k = k,
        w = initiation.w,
    )
    val keys = deriveKeys(crypto, tt, associatedData(initiation.deviceId, pcId, spkiHash))
    return DeviceConfirmation(
        macD = crypto.hmacSha256(keys.kcA, tt),
        expectedMacP = crypto.hmacSha256(keys.kcB, tt),
        keys = keys,
    )
}

/** PC の印 MAC_p が合えば署名用の鍵 K を返す。合わなければ null（偽の PC を拒否する）。 */
fun finishPairing(crypto: CryptoProvider, confirmation: DeviceConfirmation, macP: ByteArray): ByteArray? =
    if (crypto.constantTimeEquals(confirmation.expectedMacP, macP)) signingKey(crypto, confirmation.keys) else null
