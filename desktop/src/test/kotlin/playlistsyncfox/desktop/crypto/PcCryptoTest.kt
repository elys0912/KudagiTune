package playlistsyncfox.desktop.crypto

import playlistsyncfox.shared.crypto.EcPoint
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun hex(text: String) = text.hexToByteArray()

private fun scalarOf(value: Int) = ByteArray(32).also { it[31] = value.toByte() }

// P-256 の位数 n（SEC 2 / FIPS 186）
private val ORDER = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")

// 基点 G、2G、3G（FIPS 186 / SEC 2 の P-256 の既知の値）を非圧縮形式で
private const val G = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
private const val G2 = "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
    "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
private const val G3 = "045ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c" +
    "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032"

class PcCryptoTest {
    private val crypto = PcCrypto()

    @Test
    fun `TD-54 SHA-256 の標準の例 abc と一致する`() {
        assertContentEquals(
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            crypto.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `TD-54 HMAC-SHA256 の RFC 4231 のテストケース 1 と一致する`() {
        assertContentEquals(
            hex("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"),
            crypto.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".encodeToByteArray()),
        )
    }

    @Test
    fun `TD-54 HKDF-SHA256 の RFC 5869 の例 A1 と一致する`() {
        val okm = crypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() }, 42)

        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `TD-54 HKDF-SHA256 の RFC 5869 の例 A3 の塩と情報が空の場合と一致する`() {
        val okm = crypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)

        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun `TD-54 P-256 の基点の 1 倍と 2 倍が既知の座標と一致する`() {
        assertContentEquals(hex(G), crypto.multiplyBase(scalarOf(1)).encoded)
        assertContentEquals(hex(G2), crypto.multiplyBase(scalarOf(2)).encoded)
    }

    @Test
    fun `TD-54 P-256 の G と 2G の加算が既知の 3G と一致する`() {
        val sum = crypto.add(EcPoint(hex(G)), EcPoint(hex(G2)))

        assertContentEquals(hex(G3), sum.encoded)
    }

    @Test
    fun `TD-54 P-256 の任意の点の定数倍と符号反転が既知の値と一致する`() {
        assertContentEquals(hex(G3), crypto.multiply(EcPoint(hex(G)), scalarOf(3)).encoded)
        // -G は x が同じで y が p - y
        val negated = crypto.negate(EcPoint(hex(G))).encoded
        assertContentEquals(hex(G).copyOfRange(0, 33), negated.copyOfRange(0, 33))
        assertContentEquals(hex(G), crypto.negate(EcPoint(negated)).encoded)
    }

    @Test
    fun `TD-54 位数以上のスカラーは位数で割った余りとして扱う`() {
        // n + 1 は 1 と同じ
        val nPlusOne = ORDER.copyOf().also { it[31] = (it[31] + 1).toByte() }

        assertContentEquals(hex(G), crypto.multiplyBase(nPlusOne).encoded)
    }

    @Test
    fun `TD-54 スカラーが位数ちょうどでも例外を投げず曲線上の点としては復号されない`() {
        val atInfinity = crypto.multiplyBase(ORDER)

        assertNull(crypto.decodePoint(atInfinity.encoded))
    }

    @Test
    fun `TD-54 途中の点が無限遠点になっても加算と定数倍は例外を投げない`() {
        val g = EcPoint(hex(G))
        val infinity = crypto.add(g, crypto.negate(g))

        val doubled = crypto.multiply(infinity, scalarOf(5))
        val added = crypto.add(infinity, g)

        assertContentEquals(g.encoded, added.encoded)
        assertNull(crypto.decodePoint(doubled.encoded))
    }

    @Test
    fun `TD-54 点の復号は形式違い・曲線外・無限遠点を null にする`() {
        val offCurve = hex(G).also { it[64] = (it[64] + 1).toByte() }
        val compressed = hex(G).copyOf(33).also { it[0] = 2 }

        assertNotNull(crypto.decodePoint(hex(G)))
        assertNull(crypto.decodePoint(offCurve))
        assertNull(crypto.decodePoint(compressed))
        assertNull(crypto.decodePoint(ByteArray(65)))
        assertNull(crypto.decodePoint(ByteArray(0)))
    }

    @Test
    fun `TD-54 定数時間の比較は長さが違えば偽、同じ内容なら真`() {
        assertEquals(true, crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }
}
