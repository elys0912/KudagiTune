package playlistsyncfox.shared.diff

import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.manifest.UnresolvedPlaylist

/** 端末が置いた曲の記録。値はマニフェストのまま持つ。 */
data class SyncRecord(
    val persistentId: String,
    val size: Long,
    val modifiedAt: Long,
    val placedPath: String,
)

enum class RemovalGate { Allowed, LibraryChanged, NeedsConfirmation }

data class Diff(
    val toAdd: List<ManifestTrack>,
    val toReplace: List<ManifestTrack>,
    val toRemove: List<SyncRecord>,
    val unchangedCount: Int,
    val unplayable: List<ManifestTrack>,
    /** 端末が再生できない形式の曲（保護付きを除く）。転送せず、端末に今ある曲も消さない。 */
    val unsupported: List<ManifestTrack>,
    val removalGate: RemovalGate,
) {
    fun isEmpty(): Boolean = toAdd.isEmpty() && toReplace.isEmpty() && toRemove.isEmpty()
}

/**
 * @param presentIds 置いた場所にファイルが今ある曲の `persistent_id`
 * @param recordedLibraryId 記録済みのライブラリ ID。初回は null
 * @param removalConfirmThreshold 「消す」の確認の閾値（V20）
 * @param isPlayable 端末が再生できる形式の曲か。保護付きでない曲のうち、偽のものは足す・置き換えに入れず [Diff.unsupported] にだけ入れる
 */
fun computeDiff(
    tracks: List<ManifestTrack>,
    records: List<SyncRecord>,
    presentIds: Set<String>,
    manifestLibraryId: String,
    recordedLibraryId: String?,
    removalConfirmThreshold: Int,
    unresolvedPlaylists: List<UnresolvedPlaylist>,
    isPlayable: (ManifestTrack) -> Boolean = { true },
): Diff {
    val recordsById = records.associateBy { it.persistentId }
    val toAdd = mutableListOf<ManifestTrack>()
    val toReplace = mutableListOf<ManifestTrack>()
    val unplayable = mutableListOf<ManifestTrack>()
    val unsupported = mutableListOf<ManifestTrack>()
    var unchangedCount = 0

    for (track in tracks.sortedBy { it.persistentId }) {
        val record = recordsById[track.persistentId]
        val isProtectedTrack = track.isProtected || track.extension == "m4p"

        when {
            isProtectedTrack -> unplayable += track
            !isPlayable(track) -> unsupported += track
            record == null -> toAdd += track
            // ファイルが無いときは、置き換えより先に補充として足す
            track.persistentId !in presentIds -> toAdd += track
            track.size != record.size || track.modifiedAt != record.modifiedAt -> toReplace += track
            else -> unchangedCount++
        }
    }

    // 保護付きと再生できない形式の曲も tracks にあるので、消す対象にならない
    val manifestIds = tracks.mapTo(HashSet()) { it.persistentId }
    val toRemove = records.filter { it.persistentId !in manifestIds }.sortedBy { it.persistentId }

    val removalGate = when {
        toRemove.isEmpty() -> RemovalGate.Allowed
        recordedLibraryId != null && recordedLibraryId != manifestLibraryId -> RemovalGate.LibraryChanged
        toRemove.size > removalConfirmThreshold ||
            toRemove.size == records.size ||
            unresolvedPlaylists.isNotEmpty() -> RemovalGate.NeedsConfirmation
        else -> RemovalGate.Allowed
    }

    return Diff(toAdd, toReplace, toRemove, unchangedCount, unplayable, unsupported, removalGate)
}

/** 足す曲と置き換える曲の新しい `size` の合計。消す分は引かない。 */
fun requiredBytes(diff: Diff): Long = (diff.toAdd + diff.toReplace).sumOf { it.size }
