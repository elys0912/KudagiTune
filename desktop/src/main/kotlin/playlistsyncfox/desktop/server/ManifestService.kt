package playlistsyncfox.desktop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.PlaylistNode
import java.time.Clock

/** `library_id_fallback` の乱数のバイト数（B2-4）。16 進の文字列にして保存する。 */
private const val LIBRARY_ID_FALLBACK_BYTES = 16

/**
 * P1・P3・P7 の処理（X5-1）。読み直し済みの XML と、要求の本文で渡された選択から、M1 にマニフェストを作らせる。
 * 選択は保存しない。
 * 読めない状態（[library] が null）では null を返し、呼び出し側が 503（E4）にする。
 */
class ManifestService(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val settings: SettingsStore,
    private val tracks: TrackTable,
    private val library: suspend () -> HeldLibrary?,
) {
    private val builder = ManifestBuilder(crypto)
    private val knownNames = HashMap<String, String>()

    /** P1：曲の表を、この要求の選択で作ったものに置き換える。 */
    suspend fun forManifest(selectedIds: List<String>): Manifest? =
        build(selectedIds)?.also { tracks.replace(it.tracks) }?.manifest

    /** P3：曲の表は置き換えない。 */
    suspend fun forDigest(selectedIds: List<String>): String? = build(selectedIds)?.manifest?.contentDigest

    /** P7：プレイリストの木。曲の表は置き換えない。 */
    suspend fun forPlaylists(): List<PlaylistNode>? = library()?.let { buildPlaylistTree(it.result.playlists) }

    private suspend fun build(selectedIds: List<String>): BuiltManifest? {
        val held = library() ?: return null
        return withContext(Dispatchers.IO) {
            // knownNames の保護に加え、libraryIdOf の乱数の生成と保存も直列にする（並行する要求が別々の値を作らないため）
            synchronized(knownNames) {
                held.result.playlists.forEach { knownNames[it.persistentId] = it.name }
                builder.build(held, selectedIds, libraryIdOf(held), knownNames.toMap(), clock.instant())
            }
        }
    }

    // XML に無いときだけの代わりの乱数。最初に必要になったときに作って保存する（B2-4）
    private fun libraryIdOf(held: HeldLibrary): String =
        held.result.libraryPersistentId
            ?: settings.current.libraryIdFallback
            ?: crypto.randomBytes(LIBRARY_ID_FALLBACK_BYTES).toHexString().also { fallback -> settings.update { it.copy(libraryIdFallback = fallback) } }
}
