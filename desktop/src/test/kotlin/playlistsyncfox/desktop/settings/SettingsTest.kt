package playlistsyncfox.desktop.settings

import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsTest {
    private val dir: Path = createTempDirectory("psfox-settings")
    private val file = dir.resolve("settings.json")

    private fun store() = SettingsStore(file) { "11111111-2222-4333-8444-555555555555" }

    private fun fileCount(path: Path) = Files.list(path).use { it.count() }.toInt()

    @Test
    fun `TD-73 設定が無ければ既定値と新しい pc_id で始め、初回の印が付く`() {
        val load = store().load()

        assertTrue(load.firstRun)
        assertEquals("11111111-2222-4333-8444-555555555555", load.settings.pcId)
        assertEquals(DEFAULT_PORT, load.settings.port)
        assertEquals(DEFAULT_STALE_DAYS, load.settings.staleDays)
        assertTrue(Files.exists(file))
    }

    @Test
    fun `TD-24 壊れた JSON は bad へ退避して既定で起動する`() {
        file.writeText("{ これは壊れている")

        val load = store().load()

        assertNotNull(load.rescuedAs)
        assertEquals("{ これは壊れている", dir.resolve("settings.json.bad").readText())
        assertEquals(DEFAULT_PORT, load.settings.port)
        assertFalse(load.firstRun)
    }

    @Test
    fun `TD-24 必須の項目が欠けた JSON も退避する`() {
        file.writeText("""{"schema_version":1}""")

        assertNotNull(store().load().rescuedAs)
    }

    @Test
    fun `TD-26 schema_version が現在と同じなら読める`() {
        val first = store()
        first.load()
        first.update { it.copy(port = 50000) }

        val load = store().load()

        assertNull(load.rescuedAs)
        assertEquals(50000, load.settings.port)
    }

    @Test
    fun `TD-26 schema_version が 1 つ上なら読まずに退避して既定で起動する`() {
        val first = store()
        first.load()
        first.update { it.copy(schemaVersion = SETTINGS_SCHEMA_VERSION + 1, port = 50000) }

        val load = store().load()

        assertNotNull(load.rescuedAs)
        assertEquals(DEFAULT_PORT, load.settings.port)
    }

    @Test
    fun `TD-81 古い設定の selected_playlist_ids は読み捨てて他の項目を保つ`() {
        file.writeText(
            "{\"schema_version\":1,\"selected_playlist_ids\":[\"2000000000000001\"],\"stale_days\":3," +
                "\"launch_on_login\":false,\"port\":50000,\"pc_id\":\"pc\",\"devices\":[]}",
        )

        val load = store().load()

        assertNull(load.rescuedAs)
        assertEquals(50000, load.settings.port)
        assertEquals(3, load.settings.staleDays)
        val reloaded = store().also { it.load() }
        reloaded.update { it }
        assertFalse(file.readText().contains("selected_playlist_ids"))
    }

    @Test
    fun `TD-30 ログはちょうど V8 までは同じファイルで、1 バイト超で新しいファイルへ切り替わる`() {
        val logDir = dir.resolve("logs")
        val clock = MutableClock(T0)
        // 1 行の長さは本文の長さで決まる。空の本文の行の長さを測って、上限をその +10 にする
        AppLog(dir.resolve("probe"), clock).info("m", "")
        val emptyLine = Files.size(Files.list(dir.resolve("probe")).use { it.toList().single() })
        val log = AppLog(logDir, clock, maxBytes = emptyLine + 10, generations = 5)

        log.info("m", "x".repeat(10))
        assertEquals(1, fileCount(logDir))

        log.info("m", "")
        assertEquals(2, fileCount(logDir))
    }

    @Test
    fun `TD-31 世代数を超えたら最古のファイルが消える`() {
        val logDir = dir.resolve("logs")
        val log = AppLog(logDir, MutableClock(T0), maxBytes = 1, generations = 3)

        repeat(3) { log.info("m", "行 $it") }
        assertEquals(3, fileCount(logDir))
        val before = Files.list(logDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }

        log.info("m", "行 3")

        val after = Files.list(logDir).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        assertEquals(3, after.size)
        assertFalse(before.first() in after)
        assertEquals(before.drop(1), after.dropLast(1))
    }

    @Test
    fun `TD-30 連番として読めない名前の psfox ログがあっても、無視して連番の新しいファイルへ切り替わる`() {
        val logDir = dir.resolve("logs")
        Files.createDirectories(logDir)
        val stray = logDir.resolve("psfox-backup.log").also { it.writeText("利用者が置いたファイル") }
        val log = AppLog(logDir, MutableClock(T0), maxBytes = 1, generations = 3)

        log.info("m", "行 0")
        log.info("m", "行 1")

        val numbered = Files.list(logDir).use { s -> s.map { it.fileName.toString() }.filter { it != stray.fileName.toString() }.sorted().toList() }
        assertEquals(listOf("psfox-000001.log", "psfox-000002.log"), numbered)
        assertEquals("利用者が置いたファイル", stray.readText())
    }

    @Test
    fun `TD-30 ログの 1 行は時刻 レベル モジュール メッセージ E の番号をタブで区切る`() {
        val logDir = dir.resolve("logs")

        AppLog(logDir, MutableClock(T0)).warn("server", "署名の検証に失敗", 2)

        val line = Files.list(logDir).use { it.toList().single() }.readText().trimEnd()
        assertEquals("2026-01-01T00:00:00.000Z\tWARN\tserver\t署名の検証に失敗\tE2", line)
    }

    @Test
    fun `TD-84 欄の行は V55 ちょうどまで残り、1 行超えると古い行から消える`() {
        val log = AppLog(dir.resolve("logs"), MutableClock(T0))

        repeat(DesktopValues.LOG_LINES) { log.info("m", "行 $it") }
        assertEquals(DesktopValues.LOG_LINES, log.lines.value.size)
        assertEquals("行 0", log.lines.value.first().message)

        log.info("m", "行 ${DesktopValues.LOG_LINES}")
        assertEquals(DesktopValues.LOG_LINES, log.lines.value.size)
        assertEquals("行 1", log.lines.value.first().message)
        assertEquals("行 ${DesktopValues.LOG_LINES}", log.lines.value.last().message)
    }

    @Test
    fun `TD-84 ファイルへ書けなくても欄には入る`() {
        val blocker = dir.resolve("blocker").also { it.writeText("ファイル") }
        val log = AppLog(blocker.resolve("logs"), MutableClock(T0))

        log.warn("server", "書けない", 2)

        val line = log.lines.value.single()
        assertEquals(LogLine(T0, LogLevel.Warn, "server", "書けない", 2), line)
    }
}
