package playlistsyncfox.shared.m3u8

import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.path.sanitizeElement
import playlistsyncfox.shared.path.suffixOf
import playlistsyncfox.shared.path.truncateToBytes

private const val M3U8_EXTENSION = ".m3u8"

/**
 * m3u8 の本文を返す。行末は LF。文字コードの変換は呼び出し側が行う。
 * [placedPaths] に無い曲は、置けていないため載せない。
 */
fun buildM3u8(playlist: ManifestPlaylist, placedPaths: Map<String, String>): String =
    buildString {
        append("#EXTM3U\n")
        for (id in playlist.trackIds) {
            val path = placedPaths[id] ?: continue
            append(path).append('\n')
        }
    }

/**
 * プレイリストごとの m3u8 のファイル名を返す。
 * 名前が（大文字小文字を区別せず）重なる、または名前が空のときは `persistent_id` の接尾辞を付ける。
 *
 * @param suffixLength 接尾辞に使う `persistent_id` の文字数（V24）
 * @param maxBytes 1 要素の上限バイト数（V23）
 */
fun buildPlaylistFileName(
    playlists: List<ManifestPlaylist>,
    suffixLength: Int,
    maxBytes: Int,
): Map<String, String> {
    fun fileName(playlist: ManifestPlaylist, suffix: String): String {
        val stemBytes = maxBytes - M3U8_EXTENSION.length - suffix.encodeToByteArray().size
        val stem = truncateToBytes(sanitizeElement(playlist.name, "Untitled"), stemBytes, "Untitled")

        return stem + suffix + M3U8_EXTENSION
    }

    val plainNames = playlists.associate { it.persistentId to fileName(it, "") }
    val sharedNames = plainNames.values.groupingBy { it.lowercase() }.eachCount()
    val withSuffix = playlists.associate { playlist ->
        val isShared = sharedNames.getValue(plainNames.getValue(playlist.persistentId).lowercase()) > 1
        val hasNoName = sanitizeElement(playlist.name, "").isEmpty()
        val suffix = if (isShared || hasNoName) suffixOf(playlist.persistentId.take(suffixLength)) else ""

        playlist.persistentId to fileName(playlist, suffix)
    }

    // 接尾辞を付けても重なるものは、接尾辞を全文にする
    val stillShared = withSuffix.values.groupingBy { it.lowercase() }.eachCount()

    return playlists.associate { playlist ->
        val name = withSuffix.getValue(playlist.persistentId)
        val isStillShared = stillShared.getValue(name.lowercase()) > 1

        playlist.persistentId to if (isStillShared) fileName(playlist, suffixOf(playlist.persistentId)) else name
    }
}
