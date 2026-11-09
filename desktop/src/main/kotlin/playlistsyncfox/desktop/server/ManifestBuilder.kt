package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.LibraryTrack
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import playlistsyncfox.itunes.location.TrackLocation
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.manifest.ExcludedReason
import playlistsyncfox.shared.manifest.ExcludedTrack
import playlistsyncfox.shared.manifest.MANIFEST_FORMAT_VERSION
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.manifest.UnresolvedPlaylist
import playlistsyncfox.shared.manifest.computeContentDigest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class BuiltManifest(val manifest: Manifest, val tracks: Map<String, TrackEntry>)

private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** UTC、ミリ秒まで、末尾 `Z`（X3-1）。 */
fun formatIsoMillis(instant: Instant): String = isoMillis.format(instant)

/**
 * 選んだプレイリストからマニフェストを組む（X2-4 末尾の順、B2-2、B2-4）。実在の確認は選んだプレイリストの曲だけ。
 * 除外の理由の最終の決定（実在しない、読めない）と保護の判定はここで行う。
 */
class ManifestBuilder(
    private val crypto: CryptoProvider,
    // 読み取れるかの確かめ。他のプロセスの占有や権限を試験で再現できないため、差し替えられるようにする
    private val openForRead: (Path) -> Unit = { Files.newInputStream(it).close() },
) {
    /**
     * @param libraryId `Library Persistent ID`、無ければ `library_id_fallback`
     * @param lastKnownNames XML に無い ID の最後に知っていた名前
     */
    fun build(
        library: HeldLibrary,
        selectedIds: List<String>,
        libraryId: String,
        lastKnownNames: Map<String, String>,
        now: Instant,
    ): BuiltManifest {
        val result = library.result
        val selection = resolveSelection(result.playlists, selectedIds, lastKnownNames)

        val collector = TrackCollector(result)
        val playlists = selection.resolved.map { playlist ->
            ManifestPlaylist(playlist.persistentId, playlist.name, playlist.items.mapNotNull(collector::collect))
        }

        val withoutDigest = Manifest(
            formatVersion = MANIFEST_FORMAT_VERSION,
            generatedAt = formatIsoMillis(now),
            libraryId = libraryId,
            xmlModifiedAt = formatIsoMillis(library.xmlModified),
            contentDigest = "",
            tracks = collector.tracks.values.toList(),
            playlists = playlists,
            unresolvedPlaylists = selection.unresolved,
            excluded = collector.excluded.values.toList(),
        )
        return BuiltManifest(withoutDigest.copy(contentDigest = computeContentDigest(crypto, withoutDigest)), collector.entries)
    }

    private class Selection(val resolved: List<LibraryPlaylist>, val unresolved: List<UnresolvedPlaylist>)

    /** 選んだ ID を、通常のプレイリスト（resolved）と、XML に無いか種別が通常でないもの（unresolved）に分ける。重複は除く。 */
    private fun resolveSelection(
        playlists: List<LibraryPlaylist>,
        selectedIds: List<String>,
        lastKnownNames: Map<String, String>,
    ): Selection {
        val byId = playlists.associateBy { it.persistentId }
        val resolved = mutableListOf<LibraryPlaylist>()
        val unresolved = mutableListOf<UnresolvedPlaylist>()
        for (id in selectedIds.distinct()) {
            val playlist = byId[id]
            if (playlist != null && playlist.kind == PlaylistKind.Normal) {
                resolved += playlist
            } else {
                unresolved += UnresolvedPlaylist(id, playlist?.name ?: lastKnownNames[id].orEmpty())
            }
        }
        return Selection(resolved, unresolved)
    }

    /**
     * 曲を `tracks`（配る）と `excluded`（配らない）に振り分ける。同じ曲は 1 度だけ確かめる。
     * 除外の表は、ID を持つ曲と持たない曲の登場順を保つため、1 つの表にする。
     */
    private inner class TrackCollector(private val result: LibraryReadResult) {
        private val trackById = result.tracks.associateBy { it.persistentId }
        val tracks = LinkedHashMap<String, ManifestTrack>()
        val entries = LinkedHashMap<String, TrackEntry>()
        val excluded = LinkedHashMap<Any, ExcludedTrack>()

        /** プレイリストの並びに載せる曲の ID を返す。除外の曲は null。 */
        fun collect(ref: TrackRef): String? = when (ref) {
            is TrackRef.Unidentified -> {
                excluded.getOrPut(ref.index) {
                    val track = result.unidentifiedTracks[ref.index]
                    ExcludedTrack(track.name, track.artist, ExcludedReason.NO_ID)
                }
                null
            }
            is TrackRef.Identified -> collectIdentified(ref.persistentId)
        }

        private fun collectIdentified(id: String): String? {
            if (id in tracks) return id
            if (id in excluded) return null

            val track = trackById.getValue(id)
            return when (val checked = check(track)) {
                is Checked.Ok -> {
                    tracks[id] = checked.manifestTrack
                    entries[id] = checked.entry
                    id
                }
                is Checked.Excluded -> {
                    excluded[id] = ExcludedTrack(track.name, track.artist, checked.reason)
                    null
                }
            }
        }
    }

    private sealed interface Checked {
        class Ok(val manifestTrack: ManifestTrack, val entry: TrackEntry) : Checked

        class Excluded(val reason: ExcludedReason) : Checked
    }

    private fun check(track: LibraryTrack): Checked {
        val path = when (val location = track.location) {
            TrackLocation.Absent -> return Checked.Excluded(ExcludedReason.NO_LOCATION)
            is TrackLocation.Undecodable -> return Checked.Excluded(ExcludedReason.UNDECODABLE)
            is TrackLocation.Found -> try {
                Path.of(location.windowsPath)
            } catch (_: InvalidPathException) {
                return Checked.Excluded(ExcludedReason.NOT_FOUND)
            }
        }
        if (!Files.isRegularFile(path)) return Checked.Excluded(ExcludedReason.NOT_FOUND)
        val size: Long
        val modifiedAt: Long
        try {
            openForRead(path)
            size = Files.size(path)
            modifiedAt = Files.getLastModifiedTime(path).toMillis()
        } catch (_: IOException) {
            return Checked.Excluded(ExcludedReason.UNREADABLE)
        }

        val fileName = path.fileName.toString()
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val manifestTrack = ManifestTrack(
            persistentId = track.persistentId,
            size = size,
            modifiedAt = modifiedAt,
            fileName = fileName,
            extension = extension,
            name = track.name,
            artist = track.artist,
            album = track.album,
            albumArtist = track.albumArtist,
            trackNumber = track.trackNumber?.toIntOrNull(),
            discNumber = track.discNumber?.toIntOrNull(),
            durationMs = track.totalTimeMs?.toIntOrNull(),
            kind = track.kind,
            isProtected = track.isProtected || extension == "m4p",
        )
        return Checked.Ok(manifestTrack, TrackEntry(size, modifiedAt, path))
    }

    private fun Long.toIntOrNull(): Int? = if (this in Int.MIN_VALUE..Int.MAX_VALUE) toInt() else null
}
