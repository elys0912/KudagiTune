package playlistsyncfox.shared.path

import playlistsyncfox.shared.V23
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ElementTest {
    private fun sanitize(text: String) = sanitizeElement(text, "FB")

    private fun truncate(text: String, maxBytes: Int = V23) = truncateToBytes(text, maxBytes, "FB")

    @Test
    fun `TS-35 使えない文字 9 種を含む文字列は 9 種とも下線になり文字数は変わらない`() {
        val result = sanitize("a/b\\c:d*e?f\"g<h>i|j")

        assertEquals("a_b_c_d_e_f_g_h_i_j", result)
    }

    @Test
    fun `TS-36 制御文字を含む文字列は制御文字が全部削除される`() {
        assertEquals("abcd", sanitize("a\u0000b\u001Fc\u007Fd"))
    }

    @Test
    fun `TS-38 半角と全角の空白で挟んだ文字列は両端の空白が消える`() {
        assertEquals("abc", sanitize(" 　abc　 "))
    }

    @Test
    fun `TS-40 点だけと点 2 つだけの文字列は下線になる`() {
        assertEquals("_", sanitize("."))
        assertEquals("_", sanitize(".."))
    }

    @Test
    fun `TS-41 末尾に点と空白が混ざる文字列は点も空白も残らなくなるまで削られる`() {
        assertEquals("abc", sanitize("abc.."))
        assertEquals("abc", sanitize("abc. . "))
    }

    @Test
    fun `TS-42 空か空白だけか制御文字だけの文字列は代わりの文字列になる`() {
        assertEquals("FB", sanitize(""))
        assertEquals("FB", sanitize("  　"))
        assertEquals("FB", sanitize("\u0001\u0002"))
    }

    @Test
    fun `TS-43 日本語と絵文字はそのまま残る`() {
        assertEquals("日本語の曲😀", sanitize("日本語の曲😀"))
    }

    @Test
    fun `TS-44 結合文字を使った名前は正規化されず同じ列のまま残る`() {
        val decomposed = "が"

        assertEquals(decomposed, sanitize(decomposed))
    }

    @Test
    fun `TS-45 ちょうど上限のバイト数の文字列はそのまま返る`() {
        val exact = "a".repeat(V23)

        assertEquals(exact, truncate(exact))
    }

    @Test
    fun `TS-46 上限を 1 バイト超える ASCII の文字列は上限のバイト数に切れる`() {
        assertEquals("a".repeat(V23), truncate("a".repeat(V23 + 1)))
    }

    @Test
    fun `TS-47 境界が 3 バイト文字の途中に当たる文字列は文字の手前で切れる`() {
        val result = truncate("a" + "あ".repeat(80))

        assertEquals("a" + "あ".repeat(79), result)
        assertTrue(result.encodeToByteArray().size <= V23)
    }

    @Test
    fun `TS-48 境界がサロゲートペアの途中に当たる文字列はコードポイントの手前で切れる`() {
        assertEquals("a".repeat(V23 - 2), truncate("a".repeat(V23 - 2) + "😀"))
    }
}
