package playlistsyncfox.itunes.location

import kotlin.test.Test
import kotlin.test.assertEquals

class DecodeFileUrlTest {
    private fun found(path: String) = TrackLocation.Found(path)

    private fun undecodable(failure: LocationFailure) = TrackLocation.Undecodable(failure)

    @Test
    fun `境界の例の表の全件を復号すると、表の結果と一致する`() {
        val cases = listOf(
            "file://localhost/C:/Music/A%20B/%E6%9B%B2%231.mp3" to found("C:\\Music\\A B\\曲#1.mp3"),
            "file:///C:/Music/a%25b.mp3" to found("C:\\Music\\a%b.mp3"),
            "file://localhost/C:/Music/a+b.mp3" to found("C:\\Music\\a+b.mp3"),
            "file://localhost/E:/Music/%5Ba%5D%60%7D%5E/&'()!@~=$,.mp3" to found("E:\\Music\\[a]`}^\\&'()!@~=$,.mp3"),
            "file://nas.example.invalid/share/a.mp3" to found("\\\\nas.example.invalid\\share\\a.mp3"),
            "file://localhost/Users/x/a.mp3" to undecodable(LocationFailure.NoDriveLetter),
            "file://localhost/C:/Music/%E3%81.mp3" to undecodable(LocationFailure.InvalidEncoding),
            "file://localhost/C:/Music/a%zz.mp3" to undecodable(LocationFailure.InvalidEncoding),
            "http://example.invalid/a.mp3" to undecodable(LocationFailure.InvalidFormat),
            "" to undecodable(LocationFailure.InvalidFormat),
        )

        for ((input, expected) in cases) {
            assertEquals(expected, decodeFileUrl(input), "入力: $input")
        }
    }

    @Test
    fun `途中で切れた符号を含むパスを復号すると、不正な符号になる`() {
        assertEquals(undecodable(LocationFailure.InvalidEncoding), decodeFileUrl("file://localhost/C:/Music/%E6%9B"))
    }

    @Test
    fun `末尾が百分率の符号の途中で終わるパスを復号すると、不正な符号になる`() {
        assertEquals(undecodable(LocationFailure.InvalidEncoding), decodeFileUrl("file://localhost/C:/Music/a%2"))
    }

    @Test
    fun `TS-168 共有名が空のネットワークのパスを復号すると、解決できない接続先になる`() {
        assertEquals(undecodable(LocationFailure.UnresolvableHost), decodeFileUrl("file://nas.example.invalid//a.mp3"))
        assertEquals(undecodable(LocationFailure.UnresolvableHost), decodeFileUrl("file://nas.example.invalid/"))
    }

    @Test
    fun `NUL を含むパスを復号すると、制御文字になる`() {
        assertEquals(undecodable(LocationFailure.ControlCharacter), decodeFileUrl("file://localhost/C:/Music/a%00b.mp3"))
    }
}
