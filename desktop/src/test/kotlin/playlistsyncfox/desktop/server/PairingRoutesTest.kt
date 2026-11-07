package playlistsyncfox.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.MarkingCipher
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.vault.FileSecretStore
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.shared.auth.PairingInitiation
import playlistsyncfox.shared.auth.SignatureHeaderNames
import playlistsyncfox.shared.auth.confirmPairing
import playlistsyncfox.shared.auth.finishPairing
import playlistsyncfox.shared.auth.startPairing
import playlistsyncfox.shared.crypto.EcPoint
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC 9382 の P-256 の M（非圧縮形式）。w・M を作るために写した
private const val M_POINT = "04886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f" +
    "5ff355163e43ce224e0b0e65ff02ac8e5c7be09419c785e0ca547d55a12e2d20"

private val decoder = Base64.getUrlDecoder()

class PairingRoutesTest {
    private val dir: Path = createTempDirectory("psfox-pairing")
    private val deviceCrypto = PcCrypto()

    private fun openCode(f: ServerFixture): String =
        (f.pairing.view.value as PairingView.Open).code

    private fun startBody(deviceId: String, x: ByteArray, name: String = "テスト端末") =
        """{"device_id":"$deviceId","device_name":"$name","x":"${base64Url(x)}"}"""

    private fun beginPairing(f: ServerFixture): Pair<String, PairingInitiation> {
        f.pairing.open()
        val code = openCode(f)
        return code to startPairing(deviceCrypto, DEVICE, code)
    }

    private suspend fun HttpClient.p5(init: PairingInitiation): HttpResponse =
        postJson("/v1/pairing/start", startBody(init.deviceId, init.x.encoded))

    private suspend fun HttpClient.p6(deviceId: String, macD: ByteArray): HttpResponse =
        postJson("/v1/pairing/confirm", """{"device_id":"$deviceId","mac_d":"${base64Url(macD)}"}""")

    /** P5 と P6 を正しく済ませ、端末が得る署名用の鍵 K を返す。失敗なら null。 */
    private suspend fun HttpClient.pair(f: ServerFixture): ByteArray? {
        val (_, init) = beginPairing(f)
        val started = p5(init)
        if (started.status != HttpStatusCode.OK) return null
        val body = started.json()
        val y = decoder.decode(body.getValue("y").jsonPrimitive.content)
        val confirmation = assertNotNull(confirmPairing(deviceCrypto, init, body.getValue("pc_id").jsonPrimitive.content, y, SPKI_HASH))
        val confirmed = p6(DEVICE, confirmation.macD)
        if (confirmed.status != HttpStatusCode.OK) return null
        return finishPairing(deviceCrypto, confirmation, decoder.decode(confirmed.json().getValue("mac_p").jsonPrimitive.content))
    }

    @Test
    fun `TD-47 P6 の成功で鍵の保管と devices の両方に端末が入る`() {
        val f = ServerFixture(dir)

        withServer(f) { client ->
            val key = client.pair(f)

            assertNotNull(key)
            assertContentEquals(key, f.keyRing.find(DEVICE))
            assertContentEquals(key, f.store.read("device/$DEVICE"))
            assertEquals(listOf(DEVICE), f.settings.current.devices.map { it.deviceId })
            assertEquals("テスト端末", f.settings.current.devices.single().displayName)
        }
    }

    @Test
    fun `TD-47 P6 の MAC_d が不一致なら鍵の保管にも devices にも入らない`() {
        val f = ServerFixture(dir)

        withServer(f) { client ->
            val (_, init) = beginPairing(f)
            client.p5(init)

            val response = client.p6(DEVICE, ByteArray(32))

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertNull(f.keyRing.find(DEVICE))
            assertNull(f.store.read("device/$DEVICE"))
            assertTrue(f.settings.current.devices.isEmpty())
        }
    }

