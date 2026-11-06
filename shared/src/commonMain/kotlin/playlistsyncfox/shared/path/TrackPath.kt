package playlistsyncfox.shared.path

import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.manifest.ManifestTrack

/**
 * 衝突の前のパスの候補。[title] は長さを切る前の曲名。接尾辞を付けるときに、切る長さを変えて作り直すために持つ。
 * [extension] は先頭の `.` を含み、拡張子が無いときは空。
 */
data class PathParts(
    val artistFolder: String,
    val albumFolder: String,
    val title: String,
    val extension: String,
    val maxBytes: Int,
)

fun buildTrackCandidate(track: ManifestTrack, maxBytes: Int): PathParts {
    val artist = track.albumArtist.takeUnless { it.isNullOrEmpty() }
        ?: track.artist.takeUnless { it.isNullOrEmpty() }
        ?: "Unknown Artist"

    return PathParts(
        artistFolder = folderName(artist, "Unknown Artist", maxBytes),
        albumFolder = folderName(track.album.orEmpty(), "Unknown Album", maxBytes),
        title = sanitizeElement(track.name.orEmpty(), "Untitled"),
        extension = extensionPart(track.extension, maxBytes),
        maxBytes = maxBytes,
    )
}

/** `<アーティスト>/<アルバム>/<ステム><接尾辞>.<拡張子>`。接尾辞は空を許す。 */
fun assemblePath(parts: PathParts, suffix: String): String {
    val stemBytes = maxOf(0, parts.maxBytes - utf8Length(parts.extension) - utf8Length(suffix))
    val stem = truncateToBytes(parts.title, stemBytes, "Untitled")

    return "${parts.artistFolder}/${parts.albumFolder}/$stem$suffix${parts.extension}"
}

/**
 * 置く曲ごとの相対パスを返す。同じ入力なら同じ結果になる。
 *
 * @param records 置き換えの曲の `placedPath` を取り出すための記録。対象に無い曲の記録は読まない
 * @param usedPaths 対象に含まれない曲の `placedPath` と、MediaStore に既にあるパス
 * @param suffixLength 接尾辞に使う `persistent_id` の文字数（V24）
 * @param maxBytes 1 要素の上限バイト数（V23）
 */
fun resolveTrackPaths(
    tracks: List<ManifestTrack>,
    records: List<SyncRecord>,
    usedPaths: Set<String>,
    suffixLength: Int,
    maxBytes: Int,
): Map<String, String> {
    // FAT 系に備え、比較は大文字小文字を区別しない。返す綴りは元のまま
    val used = usedPaths.mapTo(HashSet()) { it.lowercase() }
    val resolved = mutableMapOf<String, String>()

    val recordsById = records.associateBy { it.persistentId }
    val (replacing, adding) = tracks.partition { it.persistentId in recordsById }
    for (track in replacing) {
        val placedPath = recordsById.getValue(track.persistentId).placedPath
        resolved[track.persistentId] = placedPath
        used += placedPath.lowercase()
    }

    for (track in adding.sortedBy { it.persistentId }) {
        val parts = buildTrackCandidate(track, maxBytes)
        val id = track.persistentId

        // 接尾辞なし、V24 文字、全文の順に試す。全文でも使用中なら、そのパスを返して MediaStore の別名付けに任せる
        val path = listOf("", suffixOf(id.take(suffixLength)))
            .map { assemblePath(parts, it) }
            .firstOrNull { it.lowercase() !in used }
            ?: assemblePath(parts, suffixOf(id))

        resolved[id] = path
        used += path.lowercase()
    }

    return resolved
}

/** 拡張子と対応する MIME。表に無い拡張子は null。 */
fun mimeTypeOf(extension: String): String? = when (extension) {
    "mp3" -> "audio/mpeg"
    "m4a" -> "audio/mp4"
    "aac" -> "audio/aac"
    "wav" -> "audio/x-wav"
    "aif", "aiff" -> "audio/x-aiff"
    "flac" -> "audio/flac"
    "m3u8" -> "audio/x-mpegurl"
    else -> null
}

internal fun suffixOf(idPrefix: String): String = " [$idPrefix]"

private fun folderName(text: String, fallback: String, maxBytes: Int): String =
    truncateToBytes(sanitizeElement(text, fallback), maxBytes, fallback)

private fun extensionPart(extension: String, maxBytes: Int): String {
    val sanitized = sanitizeElement(extension, "")
    if (sanitized.isEmpty()) return ""

    // 異常に長い拡張子でも、曲名のステムの上限が負にならないようにする
    return "." + truncateToBytes(sanitized, maxBytes - 1, "")
}
