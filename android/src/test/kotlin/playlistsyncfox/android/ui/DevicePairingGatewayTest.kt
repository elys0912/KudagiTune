package playlistsyncfox.android.ui

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.net.PairingClient
import playlistsyncfox.android.sync.KeyVault
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.vault.DeviceCrypto
import playlistsyncfox.shared.auth.PairingOutcome
import playlistsyncfox.shared.auth.PairingState
import playlistsyncfox.shared.auth.encodeBase64Url
import playlistsyncfox.shared.auth.handlePairingConfirm
import playlistsyncfox.shared.auth.handlePairingStart
import playlistsyncfox.shared.auth.openPairing
import playlistsyncfox.shared.crypto.CryptoProvider
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 試料はすべて合成。IP は 192.0.2.0/24、名前は example.invalid。

private const val PC_ID = "00000000-0000-4000-8000-0000000000a1"
private const val CODE = "123456"
private val TARGET = PairingTarget("192.0.2.10", 47800, "psfox-01234567", kind = "lan", savedValue = "psfox-01234567")
private val JSON_HEADERS = headersOf(HttpHeaders.ContentType, "application/json")

private class MemoryVault : KeyVault {
    val keys = mutableMapOf<String, ByteArray>()
    var failImport = false

    override fun exists(alias: String) = alias in keys

    override fun import(alias: String, key: ByteArray): Boolean {
        if (failImport) return false
        keys[alias] = key.copyOf()
        return true
    }

    override fun delete(alias: String): Boolean = keys.remove(alias).let { true }
}

/** shared の PC 側の手順（H0〜H3）を、HTTP の応答に載せる偽の PC。コードは [CODE] で固定する。 */
private class FakePc {
    val spki = ByteArray(32) { 0x11 }
    var savedKey: ByteArray? = null
    var tamperMacP = false

    /** 指定すると、P5 をこの応答で断る。 */
    var refuseStart: Pair<Int, String?>? = null

    /** P6 の処理の直前に呼ぶ。 */
    var beforeConfirm: () -> Unit = {}

    private val unusedDeviceCrypto = DeviceCrypto(aliasHmac = { _, _ -> error("PC は別名を使わない") })
    private val draws = ArrayDeque(listOf(ByteArray(4) { (123456L shr (8 * (3 - it))).toByte() }, ByteArray(32) { (it + 7).toByte() }))
    private val crypto = object : CryptoProvider by unusedDeviceCrypto {
        override fun randomBytes(count: Int): ByteArray = draws.removeFirst()
    }
    private var state: PairingState = openPairing(crypto, PairingState.Closed(), 0).state

    val engine = MockEngine { request ->
        val body = Json.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
        fun text(name: String) = body.getValue(name).jsonPrimitive.content
        fun fail(status: Int, detail: String?) =
            respond("""{"error":"E2"${detail?.let { ""","detail":"$it"""" }.orEmpty()}}""", HttpStatusCode.fromValue(status), JSON_HEADERS)
        when (request.url.encodedPath) {
            "/v1/pairing/start" -> {
                refuseStart?.let { return@MockEngine fail(it.first, it.second) }
                val x = Base64.getUrlDecoder().decode(text("x"))
                val step = handlePairingStart(crypto, state, text("device_id"), x, 0, PC_ID, spki, 0)
                state = step.state
                when (val outcome = step.outcome) {
                    is PairingOutcome.Ok -> respond("""{"pc_id":"$PC_ID","y":"${encodeBase64Url(outcome.value.encoded)}"}""", HttpStatusCode.OK, JSON_HEADERS)
                    is PairingOutcome.Failed -> fail(outcome.failure.status, outcome.failure.detail)
                }
            }
            else -> {
                beforeConfirm()
                val macD = Base64.getUrlDecoder().decode(text("mac_d"))
                val step = handlePairingConfirm(crypto, state, text("device_id"), macD, 0) { _, key -> savedKey = key.copyOf(); true }
                state = step.state
                when (val outcome = step.outcome) {
                    is PairingOutcome.Ok -> {
                        val macP = outcome.value.copyOf().also { if (tamperMacP) it[0] = (it[0] + 1).toByte() }
                        respond("""{"mac_p":"${encodeBase64Url(macP)}"}""", HttpStatusCode.OK, JSON_HEADERS)
                    }
                    is PairingOutcome.Failed -> fail(outcome.failure.status, outcome.failure.detail)
                }
            }
        }
    }
}

private class Rig(val pc: FakePc = FakePc()) {
    /** 端末が TLS で見た PC の SPKI のハッシュ。 */
    var seen: ByteArray? = pc.spki
    val store = InMemoryStore()
    val vault = MemoryVault()
    private val crypto = DeviceCrypto(aliasHmac = { _, _ -> error("ペアリングでは別名を使わない") })
    private val gateway = DevicePairingGateway(store, PairingKeeper(store, vault) { 1_000L }, crypto, "Model X") { host, port ->
        PairingClient(HttpClient(pc.engine) { install(HttpTimeout) }, host, port) { seen }
    }

