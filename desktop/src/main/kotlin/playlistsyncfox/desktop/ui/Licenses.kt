package playlistsyncfox.desktop.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ライセンスの表示（B4-7、N4-4）。資源の `artifacts.json`（Licensee の出力）を読んで並べるだけ。表示は名前と URL まで。

/** 部品の名前、版、許諾の名前と URL（本文は持たない）。 */
data class LicenseEntry(val name: String, val version: String, val licenses: List<String>)

/** 名前の昇順。読めなければ null（「読み込めない」を出す）。 */
fun parseLicenses(json: String): List<LicenseEntry>? = runCatching {
    Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.toEntry() }.sortedBy { it.name }
}.getOrNull()

/** 配布物の資源から読む。無ければ null。 */
fun loadBundledLicenses(): List<LicenseEntry>? =
    LicenseEntry::class.java.getResourceAsStream("/artifacts.json")?.use { parseLicenses(it.readBytes().decodeToString()) }

private fun JsonObject.toEntry(): LicenseEntry {
    fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    val licenses = (this["spdxLicenses"] as? JsonArray).orEmpty() + (this["unknownLicenses"] as? JsonArray).orEmpty()
    return LicenseEntry(
        name = field("name").ifEmpty { field("groupId") + ":" + field("artifactId") },
        version = field("version"),
        licenses = licenses.map { l ->
            val o = l.jsonObject
            listOf(o.field("name").ifEmpty { o.field("identifier") }, o.field("url")).filter(String::isNotEmpty).joinToString(" ")
        },
    )
}
