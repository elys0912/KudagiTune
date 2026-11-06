package playlistsyncfox.shared.manifest

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.playlist
import playlistsyncfox.shared.sampleManifest
import playlistsyncfox.shared.track
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

// content_digest は実体の SHA-256 の値まで確かめるため、JVM の標準の実体を渡す
private object Sha256Crypto : CryptoProvider {
    override fun sha256(input: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(input)
}

class ContentDigestTest {
    private val idA = "A000000000000001"
    private val idB = "B000000000000002"

    private fun digest(manifest: Manifest) = computeContentDigest(Sha256Crypto, manifest)

    @Test
    fun `TS-08 手で作った試料の指紋は別に計算した固定の値と一致する`() {
        val sample = sampleManifest(
            tracks = listOf(
                ManifestTrack(
                    persistentId = "0000000000000001",
                    size = 1000,
                    modifiedAt = 2000,
                    fileName = "a.mp3",
                    extension = "mp3",
                    name = "N",
                    trackNumber = 3,
                    kind = "K",
                    isProtected = false,
                ),
            ),
            playlists = listOf(playlist("00000000000000A1", "L", listOf("0000000000000001", "0000000000000001"))),
        ).copy(unresolvedPlaylists = listOf(UnresolvedPlaylist("00000000000000B1", "Old")))

        assertEquals("f896ab01452d0dd19dc8cb1c2fe476d8f6cec0fb9a1544cf809b16ca80473388", digest(sample))
    }

    @Test
    fun `TS-09 一覧の元の並びを入れ替えても指紋は同じになる`() {
        val ordered = sampleManifest(
            tracks = listOf(track(idA), track(idB)),
            playlists = listOf(playlist("C000000000000001", trackIds = listOf(idA)), playlist("D000000000000001")),
        ).copy(
            unresolvedPlaylists = listOf(
                UnresolvedPlaylist("E000000000000001", "x"),
                UnresolvedPlaylist("F000000000000001", "y"),
            ),
        )
        val shuffled = ordered.copy(
            tracks = ordered.tracks.reversed(),
            playlists = ordered.playlists.reversed(),
            unresolvedPlaylists = ordered.unresolvedPlaylists.reversed(),
        )

        assertEquals(digest(ordered), digest(shuffled))
    }

    @Test
    fun `TS-10 generated_at と library_id と excluded だけを変えても指紋は同じになる`() {
        val base = sampleManifest(tracks = listOf(track(idA)))
        val changed = base.copy(
            generatedAt = "2030-01-01T00:00:00.000Z",
            libraryId = "OTHER",
            excluded = listOf(ExcludedTrack("n", "a", ExcludedReason.NO_LOCATION)),
        )

        assertEquals(digest(base), digest(changed))
    }

    @Test
    fun `TS-11 プレイリストの track_ids の順を入れ替えると指紋は変わる`() {
        val tracks = listOf(track(idA), track(idB))
        val forward = sampleManifest(tracks, listOf(playlist("C000000000000001", trackIds = listOf(idA, idB))))
        val backward = sampleManifest(tracks, listOf(playlist("C000000000000001", trackIds = listOf(idB, idA))))

        assertNotEquals(digest(forward), digest(backward))
    }

    @Test
    fun `TS-12 隣り合う文字列の境界が違えば連結が同じでも指紋は変わる`() {
        val abAndC = sampleManifest(listOf(track(idA, name = "ab", artist = "c")))
        val aAndBc = sampleManifest(listOf(track(idA, name = "a", artist = "bc")))

        assertNotEquals(digest(abAndC), digest(aAndBc))
    }
}
