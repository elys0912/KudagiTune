package playlistsyncfox.android.vault

import playlistsyncfox.shared.crypto.EcPoint
import playlistsyncfox.shared.crypto.SigningKeyRef
import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 値は公開の原本：FIPS 186 / SEC 2 の P-256 の基点 G・2G・3G と位数 n、NIST の SHA-256 の例 abc、
// RFC 4231 のテストケース 1、RFC 5869 の付録 A のテストケース 1・3。desktop の PcCryptoTest と同じ。

private fun hex(text: String) = text.hexToByteArray()

private fun scalarOf(value: Int) = ByteArray(32).also { it[31] = value.toByte() }

private fun BigInteger.to32(): ByteArray {
    val bytes = toByteArray().let { if (it.size > 32) it.copyOfRange(it.size - 32, it.size) else it }
    return ByteArray(32 - bytes.size) + bytes
}

private val ORDER = hex("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551")

private const val G = "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296" +
    "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"
private const val G2 = "047cf27b188d034f7e8a52380304b51ac3c08969e277f21b35a60b48fc47669978" +
    "07775510db8ed040293d9ac69f7430dbba7dade63ce982299e04b79d227873d1"
private const val G3 = "045ecbe4d1a6330a44c8f7ef951d4bf165e6c6b721efada985fb41661bc6e7fd6c" +
    "8734640c4998ff7e374b06ce1a64a2ecd82ab036384fb83d9a79b127a27d5032"

private val HI_THERE_KEY = ByteArray(20) { 0x0b }
private const val HI_THERE_MAC = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"

class DeviceCryptoTest {
    private val crypto = DeviceCrypto(aliasHmac = { _, _ -> error("この試験では Keystore を使わない") })

    private fun infinity() = crypto.multiplyBase(ORDER)

    @Test
    fun `TA-120 SHA-256 は標準の例 abc と一致する`() {
        assertContentEquals(
            hex("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            crypto.sha256("abc".encodeToByteArray()),
        )
    }

    @Test
    fun `TA-120 HMAC-SHA256 は RFC 4231 のテストケース 1 と一致する`() {
        assertContentEquals(hex(HI_THERE_MAC), crypto.hmacSha256(HI_THERE_KEY, "Hi There".encodeToByteArray()))
    }

    @Test
    fun `TA-120 鍵の参照が値のときも HMAC は RFC 4231 のテストケース 1 と一致する`() {
        val mac = crypto.hmacSha256(SigningKeyRef.KeyBytes(HI_THERE_KEY), "Hi There".encodeToByteArray())

        assertContentEquals(hex(HI_THERE_MAC), mac)
    }

    @Test
    fun `TA-120 鍵の参照が別名のときは Keystore の実体に署名を任せ、同じ HMAC が得られる`() {
        val keystore = mapOf("alias-1" to HI_THERE_KEY)
        val viaAlias = DeviceCrypto(aliasHmac = { alias, message -> crypto.hmacSha256(keystore.getValue(alias), message) })

        assertContentEquals(hex(HI_THERE_MAC), viaAlias.hmacSha256(SigningKeyRef.KeystoreAlias("alias-1"), "Hi There".encodeToByteArray()))
    }

    @Test
    fun `TA-120 HKDF-SHA256 は RFC 5869 のテストケース 1 と一致する`() {
        val okm = crypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(13) { it.toByte() }, ByteArray(10) { (0xf0 + it).toByte() }, 42)

        assertContentEquals(hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm)
    }

    @Test
    fun `TA-120 HKDF-SHA256 は RFC 5869 のテストケース 3 の塩と情報が空の場合と一致する`() {
        val okm = crypto.hkdfSha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)

        assertContentEquals(hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"), okm)
    }

    @Test
    fun `TA-120 P-256 の基点の 1 倍は G と一致する`() {
        assertContentEquals(hex(G), crypto.multiplyBase(scalarOf(1)).encoded)
    }

    @Test
    fun `TA-120 P-256 の基点の 2 倍は 2G と一致する`() {
        assertContentEquals(hex(G2), crypto.multiplyBase(scalarOf(2)).encoded)
    }

    @Test
    fun `TA-120 P-256 の G と 2G の加算は 3G と一致する`() {
        assertContentEquals(hex(G3), crypto.add(EcPoint(hex(G)), EcPoint(hex(G2))).encoded)
    }

    @Test
    fun `TA-120 P-256 の任意の点 G の 3 倍は 3G と一致する`() {
        assertContentEquals(hex(G3), crypto.multiply(EcPoint(hex(G)), scalarOf(3)).encoded)
    }

