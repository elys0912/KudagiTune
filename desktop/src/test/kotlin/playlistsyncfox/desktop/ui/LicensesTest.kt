package playlistsyncfox.desktop.ui

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// 全文を同梱する 3 種の許諾（Apache-2.0、MIT、Bouncy Castle）と、種類の別の許諾（X）を 1 つずつ持つ部品
private val SAMPLE_JSON = """[
    {"groupId":"a","artifactId":"a","version":"1","name":"A","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]},
    {"groupId":"m","artifactId":"m","version":"1","name":"M","unknownLicenses":[{"name":"MIT","url":"https://opensource.org/license/mit"}]},
    {"groupId":"b","artifactId":"b","version":"1","name":"B","unknownLicenses":[{"name":"Bouncy Castle Licence","url":"https://www.bouncycastle.org/licence.html"}]},
    {"groupId":"x","artifactId":"x","version":"1","name":"X","unknownLicenses":[{"name":"X Licence","url":"https://x"}]}
]"""

private const val RUNTIME_TITLE = "Java 実行環境（OpenJDK）"

/** java.home の代わりに渡す一時ディレクトリ。[files]（`モジュール/ファイル` → 本文）を `legal/` の下に作る。 */
private fun fakeJavaHome(files: Map<String, String>): Path {
    val javaHome = createTempDirectory("psfox-javahome")
    files.forEach { (relativePath, text) ->
        val file = javaHome.resolve("legal").resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(text)
    }
    return javaHome
}

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
        val javaHome = fakeJavaHome(mapOf("java.base/LICENSE" to "GPL"))

        val texts = bundledLicenseTexts(parseLicenses(SAMPLE_JSON)!!, javaHome)

        assertEquals(listOf("Apache License 2.0", "MIT License", "Bouncy Castle Licence"), texts.take(3).map { it.title })
        assertEquals(listOf(listOf("A"), listOf("M"), listOf("B")), texts.take(3).map { it.componentNames })
        assertEquals(LicenseText("$RUNTIME_TITLE：LICENSE", listOf("java.base"), "GPL"), texts.last())
    }

    @Test
    fun 同じ許諾の部品は1件の全文にまとめて名前の昇順で並べる() {
        val json = """[
            {"groupId":"z","artifactId":"z","version":"1","name":"Z","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]},
            {"groupId":"a","artifactId":"a","version":"1","name":"A","spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://a"}]}
        ]"""

        val texts = bundledLicenseTexts(parseLicenses(json)!!)

        assertEquals(listOf("A", "Z"), texts.first().componentNames)
        assertEquals(1, texts.count { it.title == "Apache License 2.0" })
    }

    @Test
    fun 配布物の資源に全文が入っている() {
        val texts = bundledLicenseTexts(parseLicenses(SAMPLE_JSON)!!).associate { it.title to it.text }

        assertContains(texts.getValue("Apache License 2.0"), "Version 2.0, January 2004")
        assertContains(texts.getValue("MIT License"), "Permission is hereby granted")
        assertContains(texts.getValue("Bouncy Castle Licence"), "Bouncy Castle")
    }

    @Test
    fun 許諾の一覧が空でも同梱のJava実行環境の通知は出る() {
        val javaHome = fakeJavaHome(mapOf("java.base/ASSEMBLY_EXCEPTION" to "ASSEMBLY EXCEPTION"))

        val texts = bundledLicenseTexts(emptyList(), javaHome)

        assertContains(texts.last().text, "ASSEMBLY EXCEPTION")
    }

    @Test
    fun 配布物の資源に依存部品のNOTICEの一覧が入っている() {
        assertNotNull(LicenseEntry::class.java.getResourceAsStream("/dependency-notices/index.txt"))
    }

    @Test
    fun 依存部品のNOTICEは一覧の順に読み同じ本文は1件にまとめて部品の名前を並べる() {
        val resources = mapOf(
            "/dependency-notices/index.txt" to "a-1.0\nb-2.0\nc-3.0\n",
            "/dependency-notices/a-1.0.txt" to "NOTICE X",
            "/dependency-notices/b-2.0.txt" to "NOTICE Y",
            "/dependency-notices/c-3.0.txt" to "NOTICE X",
        )

        val notices = dependencyNotices { resources[it] }

        assertEquals(listOf("NOTICE X", "NOTICE Y"), notices.map { it.text })
        assertEquals(listOf(listOf("a-1.0", "c-3.0"), listOf("b-2.0")), notices.map { it.componentNames })
    }

    @Test
    fun 依存部品のNOTICEが1件も集まっていなければ空を返す() {
        assertTrue(dependencyNotices { if (it.endsWith("index.txt")) "" else null }.isEmpty())
        assertTrue(dependencyNotices { null }.isEmpty())
    }

    @Test
    fun Java実行環境の通知はlegalの下のファイルをmdも含めてモジュールごとに読む() {
        val javaHome = fakeJavaHome(
            mapOf("java.base/LICENSE" to "GPL", "java.base/aes.md" to "AES notice", "java.desktop/freetype.md" to "FreeType notice"),
        )

        val texts = runtimeLegalTexts(javaHome)

        assertEquals(listOf("LICENSE", "aes.md", "freetype.md").map { "$RUNTIME_TITLE：$it" }, texts.map { it.title })
        assertEquals(listOf(listOf("java.base"), listOf("java.base"), listOf("java.desktop")), texts.map { it.componentNames })
        assertEquals(listOf("GPL", "AES notice", "FreeType notice"), texts.map { it.text })
    }

    @Test
    fun 同じ本文のファイルは1件にまとめて持つモジュールの名前を並べる() {
        val javaHome = fakeJavaHome(mapOf("java.base/LICENSE" to "GPL", "java.desktop/LICENSE" to "GPL", "java.sql/LICENSE" to "GPL"))

        val texts = runtimeLegalTexts(javaHome)

        assertEquals(listOf(listOf("java.base", "java.desktop", "java.sql")), texts.map { it.componentNames })
    }

    @Test
    fun ファイル名が同じでも本文が違えば別の件で出す() {
        val javaHome = fakeJavaHome(mapOf("java.base/icu.md" to "ICU 1", "java.desktop/icu.md" to "ICU 2"))

        val texts = runtimeLegalTexts(javaHome)

        assertEquals(listOf("ICU 1", "ICU 2"), texts.map { it.text })
        assertEquals(listOf(listOf("java.base"), listOf("java.desktop")), texts.map { it.componentNames })
    }

    @Test
    fun legalが無いときは読み込めない旨を1件だけ出して落ちない() {
        val javaHomeWithoutLegal = createTempDirectory("psfox-javahome")

        val texts = runtimeLegalTexts(javaHomeWithoutLegal)

        assertEquals(listOf(RUNTIME_TITLE), texts.map { it.title })
        assertContains(texts.single().text, "読み込めません")
    }

    @Test
    fun legalが空のときも読み込めない旨を1件だけ出す() {
        val javaHome = createTempDirectory("psfox-javahome")
        javaHome.resolve("legal").createDirectories()

        val texts = runtimeLegalTexts(javaHome)

        assertContains(texts.single().text, "読み込めません")
    }
}
