package playlistsyncfox.desktop.watch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue

private const val XML_NAME = "iTunes Music Library.xml"

/**
 * TD-43：実ファイルの WatchService。ubuntu の実装は Windows と違い、通過しても Windows での保証にならない。
 * 既定の実行には含めず、手元の Windows で `-PwindowsWatch` を付けて回す（C-13）。
 */
@Tag("windows-watch")
class WindowsWatchTest {
    private val dir: Path = createTempDirectory("psfox-realwatch")

    /** [write] の後に届く XML の名前の通知と、XML 以外の名前の通知を集める。 */
    private fun observe(write: (Path) -> Unit): Pair<List<String>, List<String>> = runBlocking(Dispatchers.IO) {
        val events = WatchServiceOpener().open(dir) ?: error("登録できない")
        events.use {
            write(dir)
            val names = mutableListOf<String>()
            withTimeoutOrNull(3_000) {
                while (true) names += (it.next() ?: break).filterIsInstance<DirEvent.Touched>().map { e -> e.fileName }
            }
            names.partition { it.equals(XML_NAME, ignoreCase = true) }
        }
    }

    @Test
    fun `TD-43 XML の新規作成で XML の名前の通知が 1 回以上届く`() {
        val (xml, _) = observe { it.resolve(XML_NAME).writeText("a") }

        assertTrue(xml.isNotEmpty())
    }

    @Test
    fun `TD-43 既存の XML への追記で XML の名前の通知が 1 回以上届く`() {
        dir.resolve(XML_NAME).writeText("a")

        val (xml, _) = observe { Files.writeString(it.resolve(XML_NAME), "b", java.nio.file.StandardOpenOption.APPEND) }

        assertTrue(xml.isNotEmpty())
    }

    @Test
    fun `TD-43 一時ファイルへ書いて改名で入れ替えても XML の名前の通知が届く`() {
        dir.resolve(XML_NAME).writeText("old")

        val (xml, others) = observe {
            repeat(3) { n ->
                val temp = it.resolve("~tmp-$n.xml")
                temp.writeText("new $n")
                Files.move(temp, it.resolve(XML_NAME), StandardCopyOption.REPLACE_EXISTING)
            }
        }

        assertTrue(xml.isNotEmpty())
        // 一時ファイルの名前の通知は、監視の側ではなく LibraryWatcher の名前の絞り込みで落ちる
        assertTrue(others.all { it.startsWith("~tmp-") })
    }
}
