package playlistsyncfox.android.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** 葉の証明書の SPKI（DER）の SHA-256。`PublicKey.getEncoded()` は X.509 の SubjectPublicKeyInfo の DER。 */
fun spkiSha256(certificate: X509Certificate): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(certificate.publicKey.encoded)

/**
 * 標準の検証（有効期限、署名の方式、ホスト名、発行者）を使わない信頼の判定の土台。
 * `X509ExtendedTrustManager` にすると、JSSE が追加の制約の検査を挟まない。
 */
abstract class LeafTrustManager : X509ExtendedTrustManager() {
    protected abstract fun checkLeaf(leaf: X509Certificate)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        checkLeaf(chain?.firstOrNull() ?: throw CertificateException("証明書が提示されなかった"))
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?): Unit =
        throw CertificateException("クライアント証明書は使わない")

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?): Unit =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?): Unit =
        checkClientTrusted(chain, authType)

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * 証明書の固定（X6-5、C-11）。固定した SPKI のハッシュと一致したときだけ信頼する。
 * ホスト名は照合しない（B4-3 の 1）。システムの信頼には落とさない。
 */
class SpkiPinTrustManager(private val pinned: ByteArray) : LeafTrustManager() {
    /** 不一致で切った接続があるか。到達確認が「応えない」と「証明書の不一致」を分けるために使う。 */
    @Volatile
    var mismatched: Boolean = false
        private set

    override fun checkLeaf(leaf: X509Certificate) {
        if (!MessageDigest.isEqual(pinned, spkiSha256(leaf))) {
            mismatched = true
            throw CertificateException("証明書の固定が合わない")
        }
    }
}

/** P5・P6 用。検証せず、提示された葉の SPKI のハッシュを記録して X4 の手順へ渡す。固定後の P とは共用しない（X6 の決定 6）。 */
class RecordingTrustManager : LeafTrustManager() {
    @Volatile
    var spkiHash: ByteArray? = null
        private set

    override fun checkLeaf(leaf: X509Certificate) {
        spkiHash = spkiSha256(leaf)
    }
}

/** OkHttp エンジンの `TrustManager` と `HostnameVerifier` を差し込んだクライアント。リダイレクトは追わない。 */
fun createTrustedHttpClient(trust: LeafTrustManager): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(HttpTimeout)
    engine {
        config {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            sslSocketFactory(context.socketFactory, trust)
            hostnameVerifier { _, _ -> true }
        }
    }
}
