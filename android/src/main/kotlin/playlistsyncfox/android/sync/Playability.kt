package playlistsyncfox.android.sync

import android.media.MediaCodecList
import playlistsyncfox.shared.manifest.ManifestTrack

/** ALAC を復号するデコーダの MIME。 */
private const val MIME_ALAC = "audio/alac"

/** AIFF を復号するデコーダの MIME の候補。標準の端末は一覧に出さないため、多くの端末で非対応になる（実機で確かめる。C-9）。 */
private val MIMES_AIFF = listOf("audio/x-aiff", "audio/aiff")

/** iTunes の「種類」（`kind`）に含まれる ALAC の印。日本語の表示（「Apple Losslessオーディオファイル」）にも含まれる。 */
private const val KIND_ALAC = "Apple Lossless"

private val AIFF_EXTENSIONS = setOf("aif", "aiff")

/**
 * 端末で再生できる形式かの判定（X6-10。B2-8 の規則 2）。対象は ALAC と AIFF で、端末のデコーダの一覧に
 * その形式を復号できるデコーダがあるかで調べる。ほかの形式は再生できるものとして扱う。
 *
 * ALAC と AAC は拡張子がどちらも `m4a` で見分けられないため、ALAC は `kind` で見分ける。AIFF は拡張子で見分ける。
 * デコーダの一覧は同期の実行の間は変わらないので、必要になったときに 1 回だけ調べる。
 * [hasDecoder] は、その MIME を復号できるデコーダが端末にあるか。試験では偽物を渡す。
 */
class Playability(private val hasDecoder: (mime: String) -> Boolean = ::deviceHasDecoder) {
    private val canDecodeAlac by lazy { hasDecoder(MIME_ALAC) }
    private val canDecodeAiff by lazy { MIMES_AIFF.any(hasDecoder) }

    fun isPlayable(track: ManifestTrack): Boolean = when {
        isAlac(track) -> canDecodeAlac
        isAiff(track) -> canDecodeAiff
        else -> true
    }

    private fun isAlac(track: ManifestTrack) = track.kind?.contains(KIND_ALAC, ignoreCase = true) == true

    private fun isAiff(track: ManifestTrack) = track.extension.lowercase() in AIFF_EXTENSIONS
}

/** 端末のデコーダの一覧（`MediaCodecList`）に、[mime] を復号できるものがあるか。 */
internal fun deviceHasDecoder(mime: String): Boolean =
    MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
    }
