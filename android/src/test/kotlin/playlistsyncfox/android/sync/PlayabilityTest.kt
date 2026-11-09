package playlistsyncfox.android.sync

import playlistsyncfox.android.track
import playlistsyncfox.shared.manifest.ManifestTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun alac(kind: String = "Apple Lossless audio file") = track(1, extension = "m4a").copy(kind = kind)

private fun aac() = track(2, extension = "m4a").copy(kind = "AAC audio file")

private fun aiff(extension: String) = track(3, extension = extension).copy(kind = "AIFF audio file")

/** 端末のデコーダの一覧の偽物。[mimes] だけを復号でき、問い合わせた MIME を [asked] に残す。 */
private class FakeDecoders(private val mimes: Set<String>) {
    val asked = mutableListOf<String>()

    fun playability() = Playability { mime ->
        asked += mime
        mime in mimes
    }
}

class PlayabilityTest {
    @Test
    fun `X6-10 ALAC はデコーダが無ければ再生できず、あれば再生できる`() {
        assertFalse(FakeDecoders(emptySet()).playability().isPlayable(alac()))
        assertTrue(FakeDecoders(setOf("audio/alac")).playability().isPlayable(alac()))
    }

    @Test
    fun `X6-10 ALAC と AAC は拡張子が同じ m4a でも kind で見分け、日本語の kind も ALAC と見なす`() {
        val noDecoders = FakeDecoders(emptySet()).playability()

        assertTrue(noDecoders.isPlayable(aac()))
        assertFalse(noDecoders.isPlayable(alac(kind = "Apple Losslessオーディオファイル")))
    }

    @Test
    fun `X6-10 kind が無い m4a は AAC と見なして再生できる扱いにする`() {
        val noKind: ManifestTrack = track(1, extension = "m4a")

        assertTrue(FakeDecoders(emptySet()).playability().isPlayable(noKind))
    }

    @Test
    fun `X6-10 AIFF は aif と aiff の拡張子で見分け、一覧に対応が無ければ再生できない`() {
        val noDecoders = FakeDecoders(emptySet()).playability()
        val withDecoder = FakeDecoders(setOf("audio/x-aiff")).playability()

        for (extension in listOf("aif", "aiff")) {
            assertFalse(noDecoders.isPlayable(aiff(extension)))
            assertTrue(withDecoder.isPlayable(aiff(extension)))
        }
    }

    @Test
    fun `X6-10 ALAC と AIFF 以外の形式は一覧を調べず再生できる扱いにする`() {
        val decoders = FakeDecoders(emptySet())
        val playability = decoders.playability()

        assertTrue(playability.isPlayable(track(1, extension = "mp3")))
        assertTrue(playability.isPlayable(track(2, extension = "wav")))
        assertTrue(decoders.asked.isEmpty())
    }

    @Test
    fun `X6-10 デコーダの一覧は何曲を判定しても形式ごとに 1 回しか調べない`() {
        val decoders = FakeDecoders(setOf("audio/alac"))
        val playability = decoders.playability()

        repeat(3) { playability.isPlayable(alac()) }

        assertEquals(listOf("audio/alac"), decoders.asked)
    }
}
