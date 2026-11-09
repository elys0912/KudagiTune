package playlistsyncfox.shared.crypto

import kotlin.test.Test
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
    fun `TS-177 SHA-256 の値からも先頭 12 バイトの 24 桁を 4 桁ずつ空白で区切った指紋になる`() {
        val sha256OfAbc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".hexToByteArray()

        assertEquals("ba78 16bf 8f01 cfea 4141 40de", formatFingerprintOfHash(sha256OfAbc))
    }
}
