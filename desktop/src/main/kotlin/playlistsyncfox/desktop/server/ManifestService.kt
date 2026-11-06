package playlistsyncfox.desktop.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.manifest.Manifest
import java.time.Clock

/**
 * P1・P3 の処理（X5-1）。読み直し済みの XML と選択の集合から、M1 にマニフェストを作らせる。
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

    /** P1：曲の表を置き換える。 */
    suspend fun forManifest(): Manifest? = build()?.also { tracks.replace(it.tracks) }?.manifest

    /** P3：曲の表は置き換えない。 */
    suspend fun forDigest(): String? = build()?.manifest?.contentDigest

    private suspend fun build(): BuiltManifest? {
        val held = library() ?: return null
        val current = settings.current
        return withContext(Dispatchers.IO) {
            synchronized(knownNames) {
                held.result.playlists.forEach { knownNames[it.persistentId] = it.name }
                builder.build(held, current.selectedPlaylistIds, libraryIdOf(held), knownNames.toMap(), clock.instant())
            }
        }
    }

    // XML に無いときだけの代わりの乱数。最初に必要になったときに作って保存する（B2-4）
    private fun libraryIdOf(held: HeldLibrary): String =
        held.result.libraryPersistentId
            ?: settings.current.libraryIdFallback
            ?: crypto.randomBytes(16).toHexString().also { fallback -> settings.update { it.copy(libraryIdFallback = fallback) } }
}
