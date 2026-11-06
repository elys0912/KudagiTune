package playlistsyncfox.desktop.watch

import kotlinx.coroutines.delay
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.itunes.library.LibraryReadOutcome
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.plist.readLibrary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.getLastModifiedTime

/** サイズと更新日時。変われば書き込みの途中とみなす。 */
data class FileStamp(val size: Long, val modifiedAtMillis: Long)

/**
 * 書き込みが終わるまで待つ（X2-4）。V18 の間、サイズと更新日時が変わらなければ安定。
 * ファイルが無い間（置き換えの数ミリ秒）は変化とみなして待ちを最初からにする。
 */
class StabilityWaiter(
    private val stat: () -> FileStamp?,
    private val interval: Duration = DesktopValues.XML_STABLE_WAIT,
) {
    suspend fun awaitStable() {
        while (true) {
            val before = stat()
            delay(interval.toMillis())
            if (before != null && before == stat()) return
        }
    }
}

sealed interface LoadOutcome {
    class Loaded(val result: LibraryReadResult, val xmlModified: Instant) : LoadOutcome

    /** V6 の回数を読んで読めなかった。 */
    data object GaveUp : LoadOutcome
}

/** XML を開いて M2 に渡す。構文の不正、構造の違い、ファイルが開けない失敗はどれも V6 の間隔で読み直す。 */
class LibraryLoader(
    private val path: Path,
    private val readBytes: (Path) -> ByteArray = Files::readAllBytes,
) {
    suspend fun load(): LoadOutcome {
        repeat(DesktopValues.LOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(DesktopValues.LOAD_RETRY_DELAY.toMillis())
            val modified = try {
                Instant.ofEpochMilli(path.getLastModifiedTime().toMillis())
            } catch (_: IOException) {
                return@repeat
            }
            val outcome = try {
                readLibrary(readBytes(path))
            } catch (_: IOException) {
                return@repeat
            }
            if (outcome is LibraryReadOutcome.Read) return LoadOutcome.Loaded(outcome.result, modified)
        }
        return LoadOutcome.GaveUp
    }
}
