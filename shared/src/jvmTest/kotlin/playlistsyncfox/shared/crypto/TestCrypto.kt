package playlistsyncfox.shared.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * テスト用の暗号の実体。点演算は Bouncy Castle、ほかは JDK。
 * 乱数は [randoms] を先頭から 1 つずつ返す固定の偽物で、尽きたら失敗する。
 * [randomCalls] は乱数を得た回数。
 */
class TestCrypto(vararg randoms: ByteArray) : CryptoProvider {
    private val queue = ArrayDeque(randoms.toList())
    var randomCalls = 0
        private set

    private val curve = CustomNamedCurves.getByName("secp256r1").curve
    private val base = CustomNamedCurves.getByName("secp256r1").g

    override fun randomBytes(count: Int): ByteArray {
        randomCalls++
        val next = queue.removeFirstOrNull() ?: error("固定の乱数が尽きた")
        check(next.size == count) { "乱数の長さが違う：要求 $count、用意 ${next.size}" }
        return next
    }

    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    override fun hmacSha256(key: SigningKeyRef, message: ByteArray): ByteArray = when (key) {
        is SigningKeyRef.KeyBytes -> hmacSha256(key.key, message)
        is SigningKeyRef.KeystoreAlias -> error("テストの実体は Keystore を持たない")
    }

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt.takeIf { it.isNotEmpty() }, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    override fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    override fun multiplyBase(scalar: ByteArray): EcPoint = encode(base.multiply(BigInteger(1, scalar)))

    override fun multiply(point: EcPoint, scalar: ByteArray): EcPoint =
        encode(curve.decodePoint(point.encoded).multiply(BigInteger(1, scalar)))

    override fun add(a: EcPoint, b: EcPoint): EcPoint =
        encode(curve.decodePoint(a.encoded).add(curve.decodePoint(b.encoded)))

    override fun negate(point: EcPoint): EcPoint = encode(curve.decodePoint(point.encoded).negate())

    override fun decodePoint(encoded: ByteArray): EcPoint? {
        if (encoded.size != 65 || encoded[0] != 4.toByte()) return null
        return try {
            val point = curve.decodePoint(encoded)
            if (point.isInfinity || !point.isValid) null else EcPoint(encoded.copyOf())
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun encode(point: org.bouncycastle.math.ec.ECPoint) = EcPoint(point.normalize().getEncoded(false))
}

