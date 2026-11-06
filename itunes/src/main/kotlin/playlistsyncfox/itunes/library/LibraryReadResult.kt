package playlistsyncfox.itunes.library

import playlistsyncfox.itunes.location.LocationFailure
import playlistsyncfox.itunes.location.TrackLocation
import java.time.Instant

/** M2 の出力（X2-3）。ファイルの実在の確認の前の状態。 */
data class LibraryReadResult(
    val libraryPersistentId: String?,
    val tracks: List<LibraryTrack>,
    val unidentifiedTracks: List<UnidentifiedTrack>,
    val playlists: List<LibraryPlaylist>,
    val diagnostics: ReadDiagnostics,
)

data class LibraryTrack(
    val persistentId: String,
    val location: TrackLocation,
    val trackType: String?,
    val name: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val trackNumber: Long?,
    val discNumber: Long?,
    val totalTimeMs: Long?,
    val kind: String?,
    val isProtected: Boolean,
)

data class UnidentifiedTrack(val name: String?, val artist: String?)

data class LibraryPlaylist(
    val persistentId: String,
    val name: String,
    val parentPersistentId: String?,
    val kind: PlaylistKind,
    val items: List<TrackRef>,
)

sealed interface PlaylistKind {
    data object Normal : PlaylistKind

    data class Excluded(val reason: PlaylistExclusion) : PlaylistKind
}

enum class PlaylistExclusion { Master, DistinguishedKind, Folder }

sealed interface TrackRef {
    data class Identified(val persistentId: String) : TrackRef

    /** `LibraryReadResult.unidentifiedTracks` の位置。 */
    data class Unidentified(val index: Int) : TrackRef
}

data class ReadDiagnostics(
    val trackCount: Int,
    val playlistCount: Int,
    val droppedTrackRefs: Int,
    val duplicatePersistentIds: Int,
    val malformedEntries: Int,
    val locationFailures: Map<LocationFailure, Int>,
    val applicationVersion: String?,
    val xmlDate: Instant?,
)

sealed interface LibraryReadOutcome {
    data class Read(val result: LibraryReadResult) : LibraryReadOutcome

    data class Failed(val error: LibraryReadError) : LibraryReadOutcome
}

/** バイト列を読めない失敗は M3 が包むので、ここには持たない。 */
enum class LibraryReadError { InvalidSyntax, InvalidStructure }
