package playlistsyncfox.desktop.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.formatIsoMillis
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.Instant

enum class LogLevel(val label: String) { Info("INFO"), Warn("WARN"), Error("ERROR") }

/** 画面のログの欄の 1 行（X5-4）。ファイルへ書く行と同じ内容。 */
data class LogLine(val at: Instant, val level: LogLevel, val module: String, val message: String, val errorNumber: Int?)

private val logFileName = Regex("psfox-([0-9]+)[.]log")

/**
 * PC のログ（X5-4）。1 行 1 件で、時刻、レベル、モジュール、メッセージ、E の番号をタブで区切る。
 * 鍵、コード、署名の値は呼び出し側がメッセージに渡さない。ここは書式と切り替えだけを持つ。
 * 書き込みの失敗は同期を止めず、標準エラーへ 1 行だけ出す。
 *
 * @param maxBytes V8：1 ファイルの大きさ
 * @param generations V8：残すファイルの数
 */
class AppLog(
    private val dir: Path,
    private val clock: Clock,
    private val maxBytes: Long = 5L * 1024 * 1024,
    private val generations: Int = 3,
) {
    private var currentFile: Path? = null
    private val recent = MutableStateFlow<List<LogLine>>(emptyList())

    /** 直近 V55 行（X5-4）。ファイルへ書けなくても入れる。起動の前の行は読み戻さない。 */
    val lines: StateFlow<List<LogLine>> = recent

    fun info(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Info, module, message, errorNumber)

    fun warn(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Warn, module, message, errorNumber)

    fun error(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Error, module, message, errorNumber)

    @Synchronized
    private fun write(level: LogLevel, module: String, message: String, errorNumber: Int?) {
        val at = clock.instant()
        val text = message.replace('\n', ' ').replace('\t', ' ')
        recent.value = (recent.value + LogLine(at, level, module, text, errorNumber)).takeLast(DesktopValues.LOG_LINES)
        val line = listOfNotNull(formatIsoMillis(at), level.label, module, text, errorNumber?.let { "E$it" }).joinToString("\t") + "\n"
        val bytes = line.encodeToByteArray()
        try {
            Files.createDirectories(dir)
            val file = fileFor(bytes.size)
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (e: java.io.IOException) {
            System.err.println("ログを書けない：${e.javaClass.simpleName}")
        }
    }

    // 追記して V8 を超えるなら新しいファイルへ。ちょうど V8 までは同じファイルに入る
    private fun fileFor(incoming: Int): Path {
        val existing = currentFile ?: logFiles().lastOrNull()
        if (existing != null && Files.exists(existing) && Files.size(existing) + incoming <= maxBytes) {
            currentFile = existing
            return existing
        }
        val index = (logFiles().lastOrNull()?.let(::indexOf) ?: 0) + 1
        val next = dir.resolve("psfox-%06d.log".format(index))
        currentFile = next
        logFiles().let { all -> (all + listOf(next)).dropLast(generations).forEach(Files::deleteIfExists) }
        return next
    }

    // 連番として読めない名前（利用者が置いたファイルなど）は null。世代の対象にも、削除の対象にもしない
    private fun indexOf(file: Path): Int? = logFileName.matchEntire(file.fileName.toString())?.groupValues?.get(1)?.toIntOrNull()

    private fun logFiles(): List<Path> =
        Files.list(dir).use { s -> s.filter { indexOf(it) != null }.toList() }
            .sortedBy { it.fileName.toString() }
}
