package playlistsyncfox.desktop.secrets

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.ECNamedCurveTable
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.jce.spec.ECPublicKeySpec
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import playlistsyncfox.desktop.settings.writeAtomically
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.Date

/** V44：証明書の更新の閾値。残りがこれ以下なら同じ鍵で作り直す。 */
val CERTIFICATE_RENEW_THRESHOLD: Duration = Duration.ofDays(90)

private const val CERTIFICATE_SUBJECT = "CN=playlist-sync-fox"

/** V30：証明書の有効期間（年）。 */
private const val CERTIFICATE_VALID_YEARS = 10L

private const val TLS_KEY_NAME = "tls-key"

/**
 * K2：サーバーの鍵と証明書（X5-2）。
 * [spkiDer] は証明書の SubjectPublicKeyInfo の DER。[keyIsNew] が真なら SPKI が変わったので、ペアリング済みの端末は再ペアリングが要る。
 */
class TlsIdentity(
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
    val spkiDer: ByteArray,
    val keyIsNew: Boolean,
)

private val bouncyCastle = BouncyCastleProvider()

/**
 * `tls-key`（[store]）と証明書（[certFile]）を読み、足りなければ作る（X5-2 の「証明書の生成と更新」）。
 * 鍵があって、証明書が無い・期限が残り V44 以下・公開鍵が違うときは、同じ鍵で証明書を作り直す。鍵が無ければ両方を作る。
 */
fun loadOrCreateTls(store: SecretStore, certFile: Path, now: Instant): TlsIdentity {
    val keyBytes = store.read(TLS_KEY_NAME)
    if (keyBytes == null) {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), SecureRandom()) }.generateKeyPair()
        store.write(TLS_KEY_NAME, pair.private.encoded)
        return writeNewCertificate(pair.private, certFile, now, keyIsNew = true)
    }

    val privateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    val existing = readCertificate(certFile)
    val renew = existing == null ||
        !existing.publicKey.encoded.contentEquals(derivePublicKey(privateKey as ECPrivateKey).encoded) ||
        Duration.between(now, existing.notAfter.toInstant()) <= CERTIFICATE_RENEW_THRESHOLD
    return if (renew) {
        writeNewCertificate(privateKey, certFile, now, keyIsNew = false)
    } else {
        TlsIdentity(privateKey, existing, existing.publicKey.encoded, keyIsNew = false)
    }
}

private fun writeNewCertificate(privateKey: PrivateKey, certFile: Path, now: Instant, keyIsNew: Boolean): TlsIdentity {
    val publicKey = derivePublicKey(privateKey as ECPrivateKey)
    val notBefore = now.atOffset(ZoneOffset.UTC)
    val builder = JcaX509v3CertificateBuilder(
        X500Name(CERTIFICATE_SUBJECT),
        BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE),
        Date.from(notBefore.toInstant()),
        Date.from(notBefore.plusYears(CERTIFICATE_VALID_YEARS).toInstant()),
        X500Name(CERTIFICATE_SUBJECT),
        publicKey,
    )
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(privateKey)
    val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))

    val pem = "-----BEGIN CERTIFICATE-----\n" +
        Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(certificate.encoded) +
        "\n-----END CERTIFICATE-----\n"
    writeAtomically(certFile) { it.write(pem.encodeToByteArray()) }
    return TlsIdentity(privateKey, certificate, certificate.publicKey.encoded, keyIsNew)
}

private fun readCertificate(file: Path): X509Certificate? {
    if (!Files.exists(file)) return null
    return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(Files.readAllBytes(file))) as X509Certificate
}

// PKCS#8 の EC 秘密鍵は公開鍵を持たないことがあるので、d・G から求める
private fun derivePublicKey(privateKey: ECPrivateKey): java.security.PublicKey {
    val spec = ECNamedCurveTable.getParameterSpec("secp256r1")
    return KeyFactory.getInstance("EC", bouncyCastle).generatePublic(ECPublicKeySpec(spec.g.multiply(privateKey.s).normalize(), spec))
}
