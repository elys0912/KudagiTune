package playlistsyncfox.desktop.server

import kotlinx.coroutines.test.runTest
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.emptyLibrary
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.LibraryTrack
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import playlistsyncfox.itunes.library.UnidentifiedTrack
import playlistsyncfox.itunes.location.LocationFailure
import playlistsyncfox.itunes.location.TrackLocation
import playlistsyncfox.shared.manifest.ExcludedReason
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ID_OK = "1000000000000001"
private const val ID_NO_LOCATION = "1000000000000002"
private const val ID_UNDECODABLE = "1000000000000003"
private const val ID_MISSING = "1000000000000004"
private const val ID_UNREADABLE = "1000000000000005"
private const val ID_PROTECTED = "1000000000000006"
private const val ID_M4P = "1000000000000007"
private const val ID_ONLY_UNSELECTED = "1000000000000008"
private const val SELECTED = "2000000000000001"
private const val UNSELECTED = "2000000000000002"

class ManifestAssemblyTest {
    private val dir: Path = createTempDirectory("psfox-manifest")
    private val crypto = PcCrypto()
    private val modifiedMillis = 1_700_000_000_123L
    private val unreadable = dir.resolve("unreadable.mp3")

    private fun file(name: String, size: Int = 10): Path =
        dir.resolve(name).also {
            it.writeBytes(ByteArray(size))
            Files.setLastModifiedTime(it, FileTime.fromMillis(modifiedMillis))
        }

    private fun track(id: String, location: TrackLocation, name: String = "曲$id", isProtected: Boolean = false) =
        LibraryTrack(id, location, "File", name, "歌手", "アルバム", null, 2, 1, 180_000, "MPEG", isProtected)

    private fun found(path: Path) = TrackLocation.Found(path.toString())

    private fun playlist(id: String, vararg refs: TrackRef) =
        LibraryPlaylist(id, "リスト$id", null, PlaylistKind.Normal, refs.toList())

    private fun held(tracks: List<LibraryTrack>, playlists: List<LibraryPlaylist>, unidentified: List<UnidentifiedTrack> = emptyList(), libraryId: String? = "LIB") =
        HeldLibrary(
            LibraryReadResult(libraryId, tracks, unidentified, playlists, emptyLibrary().diagnostics),
            T0,
        )

    private fun build(library: HeldLibrary, selected: List<String>, names: Map<String, String> = emptyMap()) =
        ManifestBuilder(crypto) { if (it == unreadable) throw AccessDeniedException(it.toString()) }
            .build(library, selected, "LIB", names, T0)

    @Test
    fun `TD-67 除外の理由 5 種が reason に正しく出る`() {
        unreadable.writeBytes(ByteArray(1))
        val library = held(
            tracks = listOf(
                track(ID_OK, found(file("ok.mp3"))),
                track(ID_NO_LOCATION, TrackLocation.Absent),
                track(ID_UNDECODABLE, TrackLocation.Undecodable(LocationFailure.InvalidFormat)),
                track(ID_MISSING, found(dir.resolve("missing.mp3"))),
                track(ID_UNREADABLE, found(unreadable)),
            ),
            playlists = listOf(
                playlist(
                    SELECTED,
                    TrackRef.Identified(ID_OK), TrackRef.Identified(ID_NO_LOCATION), TrackRef.Identified(ID_UNDECODABLE),
                    TrackRef.Identified(ID_MISSING), TrackRef.Identified(ID_UNREADABLE), TrackRef.Unidentified(0),
                ),
            ),
            unidentified = listOf(UnidentifiedTrack("ID なしの曲", "歌手")),
        )

        val manifest = build(library, listOf(SELECTED)).manifest

        assertEquals(
            listOf(ExcludedReason.NO_LOCATION, ExcludedReason.UNDECODABLE, ExcludedReason.NOT_FOUND, ExcludedReason.UNREADABLE, ExcludedReason.NO_ID),
            manifest.excluded.map { it.reason },
        )
    }

