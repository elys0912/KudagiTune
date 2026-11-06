package playlistsyncfox.shared.path

private const val UNUSABLE_CHARS = "/\\:*?\"<>|"

// U+0085、U+2028、U+2029 は m3u8 の行の区切りになりうるため、制御文字と同じく削除する
private val lineSeparators = charArrayOf('\u0085', ' ', ' ')

/** ファイル名とフォルダ名の 1 要素を、保存先に使える形にする。正規化はしない。 */
fun sanitizeElement(text: String, fallback: String): String {
    val withoutControls = text
        .map { if (it in UNUSABLE_CHARS) '_' else it }
        .filterNot { it.code <= 0x1F || it.code == 0x7F || it in lineSeparators }
        .joinToString("")
    val trimmed = trimEnds(withoutControls)

    // 末尾の `.` は trimEnds で消えるため、`.` と `..` は trim の前の値で判定する
    val element = if (withoutControls == "." || withoutControls == "..") "_" else trimmed
    if (element.isEmpty()) return fallback

    // m3u8 のコメント行になるのを避ける
    return if (element.startsWith('#')) "_" + element.drop(1) else element
}

/**
 * UTF-8 で [maxBytes] 以内に収まる最長の前方の部分を返す。切るのはコードポイントの境界。
 * 切った後に末尾の空白と `.` を削り、空になれば [fallback] を返す。
 */
fun truncateToBytes(text: String, maxBytes: Int, fallback: String): String {
    if (utf8Length(text) <= maxBytes) return text

    var usedBytes = 0
    var end = 0
    while (end < text.length) {
        val codePointLength = if (text[end].isHighSurrogate() && end + 1 < text.length) 2 else 1
        val codePointBytes = utf8Length(text.substring(end, end + codePointLength))
        if (usedBytes + codePointBytes > maxBytes) break

        usedBytes += codePointBytes
        end += codePointLength
    }

    return trimEnds(text.substring(0, end)).ifEmpty { fallback }
}

internal fun utf8Length(text: String): Int = text.encodeToByteArray().size

private fun trimEnds(text: String): String = text.trimStart().trimEnd { it.isWhitespace() || it == '.' }
