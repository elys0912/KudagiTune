package playlistsyncfox.shared.crypto

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CryptoVectorsTest {
    private val crypto = TestCrypto()

    @Test
    fun `TS-128 既知の SPKI の指紋は先頭 12 バイトの 24 桁を 4 桁ずつ空白で区切る`() {
        val spki = ByteArray(91) { it.toByte() }

        // 先頭 12 バイトまで。SHA-256 は 58d29459b2130a2e151252d408b95e6dac42…（別に計算した値）
        assertEquals("58d2 9459 b213 0a2e 1512 52d4", formatFingerprint(crypto, spki))
    }

    @Test
    fun `SHA-256 の値からも先頭 12 バイトの 24 桁を 4 桁ずつ空白で区切った指紋になる`() {
        val sha256OfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray()

        assertEquals("ba78 16bf 8f01 cfea 4141 40de", formatFingerprintOfHash(sha256OfAbc))
    }

    @Test
    fun `TS-130 SHA-256 の標準の例 abc から指紋が得られる`() {
        // NIST の SHA-256 の例 ba7816bf 8f01cfea 414140de…
        assertEquals("ba78 16bf 8f01 cfea 4141 40de", formatFingerprint(crypto, "abc".encodeToByteArray()))
    }

    @Test
    fun `TS-130 HKDF-SHA256 の RFC 5869 の例 A1 と一致する`() {
        val okm = crypto.hkdfSha256(
            ikm = ByteArray(22) { 0x0b },
            salt = ByteArray(13) { it.toByte() },
            info = ByteArray(10) { (0xf0 + it).toByte() },
            length = 42,
        )

        assertContentEquals(
            ("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf" + "34007208d5b887185865").hexToByteArray(),
            okm,
        )
    }

    @Test
    fun `TS-130 HKDF-SHA256 の RFC 5869 の例 A3 の塩と情報が空の場合と一致する`() {
        val okm = crypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)

        assertContentEquals(
            ("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d" + "9d201395faa4b61a96c8").hexToByteArray(),
            okm,
        )
    }

    @Test
    fun `TS-130 HMAC-SHA256 の RFC 4231 のテストケース 1 と一致する`() {
        val mac = crypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray())

        assertContentEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7".hexToByteArray(),
            mac,
        )
    }
}
