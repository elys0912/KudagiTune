package playlistsyncfox.shared.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// P1・P3 の要求の本文（選んだ ID の配列）と P7 の応答（プレイリストの木）。X3-1b。

/** P7 の木の 1 つ。葉は [children] が無く [trackCount] を持ち、フォルダは [children] を持つ。 */
@Serializable
data class PlaylistNode(
    @SerialName("persistent_id") val id: String,
    val name: String,
    @SerialName("track_count") val trackCount: Int? = null,
    val children: List<PlaylistNode>? = null,
)

@Serializable
private class PlaylistTreeBody(val playlists: List<PlaylistNode>)

@Serializable
private class PlaylistIdsBody(@SerialName("playlist_ids") val playlistIds: List<String>)

sealed interface PlaylistIdsResult {
    /** 重複を除いた、入力の順の ID。 */
    data class Ids(val ids: List<String>) : PlaylistIdsResult

    data object Invalid : PlaylistIdsResult
}

/**
 * 要求の本文の検査（境界）。JSON の形、各 ID の書式（大文字 16 桁の 16 進）、個数（[maxCount]）を確かめる。
 * 失敗の文に本文の中身を入れない。
 */
fun parsePlaylistIds(text: String, maxCount: Int): PlaylistIdsResult {
    val body = try {
        boundaryJson.decodeFromString(PlaylistIdsBody.serializer(), text)
    } catch (_: IllegalArgumentException) {
        // SerializationException は IllegalArgumentException の子。どちらも本文の不正として扱う
        return PlaylistIdsResult.Invalid
    }
    if (body.playlistIds.size > maxCount || body.playlistIds.any { !isPersistentId(it) }) return PlaylistIdsResult.Invalid
    return PlaylistIdsResult.Ids(body.playlistIds.distinct())
}

fun writePlaylistIds(ids: List<String>): String = boundaryJson.encodeToString(PlaylistIdsBody.serializer(), PlaylistIdsBody(ids))

fun writePlaylistTree(nodes: List<PlaylistNode>): String = boundaryJson.encodeToString(PlaylistTreeBody.serializer(), PlaylistTreeBody(nodes))

/** 読めない、ID の書式が違うときは null。 */
fun readPlaylistTree(text: String): List<PlaylistNode>? {
    val body = try {
        boundaryJson.decodeFromString(PlaylistTreeBody.serializer(), text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    fun valid(node: PlaylistNode): Boolean = isPersistentId(node.id) && node.children.orEmpty().all(::valid)
    return body.playlists.takeIf { it.all(::valid) }
}

fun leafIds(node: PlaylistNode): List<String> = node.children?.flatMap(::leafIds) ?: listOf(node.id)

/** フォルダのチェック：配下の葉を一括で選ぶ・外す。フォルダ自身は保存しない。 */
fun withFolderChecked(selected: List<String>, folder: PlaylistNode, checked: Boolean): List<String> {
    val leaves = leafIds(folder)
    return if (checked) selected + leaves.filter { it !in selected } else selected.filter { it !in leaves }
}

/** 葉のチェック。一覧に無い ID の「外す」も、チェックを外す操作と同じ。 */
fun withLeafChecked(selected: List<String>, id: String, checked: Boolean): List<String> = when {
    !checked -> selected - id
    id in selected -> selected
    else -> selected + id
}
