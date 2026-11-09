package playlistsyncfox.desktop.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ライセンスの表示（B4-7、N4-4）。資源の `artifacts.json`（Licensee の出力。名前、版、許諾の名前と URL だけで、本文は無い）を読んで並べ、
// 許諾ごとの全文は資源の `bundled-licenses/` から添える。全文の入手にネットワークは使わない。

/** 全文を同梱している許諾。Licensee が許す許諾（build.gradle.kts の `licensee`）に合わせる。 */
enum class BundledLicense(val title: String, val resource: String) {
    Apache2("Apache License 2.0", "Apache-2.0.txt"),

    /** 本文は slf4j-api の LICENSE.txt（著作権表示つき）。MIT の部品が増えたら本文を足す。 */
    Mit("MIT License", "MIT.txt"),
    BouncyCastle("Bouncy Castle Licence", "BouncyCastle.txt"),
}

/** 部品の名前、版、許諾の名前と URL。[bundled] は、その部品が持つ許諾のうち全文を同梱しているもの。 */
data class LicenseEntry(val name: String, val version: String, val licenses: List<String>, val bundled: Set<BundledLicense>)

/** 全文 1 件と、その許諾を持つ部品の名前。 */
data class LicenseText(val title: String, val componentNames: List<String>, val text: String)

private const val TEXT_DIRECTORY = "/bundled-licenses/"
private const val TEXT_UNAVAILABLE = "全文を読み込めません"

// 配布物が同梱する Java 実行環境（jpackage が入れる）。Licensee の一覧には出ないので、全文を常に最後に足す
private const val RUNTIME_TITLE = "Java 実行環境（Eclipse Temurin、OpenJDK）"
private const val RUNTIME_RESOURCE = "OpenJDK.txt"
private const val RUNTIME_COMPONENT = "同梱の Java 実行環境"

// build.gradle.kts の `allowUrl` と同じ文字列（Licensee が SPDX の識別子に直せなかった許諾）
private const val MIT_URL = "https://opensource.org/license/mit"
private const val BOUNCY_CASTLE_URL = "https://www.bouncycastle.org/licence.html"

/** 名前の昇順。読めなければ null（「読み込めない」を出す）。 */
fun parseLicenses(json: String): List<LicenseEntry>? = runCatching {
    Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.toEntry() }.sortedBy { it.name }
}.getOrNull()

/** 配布物の資源から読む。無ければ null。 */
fun loadBundledLicenses(): List<LicenseEntry>? =
    LicenseEntry::class.java.getResourceAsStream("/artifacts.json")?.use { parseLicenses(it.readBytes().decodeToString()) }

/** 許諾ごとに全文 1 件。その許諾を持つ部品の名前を添える。最後に同梱の Java 実行環境の全文を置く。 */
fun bundledLicenseTexts(entries: List<LicenseEntry>): List<LicenseText> {
    val fromEntries = BundledLicense.entries.mapNotNull { license ->
        val names = entries.filter { license in it.bundled }.map { it.name }
        if (names.isEmpty()) null else LicenseText(license.title, names, loadText(license.resource))
    }
    return fromEntries + LicenseText(RUNTIME_TITLE, listOf(RUNTIME_COMPONENT), loadText(RUNTIME_RESOURCE))
}

private fun loadText(resource: String): String =
    LicenseEntry::class.java.getResourceAsStream(TEXT_DIRECTORY + resource)?.use { it.readBytes().decodeToString() } ?: TEXT_UNAVAILABLE

private fun bundledLicenseOf(identifier: String, url: String): BundledLicense? = when {
    identifier == "Apache-2.0" -> BundledLicense.Apache2
    url == MIT_URL -> BundledLicense.Mit
    url == BOUNCY_CASTLE_URL -> BundledLicense.BouncyCastle
    else -> null
}

private fun JsonObject.toEntry(): LicenseEntry {
    fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    val licenses = ((this["spdxLicenses"] as? JsonArray).orEmpty() + (this["unknownLicenses"] as? JsonArray).orEmpty()).map { it.jsonObject }
    return LicenseEntry(
        name = field("name").ifEmpty { field("groupId") + ":" + field("artifactId") },
        version = field("version"),
        licenses = licenses.map { l ->
            listOf(l.field("name").ifEmpty { l.field("identifier") }, l.field("url")).filter(String::isNotEmpty).joinToString(" ")
        },
        bundled = licenses.mapNotNull { bundledLicenseOf(it.field("identifier"), it.field("url")) }.toSet(),
    )
}
