package playlistsyncfox.android.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ライセンスの表示（X7-6）。資産の `artifacts.json`（Licensee の出力）を読んで並べる。全文は資産の `licenses/` に、依存の NOTICE は `notices/` に同梱し、外部の通信はしない。

/** 全文を同梱した資産の置き場（`assets/licenses/`）。 */
const val LICENSE_TEXT_DIR = "licenses"

/** 依存の NOTICE を集めた資産の置き場（`assets/notices/`）。ビルド時に build.gradle.kts の `CollectNotices` が、NOTICE を持つ jar ごとに `<jar の名前>.txt` で置く。 */
const val NOTICE_DIR = "notices"

const val LICENSE_TEXT_UNREADABLE = "ライセンスの全文を読み込めません"

const val NOTICE_HEADING = "NOTICE"

/**
 * 同梱する全文のファイル。キーは Licensee の `identifier`（SPDX があるとき）か `url`（無いとき）で、build.gradle.kts の許す許諾と対応させる。
 * 許諾を足したら、全文もここと資産に足す。MIT の全文は著作権者の行を持つため、MIT で入っている slf4j-api のもの。別の部品が MIT で入ったら、部品ごとの全文を足す。
 */
internal val BUNDLED_LICENSE_FILES = mapOf(
    "Apache-2.0" to "apache-2.0.txt",
    "https://opensource.org/license/mit" to "mit-slf4j.txt",
    "https://www.bouncycastle.org/licence.html" to "bouncycastle.txt",
)

/**
 * 部品の名前、版、ライセンスの名前。[body] は許諾の名前と URL。[textFiles] は同梱の全文のファイル名（[LICENSE_TEXT_DIR] の下）で、
 * 全文を持たない許諾は含めない。[noticeFile] は NOTICE のファイル名（[NOTICE_DIR] の下）で、`<artifactId>-<version>.txt`（jar の名前と同じ）。
 * NOTICE を持たない部品のファイルは資産に無い。全文と NOTICE は選ばれたときに読む。
 */
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val body: String,
    val textFiles: List<String>,
    val noticeFile: String,
)

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
    val textFiles = licenses.mapNotNull { BUNDLED_LICENSE_FILES[it.field("identifier").ifEmpty { it.field("url") }] }.distinct()
    return LicenseEntry(
        name = text("name").ifEmpty { text("groupId") + ":" + text("artifactId") },
        version = text("version"),
        license = names.joinToString(" / "),
        body = body,
        textFiles = textFiles,
        noticeFile = "${text("artifactId")}-${text("version")}.txt",
    )
}

/**
 * 部品を選んだときに出す全文を、表示の順に返す。許諾の全文（読めなければ [LICENSE_TEXT_UNREADABLE]）の後に、NOTICE があれば見出しを付けて足す。
 * NOTICE は無いのが普通なので、読めなくても何も足さない。
 */
fun licenseTexts(entry: LicenseEntry, readLicense: (String) -> String?, readNotice: (String) -> String?): List<String> {
    val licenses = entry.textFiles.map { readLicense(it) ?: LICENSE_TEXT_UNREADABLE }
    val notice = readNotice(entry.noticeFile)?.let { "$NOTICE_HEADING\n$it" }

    return licenses + listOfNotNull(notice)
}
