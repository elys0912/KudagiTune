package playlistsyncfox.android.net

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.network.tls.certificates.generateCertificate
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.shared.auth.SignatureHeaderNames
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint
import playlistsyncfox.shared.crypto.SigningKeyRef
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.Transport
import playlistsyncfox.android.storage.TrackSource
import playlistsyncfox.android.sync.SyncValues
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 試験用の暗号。署名に要る部分だけ（同期の実行は他を使わない）。乱数は呼ぶたびに変わる決まった並び。 */
class CountingCrypto : CryptoProvider {
    private var counter = 0

    override fun randomBytes(count: Int) = ByteArray(count) { (counter + it).toByte() }.also { counter++ }

    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)

    override fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(message)

    override fun hmacSha256(key: SigningKeyRef, message: ByteArray): ByteArray = hmacSha256(ByteArray(32) { 7 }, message)

    override fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int) = unused()

    override fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    override fun multiplyBase(scalar: ByteArray) = unused()

    override fun multiply(point: EcPoint, scalar: ByteArray) = unused()

    override fun add(a: EcPoint, b: EcPoint) = unused()

    override fun negate(point: EcPoint) = unused()

    override fun decodePoint(encoded: ByteArray) = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("同期の実行では使わない")
}

private val ENDPOINT = Endpoint(Transport.Lan, "192.0.2.10", 47800)

private val IDS = listOf("00000000000000A1", "00000000000000B2")

