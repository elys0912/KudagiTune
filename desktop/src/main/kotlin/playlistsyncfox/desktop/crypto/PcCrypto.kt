package playlistsyncfox.desktop.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
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

/**
 * `CryptoProvider` の PC 側の実体（X4-1）。点演算は Bouncy Castle、ほかは JDK。
 * どの操作も例外にしない。スカラーは位数で割った余りに直し、途中の点が無限遠点になっても演算を続ける
 * （P5 の応答の形が入力で変わると、コードを当てる手がかりになるため）。
 */
class PcCrypto(private val random: SecureRandom = SecureRandom()) : CryptoProvider {
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
        is SigningKeyRef.KeyBytes -> hmacSha256(key.key, message)
        // PC は K1 を値で持つ。Keystore の別名は端末側だけ
        is SigningKeyRef.KeystoreAlias -> error("PC は Keystore の別名を使わない")
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt.takeIf { it.isNotEmpty() }, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    override fun multiplyBase(scalar: ByteArray): EcPoint = encode(params.g.multiply(reduce(scalar)))

    override fun multiply(point: EcPoint, scalar: ByteArray): EcPoint =
        encode(decode(point).multiply(reduce(scalar)))

    override fun add(a: EcPoint, b: EcPoint): EcPoint = encode(decode(a).add(decode(b)))

    override fun negate(point: EcPoint): EcPoint = encode(decode(point).negate())

    override fun decodePoint(encoded: ByteArray): EcPoint? {
        if (encoded.size != UNCOMPRESSED_POINT_BYTES || encoded[0] != 4.toByte()) return null
        val point = try {
            curve.decodePoint(encoded)
        } catch (_: IllegalArgumentException) {
            return null
        }
        return if (point.isInfinity || !point.isValid) null else EcPoint(encoded.copyOf())
    }

    private fun reduce(scalar: ByteArray): BigInteger = BigInteger(1, scalar).mod(order)

    // 無限遠点の符号化は 1 バイトの 0x00。decodePoint（BC）もこれを無限遠点に戻す
    private fun encode(point: ECPoint) = EcPoint(point.normalize().getEncoded(false))

    private fun decode(point: EcPoint): ECPoint = curve.decodePoint(point.encoded)
}
