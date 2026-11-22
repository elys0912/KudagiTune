package playlistsyncfox.desktop.watch

import kotlinx.coroutines.delay
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.settings.AppLog
import playlistsyncfox.itunes.library.LibraryReadOutcome
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.ReadDiagnostics
import playlistsyncfox.itunes.plist.readLibrary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.getLastModifiedTime

/** サイズと更新日時。変われば書き込みの途中とみなす。 */
data class FileStamp(val size: Long, val modifiedAtMillis: Long)

/**
 * 書き込みが終わるまで待つ（X2-4）。V18 の間、サイズと更新日時が変わらなければ安定。
 * ファイルが無い間（置き換えの数ミリ秒）は変化とみなして待ちを最初からにする。
 */
class StabilityWaiter(private val stat: () -> FileStamp?) {
    suspend fun awaitStable() {
        while (true) {
            val before = stat()
            delay(DesktopValues.XML_STABLE_WAIT.toMillis())
            if (before != null && before == stat()) return
        }
    }
}

sealed interface LoadOutcome {
    class Loaded(val result: LibraryReadResult, val xmlModified: Instant) : LoadOutcome

    /** V6 の回数を読んで読めなかった。 */
    data object GaveUp : LoadOutcome
}

private const val LOG_MODULE = "xml"

/**
 * XML を開いて M2 に渡す。構文の不正、構造の違い、ファイルが開けない失敗はどれも V6 の間隔で読み直す。
 * 読めたときの診断、読み取りの失敗、読み直し、諦めを PC のログに 1 行ずつ書く（X2-2。パスを出してよいのは PC のログだけ）。
 */
class LibraryLoader(
    private val path: Path,
    private val log: AppLog,
    private val readBytes: (Path) -> ByteArray = Files::readAllBytes,
) {
    suspend fun load(): LoadOutcome {
        repeat(DesktopValues.LOAD_ATTEMPTS) { attempt ->
            if (attempt > 0) {
                delay(DesktopValues.LOAD_RETRY_DELAY.toMillis())
                log.info(LOG_MODULE, "XML を読み直す ${attemptText(attempt)}")
            }
            val modified = try {
                Instant.ofEpochMilli(path.getLastModifiedTime().toMillis())
            } catch (e: IOException) {
                logReadFailure(attempt, e.javaClass.simpleName)
                return@repeat
            }
            val outcome = try {
                readLibrary(readBytes(path))
            } catch (e: IOException) {
                logReadFailure(attempt, e.javaClass.simpleName)
                return@repeat
            }
            when (outcome) {
                is LibraryReadOutcome.Read -> {
                    log.info(LOG_MODULE, diagnosticsText(outcome.result.diagnostics))
                    return LoadOutcome.Loaded(outcome.result, modified)
                }
                is LibraryReadOutcome.Failed -> logReadFailure(attempt, outcome.error.name)
            }
        }
        log.error(LOG_MODULE, "XML を読めず諦めた attempts=${DesktopValues.LOAD_ATTEMPTS} path=$path")
        return LoadOutcome.GaveUp
    }

    private fun logReadFailure(attempt: Int, reason: String) {
        log.warn(LOG_MODULE, "XML を読めない ${attemptText(attempt)} reason=$reason")
    }

    private fun attemptText(attempt: Int) = "attempt=${attempt + 1}/${DesktopValues.LOAD_ATTEMPTS}"

    private fun diagnosticsText(diagnostics: ReadDiagnostics): String {
        val locationFailures = diagnostics.locationFailures.entries.joinToString(",") { "${it.key.name}:${it.value}" }.ifEmpty { "none" }
        return "XML を読んだ tracks=${diagnostics.trackCount} playlists=${diagnostics.playlistCount} " +
            "dropped_refs=${diagnostics.droppedTrackRefs} duplicate_ids=${diagnostics.duplicatePersistentIds} " +
            "malformed=${diagnostics.malformedEntries} location_failures=$locationFailures"
    }
}
