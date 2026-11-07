package playlistsyncfox.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LicensesTest {
    @Test
    fun 名前の昇順で許諾の名前とURLを並べる() {
        val json = """[
            {"groupId":"b","artifactId":"b","version":"1","name":"B","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]},
            {"groupId":"a","artifactId":"a","version":"2","unknownLicenses":[{"name":"X Licence","url":"https://x"}]}
        ]"""
        val entries = parseLicenses(json)!!
        assertEquals(listOf("B", "a:a"), entries.map { it.name })
        assertEquals(listOf("X Licence https://x"), entries[1].licenses)
        assertEquals(listOf("Apache License 2.0 https://a"), entries[0].licenses)
    }

    @Test
    fun 読めない内容は読み込めないとして扱う() {
        assertNull(parseLicenses("not json"))
    }

    @Test
    fun 配布物の資源にライセンスの一覧が入っている() {
        val entries = loadBundledLicenses()
        assertTrue(!entries.isNullOrEmpty())
    }
}