    suspend fun pair(code: String = CODE): List<PairingEvent> = gateway.pair(TARGET, code).toList()

    fun assertNothingSaved() {
        assertNull(store.pairingRow)
        assertTrue(vault.keys.isEmpty())
    }
}

class DevicePairingGatewayTest {
    @Test
    fun `TA-102 成功すると P5 の応答の時点で P5 の接続で見た SPKI の指紋を出し、続けて完了を返す`() = runTest {
        val rig = Rig()

        val events = rig.pair()

        assertEquals(PairingEvent.Fingerprint("1111 1111 1111 1111 1111 1111"), events[0])
        val completed = assertIs<PairingEvent.Completed>(events[1])
        assertEquals("psfox-01234567", completed.pcName)
        assertEquals(2, events.size)
    }

    @Test
    fun `TA-102 成功すると端末が Keystore へ入れる鍵と PC が保存する鍵は同じ値`() = runTest {
        val rig = Rig()

        rig.pair()

        assertContentEquals(assertNotNull(rig.pc.savedKey), rig.vault.keys.values.single())
        assertEquals(32, rig.vault.keys.values.single().size)
    }

    @Test
    fun `TA-102 成功すると T4 に SPKI のハッシュと pc_id と別名が入り、device_id は T7 に残る`() = runTest {
        val rig = Rig()

        val completed = rig.pair().filterIsInstance<PairingEvent.Completed>().single()

        val row = assertNotNull(rig.store.pairingRow)
        assertEquals(completed.deviceId, row.deviceId)
        assertEquals(completed.deviceId, rig.store.settings["device_id"])
        assertEquals(PC_ID, row.pcId)
        assertEquals("11".repeat(32), row.spkiHash)
        assertEquals("psfox-k3-${row.deviceId}", row.keyAlias)
        assertEquals(setOf(row.keyAlias), rig.vault.keys.keys)
    }

    @Test
    fun `TA-102 コードが違うと印が合わず、コード違いを返して何も保存しない`() = runTest {
        val rig = Rig()

        val events = rig.pair(code = "654321")

        assertEquals(PairingEvent.CodeMismatch, events.last())
        rig.assertNothingSaved()
    }

    @Test
    fun `TA-102 端末が見た SPKI が PC の証明書と食い違うと印が合わず、何も保存しない`() = runTest {
        val rig = Rig().also { it.seen = ByteArray(32) { 0x22 } }

        val events = rig.pair()

        assertEquals(PairingEvent.CodeMismatch, events.last())
        rig.assertNothingSaved()
    }

    @Test
    fun `TA-102 P6 の接続で P5 と別の証明書を見たら保存しない`() = runTest {
        val rig = Rig()
        rig.pc.beforeConfirm = { rig.seen = ByteArray(32) { 0x33 } }

        val events = rig.pair()

        assertIs<PairingEvent.Failed>(events.last())
        rig.assertNothingSaved()
    }

    @Test
    fun `TA-102 PC の印 MAC_p が合わないと鍵を保存せず、PC の解除を案内する失敗を返す`() = runTest {
        val rig = Rig().also { it.pc.tamperMacP = true }

        val failed = assertIs<PairingEvent.Failed>(rig.pair().last())

        assertTrue(failed.notice.rePair)
        rig.assertNothingSaved()
    }

    @Test
    fun `TA-102 鍵の取り込みに失敗すると完了を返さず、入れ直しの案内を返して T4 を作らない`() = runTest {
        val rig = Rig().also { it.vault.failImport = true }

        val events = rig.pair()

        assertEquals(PairingEvent.DeviceExists, events.last())
        rig.assertNothingSaved()
    }

    @Test
    fun `TA-121 P5 の PC の断りは画面の段に写る`() = runTest {
        val cases = listOf(
            (403 to "pairing_closed") to PairingEvent.Failed(PairingClosedNotice),
            (403 to "pairing_locked") to PairingEvent.AttemptsExhausted,
            (403 to "device_exists") to PairingEvent.DeviceExists,
            (429 to "rate_limited") to PairingEvent.Waiting,
        )
        for ((response, want) in cases) {
            val rig = Rig().also { it.pc.refuseStart = response }

            assertEquals(listOf(want), rig.pair(), "$response")
            rig.assertNothingSaved()
        }
    }

    @Test
    fun `TA-121 PC に接続できないと失敗を返し、何も保存しない`() = runTest {
        val store = InMemoryStore()
        val broken = DevicePairingGateway(store, PairingKeeper(store, MemoryVault()) { 1 }, DeviceCrypto({ _, _ -> error("") }), "Model X") { host, port ->
            PairingClient(HttpClient(MockEngine { throw java.io.IOException("切断") }) { install(HttpTimeout) }, host, port) { null }
        }

        val events = broken.pair(TARGET, CODE).toList()

        assertIs<PairingEvent.Failed>(events.single())
    }
}
