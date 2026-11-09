package playlistsyncfox.android.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val ARTIFACTS = """
    [
      {"groupId":"androidx.core","artifactId":"core","version":"1.0",
       "spdxLicenses":[{"identifier":"Apache-2.0","name":"Apache License 2.0","url":"https://www.apache.org/licenses/LICENSE-2.0"}]},
      {"groupId":"org.slf4j","artifactId":"slf4j-api","version":"2.0.17",
       "unknownLicenses":[{"name":"MIT License","url":"https://opensource.org/license/mit"}]},
      {"groupId":"org.bouncycastle","artifactId":"bcprov","version":"1.80",
       "unknownLicenses":[{"name":"Bouncy Castle Licence","url":"https://www.bouncycastle.org/licence.html"}]},
      {"groupId":"example","artifactId":"other","version":"0.1",
       "unknownLicenses":[{"name":"Other","url":"https://example.invalid/license"}]}
    ]
""".trimIndent()

class LicensesTest {
    private val entries = parseLicenses(ARTIFACTS)!!.associateBy { it.name }

    @Test
    fun `TA-254 SPDX の識別子と URL のどちらの許諾でも、同梱の全文のファイルを選ぶ`() {
        assertEquals(listOf("apache-2.0.txt"), entries.getValue("androidx.core:core").textFiles)
        assertEquals(listOf("mit-slf4j.txt"), entries.getValue("org.slf4j:slf4j-api").textFiles)
        assertEquals(listOf("bouncycastle.txt"), entries.getValue("org.bouncycastle:bcprov").textFiles)
    }

    @Test
    fun `TA-254 全文を同梱していない許諾は、名前と URL だけを出し、全文のファイルは持たない`() {
        val other = entries.getValue("example:other")

        assertTrue(other.textFiles.isEmpty())
        assertEquals("Other\nhttps://example.invalid/license", other.body)
    }

    @Test
    fun `TA-254 NOTICE のファイル名は artifactId と版から決まり、jar の名前と同じになる`() {
        assertEquals("core-1.0.txt", entries.getValue("androidx.core:core").noticeFile)
        assertEquals("slf4j-api-2.0.17.txt", entries.getValue("org.slf4j:slf4j-api").noticeFile)
    }

    @Test
    fun `TA-254 部品を選ぶと、許諾の全文の後ろに見出し付きの NOTICE が続く`() {
        val core = entries.getValue("androidx.core:core")

        val texts = licenseTexts(core, readLicense = { "全文 $it" }, readNotice = { "通知 $it" })

        assertEquals(listOf("全文 apache-2.0.txt", "NOTICE\n通知 core-1.0.txt"), texts)
    }

    @Test
    fun `TA-254 NOTICE が無い部品は、全文だけを出して NOTICE の見出しを付けない`() {
        val core = entries.getValue("androidx.core:core")

        val texts = licenseTexts(core, readLicense = { "全文" }, readNotice = { null })

        assertEquals(listOf("全文"), texts)
    }

    @Test
    fun `TA-254 全文が読めなくても代わりの文を出し、NOTICE は続けて出す`() {
        val core = entries.getValue("androidx.core:core")

        val texts = licenseTexts(core, readLicense = { null }, readNotice = { "通知" })

        assertEquals(listOf(LICENSE_TEXT_UNREADABLE, "NOTICE\n通知"), texts)
    }

    @Test
    fun `TA-254 読めない JSON は null を返す`() {
        assertNull(parseLicenses("{"))
    }

    @Test
    fun `TA-254 表に載せた全文のファイルは資産に実在し、空でない`() {
        BUNDLED_LICENSE_FILES.values.forEach { name ->
            val file = File("src/main/assets/$LICENSE_TEXT_DIR/$name")

            assertTrue(file.isFile && file.length() > 0, name)
        }
    }
}
