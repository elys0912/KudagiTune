package playlistsyncfox.desktop.ui

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 全文を同梱する 3 種の許諾（Apache-2.0、MIT、Bouncy Castle）と、種類の別の許諾（X）を 1 つずつ持つ部品
private val SAMPLE_JSON = """[
    {"groupId":"a","artifactId":"a","version":"1","name":"A","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]},
    {"groupId":"m","artifactId":"m","version":"1","name":"M","unknownLicenses":[{"name":"MIT","url":"https://opensource.org/license/mit"}]},
    {"groupId":"b","artifactId":"b","version":"1","name":"B","unknownLicenses":[{"name":"Bouncy Castle Licence","url":"https://www.bouncycastle.org/licence.html"}]},
    {"groupId":"x","artifactId":"x","version":"1","name":"X","unknownLicenses":[{"name":"X Licence","url":"https://x"}]}
]"""

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

    @Test
    fun 全文を同梱している許諾は識別子かURLで見分けて持たない許諾は見分けない() {
        val bundled = parseLicenses(SAMPLE_JSON)!!.associate { it.name to it.bundled }

        assertEquals(setOf(BundledLicense.Apache2), bundled["A"])
        assertEquals(setOf(BundledLicense.Mit), bundled["M"])
        assertEquals(setOf(BundledLicense.BouncyCastle), bundled["B"])
        assertEquals(emptySet<BundledLicense>(), bundled["X"])
    }

    @Test
    fun 全文は許諾ごとに1件で持つ部品の名前を添えて最後にJava実行環境を置く() {
        val texts = bundledLicenseTexts(parseLicenses(SAMPLE_JSON)!!)

        assertEquals(
            listOf("Apache License 2.0", "MIT License", "Bouncy Castle Licence", "Java 実行環境（Eclipse Temurin、OpenJDK）"),
            texts.map { it.title },
        )
        assertEquals(listOf(listOf("A"), listOf("M"), listOf("B"), listOf("同梱の Java 実行環境")), texts.map { it.componentNames })
    }

    @Test
    fun 同じ許諾の部品は1件の全文にまとめて名前の昇順で並べる() {
        val json = """[
            {"groupId":"z","artifactId":"z","version":"1","name":"Z","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]},
            {"groupId":"a","artifactId":"a","version":"1","name":"A","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]}
        ]"""

        val texts = bundledLicenseTexts(parseLicenses(json)!!)

        assertEquals(listOf("A", "Z"), texts.first().componentNames)
        assertEquals(2, texts.size)
    }

    @Test
    fun 配布物の資源に全文が入っている() {
        val texts = bundledLicenseTexts(parseLicenses(SAMPLE_JSON)!!).associate { it.title to it.text }

        assertContains(texts.getValue("Apache License 2.0"), "Version 2.0, January 2004")
        assertContains(texts.getValue("MIT License"), "Permission is hereby granted")
        assertContains(texts.getValue("Bouncy Castle Licence"), "Bouncy Castle")
        assertContains(texts.getValue("Java 実行環境（Eclipse Temurin、OpenJDK）"), "GNU General Public License")
    }

    @Test
    fun 許諾の一覧が空でも同梱のJava実行環境の全文は出る() {
        val texts = bundledLicenseTexts(emptyList())

        assertEquals(1, texts.size)
        assertContains(texts.single().text, "ASSEMBLY EXCEPTION")
    }
}
