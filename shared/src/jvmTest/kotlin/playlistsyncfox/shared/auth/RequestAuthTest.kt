package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.SigningKeyRef
import playlistsyncfox.shared.crypto.TestCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RequestAuthTest {
    private val crypto = TestCrypto()
    private val key = ByteArray(32) { 7 }
    private val now = 1_700_000_000L
    private val startedAt = now - 1_000
    private val tolerance = AuthValues.CLOCK_TOLERANCE_SECONDS
    private val body = "{\"a\":1}".encodeToByteArray()

    private fun request(timestamp: Long = now, nonce: Byte = 0, deviceId: String = DEVICE_ID) = SignedRequest(
        method = "post",
        pathAndQuery = "/v1/result?x=1",
        timestamp = timestamp,
        nonce = ByteArray(16) { (it + nonce).toByte() },
        deviceId = deviceId,
        body = body,
    )

    private fun signatureOf(request: SignedRequest) = signRequest(crypto, SigningKeyRef.KeyBytes(key), request).signature

    private fun verify(
        request: SignedRequest,
        signature: String = signatureOf(request),
        nonces: NonceStore = NonceStore(),
        findKey: (String) -> ByteArray? = { key },
        at: Long = now,
        started: Long = startedAt,
    ) = verifyRequest(crypto, request, signature, at, started, findKey, nonces)

    private fun AuthResult.failure() = (this as AuthResult.Failure).reason

    @Test
    fun `TS-115 固定の入力から 7 行の文字列が一致し末尾に LF が付かない`() {
        val expected = listOf(
            "PSFOX1",
            "POST",
            "/v1/result?x=1",
            "1700000000",
            "AAECAwQFBgcICQoLDA0ODw",
            DEVICE_ID,
            "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
        ).joinToString("\n")

        assertEquals(expected, buildStringToSign(crypto, request()))
    }

    @Test
    fun `TS-116 本文が空なら 7 行目は空の列の SHA-256 の小文字 16 進になる`() {
        val empty = SignedRequest("GET", "/v1/manifest", now, ByteArray(16), DEVICE_ID, ByteArray(0))

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            buildStringToSign(crypto, empty).lines()[6],
        )
    }

    @Test
    fun `TS-118 RFC 4231 の鍵と文字列から既知の値がパディングなしの base64url で得られる`() {
        val rfc4231Key = SigningKeyRef.KeyBytes(ByteArray(20) { 0x0b })

        assertEquals("sDRMYdjbOFNcqK_OrwvxK4gdwgDJgz2nJuk3bC4yz_c", sign(crypto, rfc4231Key, "Hi There"))
    }

    @Test
    fun `TS-118 署名ヘッダは device と timestamp と nonce と署名を持つ`() {
        val headers = signRequest(crypto, SigningKeyRef.KeyBytes(key), request())

        assertEquals(DEVICE_ID, headers.device)
        assertEquals("1700000000", headers.timestamp)
        assertEquals("AAECAwQFBgcICQoLDA0ODw", headers.nonce)
        assertEquals("KxLY4SNiJlgaKqOe6zO1PNxMkTPZX8Ys3DjOLn2L594", headers.signature)
    }

    @Test
    fun `TS-119 現在時刻との差が V12 ちょうど過去なら通る`() {
        assertIs<AuthResult.Success>(verify(request(now - tolerance)))
    }

    @Test
    fun `TS-119 現在時刻との差が V12 ちょうど未来なら通る`() {
        assertIs<AuthResult.Success>(verify(request(now + tolerance)))
    }

    @Test
    fun `TS-120 差が V12 を 1 秒超えた過去は clock_skew`() {
        assertEquals(AuthFailure.ClockSkew, verify(request(now - tolerance - 1)).failure())
    }

    @Test
    fun `TS-120 差が V12 を 1 秒超えた未来は clock_skew`() {
        assertEquals(AuthFailure.ClockSkew, verify(request(now + tolerance + 1)).failure())
    }

    @Test
    fun `TS-121 PC の起動時刻の 1 秒前のタイムスタンプは V12 の幅の内でも clock_skew`() {
        val started = now - 100

        assertEquals(AuthFailure.ClockSkew, verify(request(started - 1), started = started).failure())
    }

    @Test
    fun `TS-122 PC の起動時刻ちょうどのタイムスタンプは通る`() {
        val started = now - 100

        assertIs<AuthResult.Success>(verify(request(started), started = started))
    }

    @Test
    fun `TS-123 署名が合う同じ要求の 2 回目は replay`() {
        val nonces = NonceStore()
        val request = request()

        assertIs<AuthResult.Success>(verify(request, nonces = nonces))
        assertEquals(AuthFailure.Replay, verify(request, nonces = nonces).failure())
    }

    @Test
    fun `TS-124 署名が合わない要求は記録に残らず同じ nonce の正しい要求が通る`() {
        val nonces = NonceStore()
        val request = request()

        assertEquals(AuthFailure.Unauthorized, verify(request, signature = "AAAA", nonces = nonces).failure())
        assertIs<AuthResult.Success>(verify(request, nonces = nonces))
    }

    @Test
    fun `TS-125 未登録の端末は署名の誤りと同じ detail なしの失敗になる`() {
        val request = request()

        val unregistered = verify(request, findKey = { null }).failure()
        val wrongSignature = verify(request, signature = "AAAA").failure()

        assertEquals(wrongSignature, unregistered)
        assertEquals(null, unregistered.detail)
    }

    @Test
    fun `TS-125 時刻が許容の外なら未登録の端末も登録済みと同じ clock_skew になる`() {
        val late = request(now - tolerance - 1)

        assertEquals(verify(late).failure(), verify(late, findKey = { null }).failure())
        assertEquals(AuthFailure.ClockSkew, verify(late, findKey = { null }).failure())
    }

    @Test
    fun `TS-126 V12 の 2 倍を過ぎた記録は消える`() {
        val store = NonceStore()
        store.recordIfNew(DEVICE_ID, "old", 0)
        store.recordIfNew(DEVICE_ID, "other", 2 * tolerance + 1)

        assertTrue(store.recordIfNew(DEVICE_ID, "old", 2 * tolerance + 1))
    }

    @Test
    fun `TS-126 V12 の 2 倍を過ぎていない記録は残る`() {
        val store = NonceStore()
        store.recordIfNew(DEVICE_ID, "kept", 0)
        store.recordIfNew(DEVICE_ID, "other", 2 * tolerance)

        assertEquals(false, store.recordIfNew(DEVICE_ID, "kept", 2 * tolerance))
    }

    @Test
    fun `TS-127 件数の上限を超えると古い記録から消える`() {
        val store = NonceStore(limit = 2)
        listOf("a", "b", "c").forEach { store.recordIfNew(DEVICE_ID, it, 0) }

        assertEquals(false, store.recordIfNew(DEVICE_ID, "c", 0))
        assertEquals(false, store.recordIfNew(DEVICE_ID, "b", 0))
        assertTrue(store.recordIfNew(DEVICE_ID, "a", 0))
    }
}