    @Test
    fun `TD-66 P6 の成功の後にサーバーと鍵の保管を作り直しても、同じ端末の署名つき要求が通る`() {
        val store = { FileSecretStore(dir.resolve("vault.bin"), MarkingCipher()) }
        val first = ServerFixture(dir, store())
        var key: ByteArray? = null
        withServer(first) { client -> key = client.pair(first) }
        assertNotNull(key)

        val second = ServerFixture(dir, store())
        second.useLibrary(dir.resolve("any.mp3").also { it.writeBytes(ByteArray(1)) })

        withServer(second) { client ->
            assertEquals(HttpStatusCode.OK, client.signed(second, HttpMethod.Post, "/v1/digest", SELECTION, key = key!!).status)
        }
    }

    @Test
    fun `TD-56 devices が 1 件あれば device_id が違う P5 も 403 の device_exists`() {
        val f = ServerFixture(dir).also { it.addDevice() }
        f.pairing.open()

        withServer(f) { client ->
            val other = startPairing(deviceCrypto, OTHER_DEVICE, openCode(f))
            val response = client.postJson("/v1/pairing/start", startBody(OTHER_DEVICE, other.x.encoded))

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("device_exists", response.detail())
        }
    }

    @Test
    fun `TD-56 devices が 0 件の P5 は進み、応答は pc_id と y`() {
        val f = ServerFixture(dir)

        withServer(f) { client ->
            val (_, init) = beginPairing(f)

            val response = client.p5(init)

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(setOf("pc_id", "y"), response.json().keys)
        }
    }

    @Test
    fun `TD-55 P6 の不一致は署名失敗に数えず、V43 回続けても 429 にならない`() {
        val f = ServerFixture(dir).also { it.useLibrary(createTrack()) }

        withServer(f) { client ->
            // 不一致のたびにコードを作り直し、P5 からやり直す（コードごとの試行は V2 に達しない）
            repeat(DesktopValues.SIGNATURE_FAILURE_LIMIT + 2) {
                val (_, init) = beginPairing(f)
                client.p5(init)
                assertEquals(HttpStatusCode.Unauthorized, client.p6(DEVICE, ByteArray(32)).status)
            }

            f.addDevice()
            assertEquals(HttpStatusCode.OK, client.signed(f, HttpMethod.Post, "/v1/digest", SELECTION).status)
        }
    }

    private fun createTrack(): Path = dir.resolve("t.mp3").also { it.writeBytes(ByteArray(3)) }

    @Test
    fun `P5 の X が w・M で途中の点が無限遠点になっても、普通の入力と同じ形で応答する`() {
        val f = ServerFixture(dir)

        withServer(f) { client ->
            f.pairing.open()
            val code = openCode(f)
            val w = f.crypto.hkdfSha256(code.encodeToByteArray(), ByteArray(0), "psfox v1 pake-password".encodeToByteArray(), 32)
            val xAtInfinity = f.crypto.multiply(EcPoint(M_POINT.hexToByteArray()), w)

            val attack = client.postJson("/v1/pairing/start", startBody(DEVICE, xAtInfinity.encoded))
            val normal = client.p5(startPairing(deviceCrypto, DEVICE, code))
            val wrongCode = client.p5(startPairing(deviceCrypto, DEVICE, if (code == "000000") "111111" else "000000"))

            assertEquals(HttpStatusCode.OK, normal.status)
            assertEquals(normal.status, attack.status)
            assertEquals(normal.status, wrongCode.status)
            assertEquals(normal.json().keys, attack.json().keys)
            assertEquals(normal.json().keys, wrongCode.json().keys)
            // y は普通の入力と同じ長さの有効な点（65 バイト）
            assertEquals(65, decoder.decode(attack.json().getValue("y").jsonPrimitive.content).size)
        }
    }

