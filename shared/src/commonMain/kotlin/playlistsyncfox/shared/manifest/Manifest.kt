package playlistsyncfox.shared.manifest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 初版の `format_version`（V38）。M1 が読める版の範囲もこの 1 つだけ。 */
const val MANIFEST_FORMAT_VERSION = 1

private val persistentIdPattern = Regex("[0-9A-F]{16}")

/** `persistent_id` の書式。大文字 16 桁の 16 進。 */
fun isPersistentId(value: String): Boolean = persistentIdPattern.matches(value)

@Serializable
data class Manifest(
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("generated_at") val generatedAt: String,
    @SerialName("library_id") val libraryId: String,
    @SerialName("xml_modified_at") val xmlModifiedAt: String,
    @SerialName("content_digest") val contentDigest: String,
    val tracks: List<ManifestTrack>,
    val playlists: List<ManifestPlaylist>,
    @SerialName("unresolved_playlists") val unresolvedPlaylists: List<UnresolvedPlaylist>,
    val excluded: List<ExcludedTrack>,
)

@Serializable
data class ManifestTrack(
    @SerialName("persistent_id") val persistentId: String,
    val size: Long,
    @SerialName("modified_at") val modifiedAt: Long,
    @SerialName("file_name") val fileName: String,
    val extension: String,
    val name: String? = null,
    val artist: String? = null,
    val album: String? = null,
    @SerialName("album_artist") val albumArtist: String? = null,
    @SerialName("track_number") val trackNumber: Int? = null,
    @SerialName("disc_number") val discNumber: Int? = null,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val kind: String? = null,
    @SerialName("protected") val isProtected: Boolean,
    @SerialName("content_hash") val contentHash: String? = null,
)

@Serializable
data class ManifestPlaylist(
    @SerialName("persistent_id") val persistentId: String,
    val name: String,
    @SerialName("track_ids") val trackIds: List<String>,
)

@Serializable
data class UnresolvedPlaylist(
    @SerialName("persistent_id") val persistentId: String,
    @SerialName("last_known_name") val lastKnownName: String,
)

@Serializable
data class ExcludedTrack(
    val name: String? = null,
    val artist: String? = null,
    val reason: ExcludedReason,
)

@Serializable
enum class ExcludedReason {
    @SerialName("no_location") NO_LOCATION,
    @SerialName("undecodable") UNDECODABLE,
    @SerialName("not_found") NOT_FOUND,
    @SerialName("unreadable") UNREADABLE,
    @SerialName("no_id") NO_ID,
}
