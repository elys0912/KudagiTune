package playlistsyncfox.shared.result

/** E1〜E9。[number] は E の番号。 */
sealed class SyncError(val number: Int, val scope: ErrorScope) {
    data class ConnectionFailed(val cause: ConnectionFailure) : SyncError(1, ErrorScope.Run)
    data class KeyMismatch(val cause: KeyMismatchCause) : SyncError(2, ErrorScope.Run)
    data class InsufficientStorage(val shortfallBytes: Long, val phase: StoragePhase) : SyncError(3, ErrorScope.Run)
    data object XmlUnavailable : SyncError(4, ErrorScope.Run)
    data class FileMissing(
        override val persistentId: String,
        override val name: String,
        val reason: FileMissingReason,
    ) : SyncError(5, ErrorScope.Track), TrackError
    data class TransferInterrupted(val persistentId: String?, val attempts: Int) : SyncError(6, ErrorScope.Run)
    data object TimeLimit : SyncError(7, ErrorScope.Run)
    data class ManifestStale(
        override val persistentId: String,
        override val name: String,
        val retried: Boolean,
    ) : SyncError(8, ErrorScope.Track), TrackError
    data class WriteFailed(
        override val persistentId: String,
        override val name: String,
        val step: WriteStep,
    ) : SyncError(9, ErrorScope.Track), TrackError
}

/** 曲単位の E（E5・E8・E9）が持つ、曲の `persistent_id` と曲名。 */
sealed interface TrackError {
    val persistentId: String
    val name: String
}

/** 同期全体に効くか、曲単位か。 */
enum class ErrorScope { Run, Track }

enum class ConnectionFailure { NameResolution, Refused, Timeout }

enum class KeyMismatchCause { SignatureMismatch, PinMismatch, PairingRevoked }

enum class StoragePhase { PreCheck, Writing }

enum class FileMissingReason { NoLocation, Undecodable, NotFound, Unreadable }

enum class WriteStep { TempFileWrite, Move, MediaStoreRegistration }