    @Test
    fun `P5 の X が曲線上にない点なら 400 で E を付けない`() {
        val f = ServerFixture(dir)

        withServer(f) { client ->
            f.pairing.open()
            val offCurve = ByteArray(65) { 1 }.also { it[0] = 4 }

            val response = client.postJson("/v1/pairing/start", startBody(DEVICE, offCurve))

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().isEmpty())
        }
    }

    // ---- 解除と鍵が無い端末（TD-75、TD-77） ----

    @Test
    fun `TD-75 解除すると鍵の保管と devices から消え、以後の署名つき要求は 401 になる`() {
        val f = ServerFixture(dir).also { it.addDevice(); it.useLibrary(createTrack()) }

        withServer(f) { client ->
            assertEquals(HttpStatusCode.OK, client.signed(f, HttpMethod.Post, "/v1/digest", SELECTION).status)

            f.registry.unpair(DEVICE)

            assertNull(f.store.read("device/$DEVICE"))
            assertTrue(f.settings.current.devices.isEmpty())
            assertEquals(HttpStatusCode.Unauthorized, client.signed(f, HttpMethod.Post, "/v1/digest", SELECTION).status)
        }
    }

    @Test
    fun `TD-75 解除の後は devices が 0 件なので P5 が進む`() {
        val f = ServerFixture(dir).also { it.addDevice() }
        f.registry.unpair(DEVICE)

        withServer(f) { client ->
            val (_, init) = beginPairing(f)

            assertEquals(HttpStatusCode.OK, client.p5(init).status)
        }
    }

    @Test
    fun `TD-77 起動時に鍵が読めない端末は署名つき要求が 401 になり、鍵が無い端末として扱われる`() {
        val f = ServerFixture(dir)
        f.settings.update { it.copy(devices = listOf(PairedDevice(DEVICE, "端末", "2026-01-01T00:00:00.000Z"))) }
        f.keyRing.load(listOf(DEVICE))
        f.useLibrary(createTrack())

        withServer(f) { client ->
            assertEquals(HttpStatusCode.Unauthorized, client.signed(f, HttpMethod.Post, "/v1/digest", SELECTION).status)
            assertEquals(setOf(DEVICE), f.keyRing.missing)
        }
    }

    // ---- ログ（TD-29） ----

    @Test
    fun `TD-29 署名失敗とペアリングの後のログに、鍵・コード・署名・印の値が含まれない`() {
        val f = ServerFixture(dir).also { it.useLibrary(createTrack()) }
        val secrets = mutableListOf<String>()
        var code = ""

        withServer(f) { client ->
            f.addDevice()
            val wrongKey = ByteArray(32) { 0x42 }
            val bad = sign(f, HttpMethod.Post, "/v1/digest", SELECTION, key = wrongKey)
            secrets += bad.signature
            client.get("/v1/digest") {
                header(SignatureHeaderNames.DEVICE, bad.device)
                header(SignatureHeaderNames.TIMESTAMP, bad.timestamp)
                header(SignatureHeaderNames.NONCE, bad.nonce)
                header(SignatureHeaderNames.SIGNATURE, bad.signature)
            }
            f.registry.unpair(DEVICE)

            val begun = beginPairing(f)
            code = begun.first
            val init = begun.second
            val started = client.p5(init).json()
            val confirmation = confirmPairing(
                deviceCrypto, init, PC_ID, decoder.decode(started.getValue("y").jsonPrimitive.content), SPKI_HASH,
            )!!
            val macP = client.p6(DEVICE, confirmation.macD).json().getValue("mac_p").jsonPrimitive.content
            val key = finishPairing(deviceCrypto, confirmation, decoder.decode(macP))!!
            secrets += listOf(base64Url(confirmation.macD), macP, base64Url(key), key.toHexString(), base64Url(DEVICE_KEY), DEVICE_KEY.toHexString(), base64Url(wrongKey))
            client.p6(DEVICE, ByteArray(32))
        }

        val log = Files.list(f.logDir).use { s -> s.toList().joinToString("\n") { String(it.readBytes()) } }
        assertTrue(log.contains("署名の検証に失敗"))
        for (value in secrets) assertFalse(log.contains(value), "ログに秘密の値が含まれている")
        // 6 桁のコードは、長い数字の列の一部でなく単独で現れないこと
        assertFalse(Regex("(?<![0-9])${code}(?![0-9])").containsMatchIn(log))
    }
}
