package playlistsyncfox.shared.result

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import playlistsyncfox.shared.manifest.boundaryJson

/** P8 の `stage`。SyncProgress の段のうち `finished` を除く 6 つ（B4-4 の 5b）。 */
@Serializable
enum class ProgressStage {
    @SerialName("connecting") Connecting,
    @SerialName("manifest") Manifest,
    @SerialName("diff") Diff,
    @SerialName("capacity") Capacity,
    @SerialName("transfer") Transfer,
    @SerialName("playlist") Playlist,
}

/** P8 の本文（B4-4 の 5b。D22）。曲名、ID、パスを含めない。 */
@Serializable
data class ProgressReport(
    val stage: ProgressStage,
    @SerialName("done_tracks") val doneTracks: Int,
    @SerialName("total_tracks") val totalTracks: Int,
    @SerialName("done_bytes") val doneBytes: Long,
    @SerialName("total_bytes") val totalBytes: Long,
)

fun writeProgressReport(report: ProgressReport): String = boundaryJson.encodeToString(ProgressReport.serializer(), report)

/** PC の境界の検査。JSON の形、`stage` の値、数が 0 以上でなければ null（400）。`done` が `total` を超えても拒まない。 */
fun readProgressReport(text: String): ProgressReport? {
    val report = try {
        boundaryJson.decodeFromString(ProgressReport.serializer(), text)
    } catch (_: SerializationException) {
        return null
    }
    val counts = listOf(report.doneTracks.toLong(), report.totalTracks.toLong(), report.doneBytes, report.totalBytes)
    return report.takeIf { counts.all { it >= 0 } }
}
