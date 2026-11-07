package playlistsyncfox.android.net

import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.network.tls.certificates.generateCertificate
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.shared.auth.encodeBase64Url
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
private const val PC_ID = "00000000-0000-4000-8000-0000000000a1"
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private fun mockClient(
    seen: () -> ByteArray? = { null },
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
) = PairingClient(HttpClient(MockEngine(handler)) { install(HttpTimeout) }, "192.0.2.10", 47800, seen)

class PairingClientTest {
    @Test
    fun `TA-102 P5 は device_id と機種名と X を base64url で送り、応答の pc_id と Y を読む`() = runTest {
        val x = ByteArray(65) { it.toByte() }
        val y = ByteArray(65) { (it + 1).toByte() }
        var path = ""
        var body = ""
        val client = mockClient {
            path = it.url.encodedPath
            body = it.body.toByteArray().decodeToString()
            respond("""{"pc_id":"$PC_ID","y":"${encodeBase64Url(y)}"}""", HttpStatusCode.OK, JSON_HEADERS)
        }

        val reply = assertIs<PairingCall.Ok<StartReply>>(client.start(DEVICE_ID, "Model X", x)).value

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("/v1/pairing/start", path)
        assertEquals(DEVICE_ID, sent.getValue("device_id").jsonPrimitive.content)
        assertEquals("Model X", sent.getValue("device_name").jsonPrimitive.content)
        assertEquals(encodeBase64Url(x), sent.getValue("x").jsonPrimitive.content)
        assertEquals(PC_ID, reply.pcId)
        assertContentEquals(y, reply.y)
    }

    @Test
    fun `TA-102 P6 は device_id と MAC_d を送り、応答の MAC_p を読む`() = runTest {
        val macD = ByteArray(32) { 1 }
        val macP = ByteArray(32) { 2 }
        var path = ""
        var body = ""
        val client = mockClient {
            path = it.url.encodedPath
            body = it.body.toByteArray().decodeToString()
            respond("""{"mac_p":"${encodeBase64Url(macP)}"}""", HttpStatusCode.OK, JSON_HEADERS)
        }

        val result = assertIs<PairingCall.Ok<ByteArray>>(client.confirm(DEVICE_ID, macD))

        val sent = Json.parseToJsonElement(body).jsonObject
        assertEquals("/v1/pairing/confirm", path)
        assertEquals(encodeBase64Url(macD), sent.getValue("mac_d").jsonPrimitive.content)
        assertContentEquals(macP, result.value)
    }

    @Test
    fun `TA-121 PC の断りは状態の番号と detail をそのまま返す`() = runTest {
        val cases = listOf(
            Triple(403, """{"error":"E2","detail":"pairing_closed"}""", PairingCall.Refused(403, "pairing_closed")),
            Triple(403, """{"error":"E2","detail":"pairing_locked"}""", PairingCall.Refused(403, "pairing_locked")),
            Triple(403, """{"error":"E2","detail":"device_exists"}""", PairingCall.Refused(403, "device_exists")),
            Triple(401, """{"error":"E2"}""", PairingCall.Refused(401, null)),
            Triple(429, """{"error":"E2","detail":"rate_limited"}""", PairingCall.Refused(429, "rate_limited")),
            Triple(400, "", PairingCall.Refused(400, null)),
            Triple(500, "", PairingCall.Refused(500, null)),
        )
        for ((status, text, want) in cases) {
            val client = mockClient { respond(text, HttpStatusCode.fromValue(status), JSON_HEADERS) }

            assertEquals(want, client.confirm(DEVICE_ID, ByteArray(32)), "$status $text")
        }
    }

    @Test
    fun `TA-102 切断は Disconnected、読めない本文と足りない項目は BadReply`() = runTest {
        assertEquals(PairingCall.Disconnected, mockClient { throw java.io.IOException("切断") }.confirm(DEVICE_ID, ByteArray(32)))
        assertEquals(PairingCall.BadReply, mockClient { respond("not json", HttpStatusCode.OK) }.confirm(DEVICE_ID, ByteArray(32)))
        assertEquals(PairingCall.BadReply, mockClient { respond("""{"mac_p":"***"}""", HttpStatusCode.OK) }.confirm(DEVICE_ID, ByteArray(32)))
        assertEquals(PairingCall.BadReply, mockClient { respond("""{"pc_id":"$PC_ID"}""", HttpStatusCode.OK) }.start(DEVICE_ID, "m", ByteArray(65)))
    }

    @Test
    fun `TA-102 固定前の接続は未知の自己署名の証明書でも通り、提示された SPKI のハッシュを記録する`() = runTest {
        val keystore = generateCertificate(null as File?, algorithm = "SHA256withRSA", keyAlias = "t", keyPassword = "changeit", jksPassword = "changeit", keySizeInBits = 2048)
        val certificate = keystore.getCertificate("t") as X509Certificate
        val server = startTlsServer(keystore)
        try {
            PairingClient.open("127.0.0.1", server.address.port).use { client ->
                assertNull(client.spkiHash)

                val result = client.confirm(DEVICE_ID, ByteArray(32))

                assertIs<PairingCall.Ok<ByteArray>>(result)
                assertContentEquals(spkiSha256(certificate), client.spkiHash)
            }
        } finally {
            server.stop(0)
        }
    }

    private fun startTlsServer(keystore: KeyStore): HttpsServer {
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, "changeit".toCharArray()) }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers.keyManagers, null, null) }
        return HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            httpsConfigurator = HttpsConfigurator(context)
            createContext("/v1/pairing/confirm") { exchange ->
                exchange.responseHeaders.add("Content-Type", "application/json")
                val body = """{"mac_p":"${encodeBase64Url(ByteArray(32))}"}""".encodeToByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
    }
}
