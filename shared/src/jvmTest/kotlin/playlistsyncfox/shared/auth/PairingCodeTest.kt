package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.TestCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PairingCodeTest {
    // V1 = 6 桁なので、2 の 32 乗以下で 10 の 6 乗の最大の倍数
    private val threshold = 4_294_000_000L

    @Test
    fun `TS-112 閾値の直前の乱数は採用されて V1 桁の文字列になる`() {
        val crypto = TestCrypto(draw(threshold - 1))

        assertEquals("999999", generatePairingCode(crypto))
        assertEquals(1, crypto.randomCalls)
    }

    @Test
    fun `TS-113 閾値以上の乱数は捨てて次の乱数を採用する`() {
        val crypto = TestCrypto(draw(threshold), draw(654_321))

        assertEquals("654321", generatePairingCode(crypto))
        assertEquals(2, crypto.randomCalls)
    }

    @Test
    fun `TS-114 余りが V1 桁に満たなければ先頭を 0 で埋める`() {
        assertEquals("000042", generatePairingCode(TestCrypto(draw(42))))
    }

    @Test
    fun `TS-138 閾値以上の乱数が V42 回続いたら次を引かずに失敗する`() {
        val crypto = TestCrypto(*Array(AuthValues.CODE_MAX_DRAWS + 1) { draw(threshold) })

        assertNull(generatePairingCode(crypto))
        assertEquals(AuthValues.CODE_MAX_DRAWS, crypto.randomCalls)
    }
}
