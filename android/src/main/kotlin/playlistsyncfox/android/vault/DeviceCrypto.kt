package playlistsyncfox.android.vault

import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.math.ec.ECPoint
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint
import playlistsyncfox.shared.crypto.SigningKeyRef
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val UNCOMPRESSED_POINT_BYTES = 65
private const val HASH_BYTES = 32

/**
 * `CryptoProvider` の端末側の実体（X4-1、X6-6）。点演算は Bouncy Castle、ほかは JDK。
 * 署名の鍵が Keystore の別名のときは [aliasHmac] に任せる（K3 は取り出せない）。
 * 点演算は例外にしない。スカラーは位数で割った余りに直し、途中の点が無限遠点になっても演算を続ける
 * （P5 の応答の形が入力で変わると、コードを当てる手がかりになるため）。
 */
class DeviceCrypto(
    private val aliasHmac: (alias: String, message: ByteArray) -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) : CryptoProvider {
    private val params = CustomNamedCurves.getByName("secp256r1")
    private val curve = params.curve
    private val order = params.n

    override fun randomBytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    override fun hmacSha256(key: SigningKeyRef, message: ByteArray): ByteArray = when (key) {
        is SigningKeyRef.KeystoreAlias -> aliasHmac(key.alias, message)
        is SigningKeyRef.KeyBytes -> hmacSha256(key.key, message)
    }

    // RFC 5869。塩が空なら 0 を HashLen 個並べた塩と同じ。Android 本体にも org.bouncycastle があり、
    // 本体側の同名クラスが先に読まれうるので、BC の HKDF には頼らない
    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(if (salt.isEmpty()) ByteArray(HASH_BYTES) else salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var filled = 0
        var counter = 1
        while (filled < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val take = minOf(previous.size, length - filled)
            previous.copyInto(out, filled, 0, take)
            filled += take
            counter++
        }
        return out
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    override fun multiplyBase(scalar: ByteArray): EcPoint = encode(params.g.multiply(reduce(scalar)))

    override fun multiply(point: EcPoint, scalar: ByteArray): EcPoint = encode(decode(point).multiply(reduce(scalar)))

    override fun add(a: EcPoint, b: EcPoint): EcPoint = encode(decode(a).add(decode(b)))

    override fun negate(point: EcPoint): EcPoint = encode(decode(point).negate())

    override fun decodePoint(encoded: ByteArray): EcPoint? {
        if (encoded.size != UNCOMPRESSED_POINT_BYTES || encoded[0] != 4.toByte()) return null
        val point = try {
            curve.decodePoint(encoded)
        } catch (_: RuntimeException) {
            return null
        }
        return if (point.isInfinity || !point.isValid) null else EcPoint(encoded.copyOf())
    }

    private fun reduce(scalar: ByteArray): BigInteger = BigInteger(1, scalar).mod(order)

    // 無限遠点の符号化は 1 バイトの 0x00。BC の decodePoint もこれを無限遠点に戻す
    private fun encode(point: ECPoint) = EcPoint(point.normalize().getEncoded(false))

    private fun decode(point: EcPoint): ECPoint = curve.decodePoint(point.encoded)
}
