package playlistsyncfox.shared.auth

import org.bouncycastle.crypto.ec.CustomNamedCurves
import playlistsyncfox.shared.crypto.EcPoint
import playlistsyncfox.shared.crypto.TestCrypto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class Spake2Test {
    private val crypto = TestCrypto()

    private fun String.point() = EcPoint(hexToByteArray())

    @Test
    fun `TS-103 RFC 9382 のベクターで両側の値 pA と pB が一致する`() {
        rfc9382Vectors.forEachIndexed { index, v ->
            val w = v.w.hexToByteArray()
            assertContentEquals(v.pA.hexToByteArray(), initiatorShare(crypto, v.x.hexToByteArray(), w).encoded, "pA #$index")
            assertContentEquals(v.pB.hexToByteArray(), responderShare(crypto, v.y.hexToByteArray(), w).encoded, "pB #$index")
        }
    }

    @Test
    fun `TS-103 RFC 9382 のベクターで両側が同じ共有点 K と転写 TT を得る`() {
        rfc9382Vectors.forEachIndexed { index, v ->
            val w = v.w.hexToByteArray()
            val kInitiator = initiatorSharedPoint(crypto, v.x.hexToByteArray(), w, v.pB.point())
            val kResponder = responderSharedPoint(crypto, v.y.hexToByteArray(), w, v.pA.point())
            assertContentEquals(v.k.hexToByteArray(), kInitiator.encoded, "K 端末 #$index")
            assertContentEquals(v.k.hexToByteArray(), kResponder.encoded, "K PC #$index")

            val tt = transcript(v.a.encodeToByteArray(), v.b.encodeToByteArray(), v.pA.point(), v.pB.point(), kInitiator, w)
            assertContentEquals(v.tt.hexToByteArray(), tt, "TT #$index")
        }
    }

    @Test
    fun `TS-103 RFC 9382 のベクターで鍵 Ke KcA KcB と確認の印が一致する`() {
        rfc9382Vectors.forEachIndexed { index, v ->
            val keys = deriveKeys(crypto, v.tt.hexToByteArray(), ByteArray(0))
            assertContentEquals(v.ke.hexToByteArray(), keys.ke, "Ke #$index")
            assertContentEquals(v.kcA.hexToByteArray(), keys.kcA, "KcA #$index")
            assertContentEquals(v.kcB.hexToByteArray(), keys.kcB, "KcB #$index")
            assertContentEquals(v.confA.hexToByteArray(), crypto.hmacSha256(keys.kcA, keys.transcript), "A conf #$index")
            assertContentEquals(v.confB.hexToByteArray(), crypto.hmacSha256(keys.kcB, keys.transcript), "B conf #$index")
        }
    }

    @Test
    fun `TS-103 定数点 M と N は RFC 9382 の圧縮形式の値と同じ点になる`() {
        val curve = CustomNamedCurves.getByName("secp256r1").curve
        val compressedM = "02886e2f97ace46e55ba9dd7242579f2993b64e16ef3dcab95afd497333d8fa12f"
        val compressedN = "03d8bbd6c639c62937b04d997f38c3770719c629d7014d49a24b4f98baa1292b49"

        assertContentEquals(curve.decodePoint(compressedM.hexToByteArray()).getEncoded(false), M.encoded)
        assertContentEquals(curve.decodePoint(compressedN.hexToByteArray()).getEncoded(false), N.encoded)
    }

    @Test
    fun `TS-103 コードが同じなら端末と PC が同じ K に至る`() {
        val shared = TestCrypto(CODE_DRAW, scalar(1), scalar(0x40))
        val host = openPairing(shared, PairingState.Closed(), 0).state
        val initiation = startPairing(shared, DEVICE_ID, CODE)
        val started = host.sendStart(shared, initiation)
        val confirmation = assertNotNull(confirmPairing(shared, initiation, PC_ID, started.value().encoded, SPKI_HASH))
        var savedKey: ByteArray? = null
        val confirmed = handlePairingConfirm(shared, started.state, DEVICE_ID, confirmation.macD, 0) { _, key ->
            savedKey = key
            true
        }

        val deviceKey = assertNotNull(finishPairing(shared, confirmation, confirmed.value()))
        assertContentEquals(savedKey, deviceKey)
    }

    @Test
    fun `TS-103 乱数は端末の x の次に PC の y を引く`() {
        val shared = TestCrypto(CODE_DRAW, scalar(1), scalar(0x40))
        val host = openPairing(shared, PairingState.Closed(), 0).state
        val initiation = startPairing(shared, DEVICE_ID, CODE)
        val started = host.sendStart(shared, initiation)

        val w = passwordScalar(crypto, CODE)
        assertContentEquals(initiatorShare(crypto, scalar(1), w).encoded, initiation.x.encoded)
        assertContentEquals(responderShare(crypto, scalar(0x40), w).encoded, started.value().encoded)
    }

    @Test
    fun `TS-104 端末のコードが 1 桁違うと PC は MAC_d を拒否し K を保存しない`() {
        val (hostCrypto, host) = openHost(0, scalar(0x40))
        val initiation = deviceStart(code = "123457")
        val started = host.sendStart(hostCrypto, initiation)
        val confirmation = assertNotNull(confirmPairing(crypto, initiation, PC_ID, started.value().encoded, SPKI_HASH))
        var saved = false

        val confirmed = handlePairingConfirm(hostCrypto, started.state, DEVICE_ID, confirmation.macD, 0) { _, _ ->
            saved = true
            true
        }

        assertEquals(PairingFailure.MacMismatch, confirmed.failure())
        assertFalse(saved)
    }

    @Test
    fun `TS-105 端末が見た SPKI のハッシュが 1 バイト違うと PC は MAC_d を拒否する`() {
        val (hostCrypto, host) = openHost(0, scalar(0x40))
        val initiation = deviceStart()
        val started = host.sendStart(hostCrypto, initiation)
        val seenByDevice = SPKI_HASH.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val confirmation = assertNotNull(confirmPairing(crypto, initiation, PC_ID, started.value().encoded, seenByDevice))

        val confirmed = handlePairingConfirm(hostCrypto, started.state, DEVICE_ID, confirmation.macD, 0) { _, _ -> true }

        assertEquals(PairingFailure.MacMismatch, confirmed.failure())
    }

    @Test
    fun `TS-155 PC が返す MAC_p を 1 バイト変えると端末は K に至らない`() {
        val (hostCrypto, host) = openHost(0, scalar(0x40))
        val initiation = deviceStart()
        val started = host.sendStart(hostCrypto, initiation)
        val confirmation = assertNotNull(confirmPairing(crypto, initiation, PC_ID, started.value().encoded, SPKI_HASH))
        val macP = handlePairingConfirm(hostCrypto, started.state, DEVICE_ID, confirmation.macD, 0) { _, _ -> true }.value()
        macP[0] = (macP[0] + 1).toByte()

        assertNull(finishPairing(crypto, confirmation, macP))
    }

    @Test
    fun `TS-106 曲線上にない点の X を PC は書式の誤りとして拒否する`() {
        val (hostCrypto, host) = openHost()
        val offCurve = ByteArray(65) { 1 }.also { it[0] = 4 }

        val step = handlePairingStart(hostCrypto, host, DEVICE_ID, offCurve, 0, PC_ID, SPKI_HASH, 0)

        assertEquals(PairingFailure.BadRequest, step.failure())
    }

    @Test
    fun `TS-107 無限遠点の X を PC は拒否する`() {
        val (hostCrypto, host) = openHost()

        listOf(ByteArray(65), byteArrayOf(0)).forEach { infinity ->
            val step = handlePairingStart(hostCrypto, host, DEVICE_ID, infinity, 0, PC_ID, SPKI_HASH, 0)
            assertEquals(PairingFailure.BadRequest, step.failure())
        }
    }

    @Test
    fun `TS-142 コードから導く w は手で計算した値と一致する`() {
        assertContentEquals(
            "45fa7bedbf01204d8fbcddd98dbc9925f5d808afbd6588c5f7fba7b9792dd0c5".hexToByteArray(),
            passwordScalar(crypto, "123456"),
        )
    }
}
