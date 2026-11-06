package playlistsyncfox.shared.manifest

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

sealed interface ManifestReadResult {
    /** [duplicatesDropped] は、同じ `persistent_id` の曲を落とした件数。 */
    data class Loaded(val manifest: Manifest, val duplicatesDropped: Int) : ManifestReadResult

    /** 版が合わない。E8 ではなく、端末は更新の案内を出す。 */
    data class VersionMismatch(val receivedVersion: Int) : ManifestReadResult

    data class Broken(val reason: String) : ManifestReadResult
}

// 未知のキーは読み捨てる。空の項目は null にして、JSON には書かない。
private val manifestJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private val persistentIdPattern = Regex("[0-9A-F]{16}")

private val supportedFormatVersions = MANIFEST_FORMAT_VERSION..MANIFEST_FORMAT_VERSION

fun writeManifest(manifest: Manifest): String = manifestJson.encodeToString(Manifest.serializer(), manifest)

fun readManifest(text: String): ManifestReadResult {
    val root = try {
        manifestJson.parseToJsonElement(text)
    } catch (e: SerializationException) {
        return ManifestReadResult.Broken(firstLine(e))
    }

    // 版を先に読む。知らない版は、残りの形が違っても「壊れた」ではなく「版が合わない」にする
    val version = ((root as? JsonObject)?.get("format_version") as? JsonPrimitive)?.intOrNull
        ?: return ManifestReadResult.Broken("format_version を読めない")
    if (version !in supportedFormatVersions) return ManifestReadResult.VersionMismatch(version)

    val manifest = try {
        manifestJson.decodeFromJsonElement(Manifest.serializer(), root)
    } catch (e: SerializationException) {
        return ManifestReadResult.Broken(firstLine(e))
    }

    validateIds(manifest)?.let { return ManifestReadResult.Broken(it) }

    val uniqueTracks = manifest.tracks.distinctBy { it.persistentId }
    val knownIds = uniqueTracks.mapTo(HashSet()) { it.persistentId }
    if (manifest.playlists.any { playlist -> playlist.trackIds.any { it !in knownIds } }) {
        return ManifestReadResult.Broken("track_ids に tracks に無い ID がある")
    }

    return ManifestReadResult.Loaded(
        manifest.copy(tracks = uniqueTracks),
        duplicatesDropped = manifest.tracks.size - uniqueTracks.size,
    )
}

// 例外の本文には JSON の抜粋が付くことがあるため、先頭の 1 行だけを理由にする
private fun firstLine(e: Exception): String = e.message?.lineSequence()?.firstOrNull().orEmpty()

private fun validateIds(manifest: Manifest): String? {
    val ids = manifest.tracks.map { it.persistentId } +
        manifest.playlists.map { it.persistentId } +
        manifest.unresolvedPlaylists.map { it.persistentId }

    return if (ids.all { persistentIdPattern.matches(it) }) null else "persistent_id が大文字 16 桁の 16 進ではない"
}
