package playlistsyncfox.desktop.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readBytes

// ライセンスの表示（B4-7、N4-4）。資源の `artifacts.json`（Licensee の出力。名前、版、許諾の名前と URL だけで、本文は無い）を読んで並べ、
// 許諾ごとの全文は資源の `bundled-licenses/` から添える。全文の入手にネットワークは使わない。
// 依存部品の NOTICE は資源の `dependency-notices/`（ビルドが jar から集める）から、同梱の Java 実行環境の通知は実行時に `java.home` の `legal/` から読む。

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

// ビルドが依存の jar から集めた NOTICE（build.gradle.kts の `collectDependencyNotices`）。`index.txt` に部品の名前を 1 行ずつ、`<部品の名前>.txt` に本文を置く
private const val NOTICE_DIRECTORY = "/dependency-notices/"
private const val NOTICE_INDEX = "index.txt"
private const val NOTICE_TITLE = "NOTICE"

// 配布物が同梱する Java 実行環境（jpackage が jlink で作る）。Licensee の一覧には出ないので、通知を常に最後に足す。
// 通知は実行環境の `legal/<モジュール>/` を実行時に読む。配布物の実行環境と常に一致する
private const val RUNTIME_TITLE = "Java 実行環境（OpenJDK）"
private const val RUNTIME_COMPONENT = "同梱の Java 実行環境"
private const val RUNTIME_LEGAL_DIRECTORY = "legal"
private const val RUNTIME_LEGAL_UNAVAILABLE = "Java 実行環境の legal/ を読み込めません"

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

/**
 * 許諾ごとに全文 1 件。その許諾を持つ部品の名前を添える。続けて依存部品の NOTICE、最後に同梱の Java 実行環境の通知を置く。
 * [javaHome] は Java 実行環境の場所。通知はその下の `legal/` から読む（試験で差し替える）。
 */
fun bundledLicenseTexts(entries: List<LicenseEntry>, javaHome: Path = Path.of(System.getProperty("java.home"))): List<LicenseText> {
    val fromEntries = BundledLicense.entries.mapNotNull { license ->
        val names = entries.filter { license in it.bundled }.map { it.name }
        if (names.isEmpty()) null else LicenseText(license.title, names, loadText(license.resource))
    }
    return fromEntries + dependencyNotices() + runtimeLegalTexts(javaHome)
}

/** ビルドが集めた依存部品の NOTICE。同じ本文は部品の名前を並べて 1 件にまとめる。集まっていなければ空。[readResource] は資源の読み込み（試験で差し替える）。 */
fun dependencyNotices(readResource: (String) -> String? = ::readResourceText): List<LicenseText> {
    val componentNames = readResource(NOTICE_DIRECTORY + NOTICE_INDEX).orEmpty().lines().filter { it.isNotBlank() }
    val notices = componentNames.mapNotNull { name ->
        readResource("$NOTICE_DIRECTORY$name.txt")?.let { LicenseText(NOTICE_TITLE, listOf(name), it) }
    }
    return mergeSameText(notices)
}

/**
 * `<javaHome>/legal/<モジュール>/` の全ファイル（md を含む）。ファイルごとに 1 件で、同じ本文は持つモジュールの名前を並べて 1 件にまとめる。
 * `legal/` が無い・読めないときは、その旨の 1 件を返す。
 */
fun runtimeLegalTexts(javaHome: Path): List<LicenseText> {
    val texts = runCatching { readRuntimeLegalFiles(javaHome.resolve(RUNTIME_LEGAL_DIRECTORY)) }.getOrNull().orEmpty()
    if (texts.isEmpty()) return listOf(LicenseText(RUNTIME_TITLE, listOf(RUNTIME_COMPONENT), RUNTIME_LEGAL_UNAVAILABLE))

    return mergeSameText(texts)
}

private fun readRuntimeLegalFiles(legalDirectory: Path): List<LicenseText> =
    legalDirectory.listDirectoryEntries().filter { it.isDirectory() }.sortedBy { it.name }.flatMap { module ->
        module.listDirectoryEntries().filter { it.isRegularFile() }.sortedBy { it.name }.map { file ->
            LicenseText("$RUNTIME_TITLE：${file.name}", listOf(module.name), file.readBytes().decodeToString())
        }
    }

/** 題と本文が同じ件を 1 件にまとめ、部品の名前を並べる。最初に出た順を保つ。 */
private fun mergeSameText(texts: List<LicenseText>): List<LicenseText> =
    texts.groupBy { it.title to it.text }.map { (key, group) -> LicenseText(key.first, group.flatMap { it.componentNames }, key.second) }

private fun readResourceText(path: String): String? =
    LicenseEntry::class.java.getResourceAsStream(path)?.use { it.readBytes().decodeToString() }

private fun loadText(resource: String): String = readResourceText(TEXT_DIRECTORY + resource) ?: TEXT_UNAVAILABLE

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
