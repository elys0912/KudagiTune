package playlistsyncfox.android.log

import java.io.File
import java.util.concurrent.Executor
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val direct = Executor { it.run() }

private fun tempDir(): File = createTempDirectory("applog").toFile().also { it.deleteOnExit() }

class AppLogTest {
    private fun log(dir: File, maxBytes: Long = AppLog.MAX_FILE_BYTES) =
        AppLog(dir, now = { 0L }, maxBytes = maxBytes, executor = direct)

    @Test
    fun `TA-115 上限を超えて書くと世代が送られ、3 世代を超えて残らない`() {
        val dir = tempDir()
        val log = log(dir, maxBytes = 200)

        repeat(40) { log.log(LogLevel.Info, "sync", "メッセージ $it") }

        assertEquals(setOf("app.log", "app.log.1", "app.log.2"), dir.list()!!.toSet())
        assertTrue(dir.listFiles()!!.all { it.length() <= 200 })
        // 最新の行は残り、最古の行は消えている
        assertTrue(log.tail().last().contains("メッセージ 39"))
        assertFalse(log.tail().any { it.contains("メッセージ 0\t") || it.endsWith("メッセージ 0") })
    }

    @Test
    fun `TA-116 鍵、コード、パス、IP、MAC を含む入力でも行にそれらが出ない`() {
        val dir = tempDir()
        val key = "a1b2c3d4".repeat(8)
        log(dir).log(
            LogLevel.Warn,
            "pairing",
            "鍵 $key コード 123456 パス Music/playlist-sync-fox/a/b.mp3 接続先 192.0.2.10 機器 aa:bb:cc:dd:ee:ff 曲 Song 1",
            errorNumber = 2,
        )

        val line = File(dir, "app.log").readText()

        listOf(key, "123456", "Music/playlist-sync-fox", "192.0.2.10", "aa:bb:cc:dd:ee:ff").forEach { assertFalse(line.contains(it), it) }
        // 曲名まではメッセージに出してよい。項目はタブ区切りで E の番号が最後に付く
        assertTrue(line.contains("Song 1"))
        val fields = line.trimEnd().split('\t')
        assertEquals(listOf("警告", "pairing"), fields.subList(1, 3))
        assertEquals("E2", fields[4])
    }

    @Test
    fun `TA-117 書き込みが失敗しても呼び出し側は例外を受けない`() {
        val blocker = tempDir().resolve("not-a-dir").also { it.writeText("x") }

        // ログの置き場が通常のファイルで、作れない
        log(blocker).log(LogLevel.Error, "sync", "失敗しても止まらない")
    }

    @Test
    fun `TA-118 閲覧は末尾から 500 行までで、501 行のログは 500 行だけ読む`() {
        val dir = tempDir()
        val log = log(dir)

        repeat(500) { log.log(LogLevel.Info, "m", "行 $it") }
        assertEquals(500, log.tail().size)
        log.log(LogLevel.Info, "m", "行 500")

        val lines = log.tail()
        assertEquals(500, lines.size)
        assertTrue(lines.first().contains("行 1\t") || lines.first().endsWith("行 1"))
        assertTrue(lines.last().contains("行 500"))
    }

    @Test
    fun `TA-128 世代をまたぐログは新しい世代から末尾の 500 行に切る`() {
        val dir = tempDir()
        val log = log(dir, maxBytes = 12_000)

        repeat(1_200) { log.log(LogLevel.Info, "m", "行 %04d".format(it)) }

        val lines = log.tail()
        assertEquals(setOf("app.log", "app.log.1", "app.log.2"), dir.list()!!.toSet())
        assertTrue(File(dir, "app.log").readLines().size < 500, "現行の世代だけでは足りない")
        assertEquals(500, lines.size)
        assertTrue(lines.last().contains("行 1199"))
        assertTrue(lines.first().contains("行 0700"))
    }

    @Test
    fun `TA-136 画面の欄は直近 200 行までで 1 行超えると古い行から消える`() {
        val log = log(tempDir())

        repeat(AppLog.SCREEN_LINES) { log.log(LogLevel.Info, "m", "行 $it") }
        assertEquals(AppLog.SCREEN_LINES, log.recent.value.size)
        assertEquals("行 0", log.recent.value.first().message)
        log.log(LogLevel.Info, "m", "行 ${AppLog.SCREEN_LINES}")

        assertEquals(AppLog.SCREEN_LINES, log.recent.value.size)
        assertEquals("行 1", log.recent.value.first().message)
        assertEquals("行 ${AppLog.SCREEN_LINES}", log.recent.value.last().message)
    }

    @Test
    fun `TA-136 パスは伏せた後の行が画面の欄に入り ファイルに書けなくても入る`() {
        // 置き場にしたい場所が普通のファイルなので、ファイルへの書き込みは失敗する
        val blocker = File(tempDir(), "blocked").also { it.writeText("x") }
        val log = log(File(blocker, "logs"))

        log.log(LogLevel.Warn, "sync", "保存先 Music/playlist-sync-fox/a/b.mp3 の曲 Song 1", errorNumber = 9)

        val entry = log.recent.value.single()
        assertFalse(entry.message.contains("Music/playlist-sync-fox"))
        assertTrue(entry.message.contains("Song 1"))
        assertEquals(9, entry.errorNumber)
        assertEquals(LogLevel.Warn, entry.level)
        assertFalse(File(blocker, "logs").exists())
    }
}
