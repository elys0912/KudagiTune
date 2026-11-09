package playlistsyncfox.desktop.watch

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import playlistsyncfox.desktop.DesktopValues
import java.io.IOException
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

sealed interface DirEvent {
    data class Touched(val fileName: String) : DirEvent

    /** 取りこぼし。 */
    data object Overflow : DirEvent
}

/** フォルダの監視の登録。差し替えられるよう WatchService を包む。 */
interface DirectoryEvents : AutoCloseable {
    /** 次の通知。登録が無効になったら null。 */
    suspend fun next(): List<DirEvent>?
}

fun interface DirectoryEventsOpener {
    /** 登録できなければ null（フォルダが無い）。 */
    fun open(dir: Path): DirectoryEvents?
}

/** 作成と変更の 2 種で登録する（ENTRY_DELETE は登録しない）。実環境の確認は C-13。 */
class WatchServiceOpener : DirectoryEventsOpener {
    override fun open(dir: Path): DirectoryEvents? {
        if (!Files.isDirectory(dir)) return null
        val service = FileSystems.getDefault().newWatchService()
        return try {
            WatchServiceEvents(service, dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY))
        } catch (_: IOException) {
            service.close()
            null
        }
    }
}

private class WatchServiceEvents(private val service: WatchService, private val key: WatchKey) : DirectoryEvents {
    private var invalid = false

    override suspend fun next(): List<DirEvent>? {
        if (invalid) return null
        val signalled = try {
            runInterruptible(Dispatchers.IO) { service.take() }
        } catch (_: ClosedWatchServiceException) {
            return null
        }
        val events = signalled.pollEvents().map { event ->
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) DirEvent.Overflow else DirEvent.Touched(event.context().toString())
        }
        invalid = !key.reset()
        return events
    }

    override fun close() = service.close()
}

/**
 * XML のフォルダを見て、XML の名前の通知だけを [onChanged] へ渡す（X2-4）。
 * 名前は大文字小文字を区別しない（Windows）。取りこぼしは [onOverflow]。
 * 登録が無効になったら V18 の間隔でフォルダを確かめ、戻ったら登録し直して 1 回読む。
 */
class LibraryWatcher(
    private val scope: CoroutineScope,
    private val xmlPath: Path,
    private val opener: DirectoryEventsOpener,
    private val onChanged: () -> Unit,
    private val onOverflow: () -> Unit,
) {
    fun start(): Job = scope.launch {
        // 相対パス（W5 の入力）は親が無い。現在のフォルダを基にした絶対パスにして、フォルダを求める
        val dir = xmlPath.toAbsolutePath().parent
        val xmlName = xmlPath.fileName.toString()
        var lost = false
        while (isActive) {
            val events = opener.open(dir)
            if (events != null) {
                if (lost) onChanged()
                events.use {
                    while (true) {
                        val batch = it.next() ?: break
                        for (event in batch) {
                            when (event) {
                                is DirEvent.Touched -> if (event.fileName.equals(xmlName, ignoreCase = true)) onChanged()
                                DirEvent.Overflow -> onOverflow()
                            }
                        }
                    }
                }
            }
            lost = true
            delay(DesktopValues.XML_STABLE_WAIT.toMillis())
        }
    }
}
