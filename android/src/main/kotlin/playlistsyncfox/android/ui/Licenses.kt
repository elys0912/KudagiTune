package playlistsyncfox.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ライセンスの表示（X7-6）。資産の `artifacts.json`（Licensee の出力）を読んで並べるだけ。外部の通信はしない。

/** 部品の名前、版、ライセンスの名前。本文は選ばれたときに [LicenseEntry.body] を使う。 */
data class LicenseEntry(val name: String, val version: String, val license: String, val body: String)

/** 名前の昇順。読めなければ null（「読み込めない」を出す）。 */
fun parseLicenses(json: String): List<LicenseEntry>? = runCatching {
    Json.parseToJsonElement(json).jsonArray.map { it.jsonObject.toEntry() }.sortedBy { it.name }
}.getOrNull()

private fun JsonObject.toEntry(): LicenseEntry {
    fun text(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    val licenses = (this["spdxLicenses"] as? JsonArray).orEmpty().map { it.jsonObject } +
        (this["unknownLicenses"] as? JsonArray).orEmpty().map { it.jsonObject }
    fun JsonObject.field(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
    val names = licenses.map { it.field("name").ifEmpty { it.field("identifier") } }
    val body = licenses.joinToString("\n") { listOf(it.field("name"), it.field("url")).filter(String::isNotEmpty).joinToString("\n") }
    return LicenseEntry(
        name = text("name").ifEmpty { text("groupId") + ":" + text("artifactId") },
        version = text("version"),
        license = names.joinToString(" / "),
        body = body,
    )
}
