package playlistsyncfox.shared

import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.manifest.ManifestTrack

/** 消す件数の確認の閾値（V20）。 */
const val V20 = 20

/** 1 要素の上限バイト数（V23）。 */
const val V23 = 240

/** 接尾辞に使う `persistent_id` の文字数（V24）。 */
const val V24 = 8

/** 先頭 8 文字が [head]、後ろが [tail] の 16 桁の ID。 */
fun pid(head: Char, tail: Int = 1): String = head.toString().repeat(8) + tail.toString(16).uppercase().padStart(8, '0')

fun track(
    id: String,
    name: String? = "T",
    artist: String? = "Ar",
    album: String? = "Al",
    albumArtist: String? = null,
    extension: String = "mp3",
    size: Long = 100,
    modifiedAt: Long = 1,
    isProtected: Boolean = false,
): ManifestTrack = ManifestTrack(
    persistentId = id,
    size = size,
    modifiedAt = modifiedAt,
    fileName = "file.$extension",
    extension = extension,
    name = name,
    artist = artist,
    album = album,
    albumArtist = albumArtist,
    isProtected = isProtected,
)

fun record(id: String, size: Long = 100, modifiedAt: Long = 1, placedPath: String = "Ar/Al/$id.mp3"): SyncRecord =
    SyncRecord(id, size, modifiedAt, placedPath)

fun playlist(id: String, name: String = "L", trackIds: List<String> = emptyList()): ManifestPlaylist =
    ManifestPlaylist(id, name, trackIds)

fun sampleManifest(
    tracks: List<ManifestTrack> = emptyList(),
    playlists: List<ManifestPlaylist> = emptyList(),
): Manifest = Manifest(
    formatVersion = 1,
    generatedAt = "2026-01-01T00:00:00.000Z",
    libraryId = "LIB",
    xmlModifiedAt = "2026-01-01T00:00:00.000Z",
    contentDigest = "",
    tracks = tracks,
    playlists = playlists,
    unresolvedPlaylists = emptyList(),
    excluded = emptyList(),
)