private fun client(
    pinMismatched: () -> Boolean = { false },
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = PsfoxClient(
    http = HttpClient(MockEngine(handler)) { install(HttpTimeout) },
    endpoint = ENDPOINT,
    crypto = CountingCrypto(),
    keyRef = SigningKeyRef.KeystoreAlias("alias"),
    deviceId = DEVICE,
    nowSeconds = { 1_800_000_000 },
    pinMismatched = pinMismatched,
)

private class Drain(private val source: TrackSource) {
    suspend fun all(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16)
        while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}

private fun MockRequestHandleScope.failure(status: Int, detail: String? = null) = respond(
    content = """{"error":"E2"${if (detail != null) ",\"detail\":\"$detail\"" else ""}}""",
    status = HttpStatusCode.fromValue(status),
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

class PsfoxClientTest {
    @Test
    fun `TA-029 206 の Content-Range が要求位置と違えば書き込まずに取り直しを求める`() = runTest {
        val api = client {
            respond("x".repeat(10), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 0-9/100"))
        }
        var called = false

        val result = api.track("A", 100, 1, rangeFrom = 50) { called = true }

        assertEquals(ApiResult.Fail(ApiFailure.RangeMismatch), result)
        assertFalse(called)
    }

    @Test
    fun `TA-029 206 の Content-Range の開始が要求位置と同じなら本文を渡す`() = runTest {
        val api = client {
            respond("tail", HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 50-53/54"))
        }

        val result = api.track("A", 54, 1, rangeFrom = 50) { Drain(it.source).all().decodeToString() to it.startAt }

        assertEquals(ApiResult.Ok("tail" to 50L), result)
    }

    @Test
    fun `TA-030 Range を付けたのに 200 が来たら先頭からの本文として渡す`() = runTest {
        val api = client { respond("whole", HttpStatusCode.OK) }

        val result = api.track("A", 5, 1, rangeFrom = 3) { Drain(it.source).all().decodeToString() to it.startAt }

        assertEquals(ApiResult.Ok("whole" to 0L), result)
    }

    @Test
    fun `TA-031 曲の取得の失敗は X6-5 の対応表どおりに分類される`() = runTest {
        val expected = listOf<Triple<Int, String?, ApiFailure>>(
            Triple(409, null, ApiFailure.Stale),
            Triple(416, null, ApiFailure.RangeNotSatisfiable),
            Triple(404, null, ApiFailure.FileMissing),
            Triple(401, "clock_skew", ApiFailure.Rejected(ErrorDetail.ClockSkew, keyMismatch = false)),
            Triple(401, "replay", ApiFailure.Rejected(ErrorDetail.Replay, keyMismatch = false)),
            Triple(401, null, ApiFailure.Rejected(ErrorDetail.NoDetail, keyMismatch = true)),
            Triple(429, null, ApiFailure.Rejected(ErrorDetail.RateLimited, keyMismatch = false)),
            Triple(403, null, ApiFailure.Rejected(null, keyMismatch = false)),
            Triple(400, null, ApiFailure.ServerFault),
            Triple(405, null, ApiFailure.ServerFault),
            Triple(413, null, ApiFailure.ServerFault),
            Triple(500, null, ApiFailure.ServerFault),
            Triple(503, null, ApiFailure.XmlUnavailable),
        )
        for ((status, detail, want) in expected) {
            val api = client { failure(status, detail) }

            val result = api.track("A", 100, 1, rangeFrom = 0) { }

            assertEquals(ApiResult.Fail(want), result, "$status $detail")
        }
    }

    @Test
    fun `TA-031 マニフェストと digest の 400 と 404 と 500 は E なしの停止で 503 は E4`() = runTest {
        for ((status, want) in listOf(400 to ApiFailure.ServerFault, 404 to ApiFailure.ServerFault, 500 to ApiFailure.ServerFault, 503 to ApiFailure.XmlUnavailable)) {
            assertEquals(ApiResult.Fail(want), client { failure(status) }.manifest(IDS), "P1 $status")
            assertEquals(ApiResult.Fail(want), client { failure(status) }.digest(IDS), "P3 $status")
        }
    }

    @Test
    fun `TA-031 報告の 204 は成功で 400 と 500 は E なしの停止`() = runTest {
        assertEquals(ApiResult.Ok(Unit), client { respond("", HttpStatusCode.NoContent) }.report("{}"))
        assertEquals(ApiResult.Fail(ApiFailure.ServerFault), client { failure(400) }.report("{}"))
    }

    @Test
    fun `TA-032 全要求に署名ヘッダが付き再開のたびに nonce が変わる`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = client {
            seen += it
            respond("x", HttpStatusCode.OK)
        }

        api.track("A", 1, 1, rangeFrom = 0) { }
        api.track("A", 1, 1, rangeFrom = 0) { }
        api.digest(IDS)

        for (request in seen) {
            for (name in listOf(SignatureHeaderNames.DEVICE, SignatureHeaderNames.TIMESTAMP, SignatureHeaderNames.NONCE, SignatureHeaderNames.SIGNATURE)) {
                assertTrue(request.headers[name] != null, name)
            }
            assertEquals(DEVICE, request.headers[SignatureHeaderNames.DEVICE])
        }
        assertEquals(3, seen.mapTo(HashSet()) { it.headers[SignatureHeaderNames.NONCE] }.size)
        assertNotEquals(seen[0].headers[SignatureHeaderNames.SIGNATURE], seen[1].headers[SignatureHeaderNames.SIGNATURE])
    }

    @Test
    fun `TA-032 署名の対象に Range を含めない`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val api = client {
            seen += it
            respond("x", HttpStatusCode.OK)
        }

        api.track("A", 1, 1, rangeFrom = 0) { }
        api.track("A", 1, 1, rangeFrom = 5) { }

        assertNull(seen[0].headers[HttpHeaders.Range])
        assertEquals("bytes=5-", seen[1].headers[HttpHeaders.Range])
    }

    @Test
    fun `TA-033 曲の取得では圧縮を要求せずマニフェストでは gzip を要求して展開する`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("{\"a\":1}".encodeToByteArray()) } }.toByteArray()
        val api = client {
            seen += it
            if (it.url.encodedPath == "/v1/manifest") {
                respond(gz, HttpStatusCode.OK, headersOf(HttpHeaders.ContentEncoding, "gzip"))
            } else {
                respond("x", HttpStatusCode.OK)
            }
        }

        api.track("A", 1, 1, rangeFrom = 0) { }
        val manifest = api.manifest(IDS)

        assertFalse("gzip" in seen[0].headers[HttpHeaders.AcceptEncoding].orEmpty())
        assertEquals("gzip", seen[1].headers[HttpHeaders.AcceptEncoding])
        assertEquals(ApiResult.Ok("{\"a\":1}"), manifest)
    }

    @Test
    fun `TA-224 P1 と P3 は POST で本文に選んだ ID の配列を JSON で送る`() = runTest {
        val seen = mutableListOf<Pair<HttpRequestData, String>>()
        val api = client {
            seen += it to (it.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
            if (it.url.encodedPath == "/v1/digest") respond("""{"content_digest":"d"}""", HttpStatusCode.OK) else respond("{}", HttpStatusCode.OK)
        }

        api.manifest(IDS)
        api.digest(IDS)

        for ((request, body) in seen) {
            assertEquals(io.ktor.http.HttpMethod.Post, request.method)
            assertEquals("""{"playlist_ids":["00000000000000A1","00000000000000B2"]}""", body)
        }
        assertEquals(listOf("/v1/manifest", "/v1/digest"), seen.map { it.first.url.encodedPath })
    }

    @Test
    fun `TA-224 P7 は GET でプレイリストの本文を返し 503 は E4 にする`() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val ok = client {
            seen += it
            respond("""{"playlists":[]}""", HttpStatusCode.OK)
        }

        assertEquals(ApiResult.Ok("""{"playlists":[]}"""), ok.playlists())
        assertEquals(io.ktor.http.HttpMethod.Get, seen.single().method)
        assertEquals("/v1/playlists", seen.single().url.encodedPath)
        assertEquals(ApiResult.Fail(ApiFailure.XmlUnavailable), client { failure(503) }.playlists())
    }

    @Test
    fun `TA-065 接続の確立は V29 で P3 の応答は V53 を待ち上限にする`() = runTest {
        var timeout: Any? = null
        val api = client {
            timeout = it.getCapabilityOrNull(HttpTimeoutCapability)
            respond("""{"content_digest":"d"}""", HttpStatusCode.OK)
        }

        api.digest(IDS)

        val config = assertIs<io.ktor.client.plugins.HttpTimeoutConfig>(timeout)
        assertEquals(SyncValues.CONNECT_WAIT_MS, config.connectTimeoutMillis)
        assertEquals(SyncValues.CONNECT_WAIT_MS + SyncValues.DIGEST_RESPONSE_WAIT_MS, config.requestTimeoutMillis)
    }

    @Test
    fun `TA-065 接続の時間切れは切断として分類される`() = runTest {
        val api = client { throw ConnectTimeoutException("時間切れ", null) }

        assertEquals(ApiResult.Fail(ApiFailure.Disconnected), api.digest(IDS))
    }

    @Test
    fun `証明書の不一致で切った接続は切断と区別される`() = runTest {
        val api = client(pinMismatched = { true }) { throw java.io.IOException("切断") }

        assertEquals(ApiResult.Fail(ApiFailure.PinMismatch), api.digest(IDS))
    }

    @Test
    fun `署名に失敗したときは例外にせず鍵の不一致の E2 として返す`() = runTest {
        val broken = PsfoxClient(
            http = HttpClient(MockEngine { respond("", HttpStatusCode.OK) }),
            endpoint = ENDPOINT,
            crypto = object : CryptoProvider by CountingCrypto() {
                override fun hmacSha256(key: SigningKeyRef, message: ByteArray): ByteArray = throw java.security.GeneralSecurityException("鍵が使えない")
            },
            keyRef = SigningKeyRef.KeystoreAlias("alias"),
            deviceId = DEVICE,
            nowSeconds = { 0 },
        )

        assertEquals(ApiResult.Fail(ApiFailure.Rejected(ErrorDetail.NoDetail, keyMismatch = true)), broken.digest(IDS))
    }

    @Test
    fun `TA-028 固定した SPKI と同じ証明書の PC には届き違う公開鍵の証明書は切断して本文を読まない`() = runTest {
        val keystore = generateCertificate(null as File?, algorithm = "SHA256withRSA", keyAlias = "t", keyPassword = "changeit", jksPassword = "changeit", keySizeInBits = 2048)
        val certificate = keystore.getCertificate("t") as X509Certificate
        val served = java.util.concurrent.atomic.AtomicInteger()
        val server = startTlsServer(keystore, served)
        try {
            val good = SpkiPinTrustManager(spkiSha256(certificate))
            val reached = PsfoxClient(createTrustedHttpClient(good), Endpoint(Transport.Lan, "127.0.0.1", server.address.port), CountingCrypto(), SigningKeyRef.KeystoreAlias("a"), DEVICE, { 0 }, { good.mismatched })
            assertEquals(ApiResult.Ok("d"), reached.digest(IDS))
            assertEquals(1, served.get())

            val other = SpkiPinTrustManager(ByteArray(32) { 1 })
            val refused = PsfoxClient(createTrustedHttpClient(other), Endpoint(Transport.Lan, "127.0.0.1", server.address.port), CountingCrypto(), SigningKeyRef.KeystoreAlias("a"), DEVICE, { 0 }, { other.mismatched })
            assertEquals(ApiResult.Fail(ApiFailure.PinMismatch), refused.digest(IDS))
            assertEquals(1, served.get(), "不一致の接続では要求が届かない")
        } finally {
            server.stop(0)
        }
    }

    private fun startTlsServer(keystore: KeyStore, served: java.util.concurrent.atomic.AtomicInteger): HttpsServer {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/v1/digest") { exchange ->
                served.incrementAndGet()
                val body = """{"content_digest":"d"}""".encodeToByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }
}
