package playlistsyncfox.desktop.settings

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class LogLevel(val label: String) { Info("INFO"), Warn("WARN"), Error("ERROR") }

private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

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

    fun info(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Info, module, message, errorNumber)

    fun warn(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Warn, module, message, errorNumber)

    fun error(module: String, message: String, errorNumber: Int? = null) = write(LogLevel.Error, module, message, errorNumber)

    @Synchronized
    private fun write(level: LogLevel, module: String, message: String, errorNumber: Int?) {
        val line = listOfNotNull(
            stamp.format(clock.instant()),
            level.label,
            module,
            message.replace('\n', ' ').replace('\t', ' '),
            errorNumber?.let { "E$it" },
        ).joinToString("\t") + "\n"
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

    private fun indexOf(file: Path): Int = file.fileName.toString().removePrefix("psfox-").removeSuffix(".log").toInt()

    private fun logFiles(): List<Path> =
        Files.list(dir).use { s -> s.filter { it.fileName.toString().startsWith("psfox-") && it.toString().endsWith(".log") }.toList() }
            .sortedBy { it.fileName.toString() }
}
