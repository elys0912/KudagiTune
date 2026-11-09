package playlistsyncfox.desktop.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import playlistsyncfox.desktop.settings.AppLog
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.getLastModifiedTime

private fun statOf(path: Path): FileStamp? = try {
    FileStamp(Files.size(path), path.getLastModifiedTime().toMillis())
} catch (_: java.io.IOException) {
    null
}

/** 1 つの XML の場所に対する監視と読み直しの一式（X2-4）。場所が変わったら作り直す。結果は [holder] に流す。 */
class XmlWatch(
    parent: CoroutineScope,
    private val path: Path,
    holder: LibraryHolder,
    opener: DirectoryEventsOpener,
    log: AppLog,
) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private val refresher = LibraryRefresher(
        scope,
        LibraryLoader(path, log),
        StabilityWaiter({ statOf(path) }),
        holder,
        { statOf(path)?.let { Instant.ofEpochMilli(it.modifiedAtMillis) } },
    )
    private val watcher = LibraryWatcher(scope, path, opener, refresher::notifyChanged, refresher::checkModified)

    /** 起動のとき 1 回読み、監視を始める。 */
    fun start() {
        refresher.start()
        watcher.start()
    }

    /** P1・P3 の要求の時。 */
    suspend fun refreshForRequest() = refresher.refreshForRequest()

    fun stop() = scope.cancel()
}