    @Test
    fun `TD-68 保護付きは tracks に残り excluded に載らず、除外の曲はプレイリストの並びから外れる`() {
        val library = held(
            tracks = listOf(
                track(ID_OK, found(file("ok.mp3"))),
                track(ID_PROTECTED, found(file("p.m4a")), isProtected = true),
                track(ID_M4P, found(file("q.M4P"))),
                track(ID_MISSING, found(dir.resolve("missing.mp3"))),
            ),
            playlists = listOf(
                playlist(SELECTED, TrackRef.Identified(ID_OK), TrackRef.Identified(ID_MISSING), TrackRef.Identified(ID_PROTECTED), TrackRef.Identified(ID_M4P)),
            ),
        )

        val manifest = build(library, listOf(SELECTED)).manifest

        assertEquals(listOf(true, true), manifest.tracks.filter { it.persistentId in setOf(ID_PROTECTED, ID_M4P) }.map { it.isProtected })
        assertEquals(listOf(ExcludedReason.NOT_FOUND), manifest.excluded.map { it.reason })
        assertEquals(listOf(ID_OK, ID_PROTECTED, ID_M4P), manifest.playlists.single().trackIds)
    }

    @Test
    fun `TD-69 実在の確認は選んだプレイリストの曲だけで、選んだ ID が XML に無ければ unresolved_playlists に入る`() {
        val library = held(
            tracks = listOf(track(ID_OK, found(file("ok.mp3"))), track(ID_ONLY_UNSELECTED, found(dir.resolve("gone.mp3")))),
            playlists = listOf(playlist(SELECTED, TrackRef.Identified(ID_OK)), playlist(UNSELECTED, TrackRef.Identified(ID_ONLY_UNSELECTED))),
        )

        val manifest = build(library, listOf(SELECTED, "3000000000000009"), names = mapOf("3000000000000009" to "前に見た名前")).manifest

        assertTrue(manifest.excluded.isEmpty())
        assertEquals(listOf(ID_OK), manifest.tracks.map { it.persistentId })
        assertEquals(listOf("3000000000000009" to "前に見た名前"), manifest.unresolvedPlaylists.map { it.persistentId to it.lastKnownName })
    }

    @Test
    fun `TD-70 size と modified_at は実ファイルの値で、extension は小文字、拡張子なしは空`() {
        val library = held(
            tracks = listOf(
                track(ID_OK, found(file("Upper.MP3", size = 42))),
                track(ID_PROTECTED, found(file("noextension", size = 7))),
            ),
            playlists = listOf(playlist(SELECTED, TrackRef.Identified(ID_OK), TrackRef.Identified(ID_PROTECTED))),
        )

        val built = build(library, listOf(SELECTED))

        val upper = built.manifest.tracks.first { it.persistentId == ID_OK }
        assertEquals(42, upper.size)
        assertEquals(modifiedMillis, upper.modifiedAt)
        assertEquals("Upper.MP3", upper.fileName)
        assertEquals("mp3", upper.extension)
        assertEquals("", built.manifest.tracks.first { it.persistentId == ID_PROTECTED }.extension)
        assertEquals(42, built.tracks.getValue(ID_OK).size)
    }

    private fun service(library: HeldLibrary, fallback: String?): ManifestService {
        val settings = SettingsStore(dir.resolve("settings-${System.nanoTime()}.json")) { "pc" }
        settings.load()
        settings.update { it.copy(selectedPlaylistIds = listOf(SELECTED), libraryIdFallback = fallback) }
        return ManifestService(crypto, MutableClock(T0), settings, TrackTable()) { library }
    }

    @Test
    fun `TD-70 library_id は Library Persistent ID を使い、無ければ library_id_fallback を使う`() = runTest {
        val playlists = listOf(playlist(SELECTED))

        val withId = service(held(emptyList(), playlists, libraryId = "FROMXML"), fallback = "FALLBACK").forManifest()
        val withoutId = service(held(emptyList(), playlists, libraryId = null), fallback = "FALLBACK").forManifest()

        assertEquals("FROMXML", withId?.libraryId)
        assertEquals("FALLBACK", withoutId?.libraryId)
    }

    @Test
    fun `TD-70 library_id が XML にも設定にも無ければ乱数を作って設定に保存する`() = runTest {
        val service = service(held(emptyList(), listOf(playlist(SELECTED)), libraryId = null), fallback = null)

        val first = service.forManifest()!!.libraryId
        val second = service.forManifest()!!.libraryId

        assertEquals(32, first.length)
        assertEquals(first, second)
    }
}