    @Test
    fun `TA-120 P-256 の符号反転は x が同じで、2 回反転すると元に戻る`() {
        val negated = crypto.negate(EcPoint(hex(G))).encoded

        assertContentEquals(hex(G).copyOfRange(0, 33), negated.copyOfRange(0, 33))
        assertContentEquals(hex(G), crypto.negate(EcPoint(negated)).encoded)
    }

    @Test
    fun `X4-1 位数以上のスカラーは位数で割った余りとして扱う`() {
        val nPlusOne = BigInteger(1, ORDER).add(BigInteger.ONE).to32()

        assertContentEquals(hex(G), crypto.multiplyBase(nPlusOne).encoded)
    }

    @Test
    fun `X4-1 全ビットが立ったスカラーも余りに直して例外を投げない`() {
        val max = ByteArray(32) { 0xff.toByte() }
        val reduced = BigInteger(1, max).mod(BigInteger(1, ORDER)).to32()

        assertContentEquals(crypto.multiplyBase(reduced).encoded, crypto.multiplyBase(max).encoded)
    }

    @Test
    fun `X4-1 スカラーが位数ちょうどでも例外を投げず、結果は点として復号されない`() {
        assertNull(crypto.decodePoint(crypto.multiplyBase(ORDER).encoded))
    }

    @Test
    fun `X4-1 スカラーが 0 でも例外を投げず、結果は点として復号されない`() {
        assertNull(crypto.decodePoint(crypto.multiplyBase(ByteArray(32)).encoded))
    }

    @Test
    fun `X4-1 無限遠点との加算は例外を投げず、もう一方の点を返す`() {
        val g = EcPoint(hex(G))

        assertContentEquals(g.encoded, crypto.add(infinity(), g).encoded)
        assertContentEquals(g.encoded, crypto.add(g, infinity()).encoded)
    }

    @Test
    fun `X4-1 点とその符号反転の加算が無限遠点になっても例外を投げない`() {
        val g = EcPoint(hex(G))

        assertNull(crypto.decodePoint(crypto.add(g, crypto.negate(g)).encoded))
    }

    @Test
    fun `X4-1 無限遠点どうしの加算は例外を投げず、無限遠点のまま`() {
        assertNull(crypto.decodePoint(crypto.add(infinity(), infinity()).encoded))
    }

    @Test
    fun `X4-1 無限遠点の定数倍は例外を投げず、無限遠点のまま`() {
        assertNull(crypto.decodePoint(crypto.multiply(infinity(), scalarOf(5)).encoded))
    }

    @Test
    fun `X4-1 無限遠点の符号反転は例外を投げず、無限遠点のまま`() {
        assertNull(crypto.decodePoint(crypto.negate(infinity()).encoded))
    }

    @Test
    fun `X4-1 任意の点の位数倍は例外を投げず、無限遠点になる`() {
        assertNull(crypto.decodePoint(crypto.multiply(EcPoint(hex(G)), ORDER).encoded))
    }

    @Test
    fun `X4-1 点の復号は曲線上の非圧縮形式の点を返す`() {
        assertContentEquals(hex(G), assertNotNull(crypto.decodePoint(hex(G))).encoded)
    }

    @Test
    fun `X4-1 点の復号は曲線上にない点を null にする`() {
        val offCurve = hex(G).also { it[64] = (it[64] + 1).toByte() }

        assertNull(crypto.decodePoint(offCurve))
    }

    @Test
    fun `X4-1 点の復号は座標が素数以上の値を例外にせず null にする`() {
        val tooLarge = byteArrayOf(4) + ByteArray(64) { 0xff.toByte() }

        assertNull(crypto.decodePoint(tooLarge))
    }

    @Test
    fun `X4-1 点の復号は圧縮形式を null にする`() {
        assertNull(crypto.decodePoint(hex(G).copyOf(33).also { it[0] = 2 }))
    }

    @Test
    fun `X4-1 点の復号は先頭が 4 でない 65 バイトを null にする`() {
        assertNull(crypto.decodePoint(hex(G).also { it[0] = 3 }))
    }

    @Test
    fun `X4-1 点の復号は空の入力と無限遠点の符号化を null にする`() {
        assertNull(crypto.decodePoint(ByteArray(0)))
        assertNull(crypto.decodePoint(ByteArray(65)))
        assertNull(crypto.decodePoint(byteArrayOf(0)))
    }

    @Test
    fun `X4-1 定数時間の比較は内容が同じなら真、長さが違えば偽`() {
        assertTrue(crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(crypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `X4-1 乱数は指定した長さを返す`() {
        assertEquals(16, crypto.randomBytes(16).size)
    }
}
