package playlistsyncfox.android.log

import playlistsyncfox.android.sync.SyncLogger
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class LogLevel(val text: String) { Info("情報"), Warn("警告"), Error("エラー") }

/**
 * 端末のログ（X7-5）。1 行 1 件、項目はタブ区切りで 日時（UTC）、レベル、モジュール、メッセージ、E の番号。
 * 書き込みは専用の 1 スレッドで行い、失敗しても呼び出し側には伝えない。
 */
class AppLog(
    private val dir: File,
    private val enabled: () -> Boolean,
    private val now: () -> Long,
    private val maxBytes: Long = MAX_FILE_BYTES,
    private val executor: Executor = Executors.newSingleThreadExecutor(),
) {
    fun log(level: LogLevel, module: String, message: String, errorNumber: Int? = null) {
        if (!enabled()) return
        val line = listOf(Instant.ofEpochMilli(now()).toString(), level.text, module, clean(message), errorNumber?.let { "E$it" }.orEmpty())
            .joinToString("\t")
        executor.execute { runCatching { append(line) } }
    }

    /** 同期の診断のログ（`SyncLogger`）として使う。 */
    fun asSyncLogger() = SyncLogger { log(LogLevel.Info, "sync", it) }

    /** 古い世代から新しい世代の順で、末尾の [maxLines] 行まで（閲覧と共有で共通。V51）。 */
    fun tail(maxLines: Int = MAX_VIEW_LINES): List<String> {
        val collected = ArrayDeque<String>()
        for (file in generationFiles()) {
            if (!file.exists()) continue
            collected.addAll(0, file.readLines())
            if (collected.size >= maxLines) break
        }
        return collected.takeLast(maxLines)
    }

    fun sizeBytes(): Long = generationFiles().filter { it.exists() }.sumOf { it.length() }

    /** 全世代を消し、消した旨を新しい世代に書く。 */
    fun clear() {
        executor.execute {
            runCatching { generationFiles().forEach { it.delete() } }
            log(LogLevel.Info, "log", "ログを消去した")
        }
    }

    /** 新しい世代が先。 */
    private fun generationFiles(): List<File> = listOf(File(dir, NAME)) + (1 until GENERATIONS).map { File(dir, "$NAME.$it") }

    private fun append(line: String) {
        dir.mkdirs()
        val current = File(dir, NAME)
        val bytes = line.toByteArray().size + 1
        if (current.exists() && current.length() + bytes > maxBytes) rotate()
        current.appendText(line + "\n")
    }

    /** 世代を 1 つずつ送り、最古を消す。 */
    private fun rotate() {
        val files = generationFiles()
        files.last().delete()
        for (index in files.lastIndex - 1 downTo 0) {
            if (files[index].exists()) files[index].renameTo(files[index + 1])
        }
    }

    companion object {
        const val NAME = "app.log"

        /** V9 1 ファイル 2 MB。 */
        const val MAX_FILE_BYTES = 2L * 1024 * 1024

        /** V9 3 世代。 */
        const val GENERATIONS = 3

        /** V51 閲覧と共有で読む行数の上限。 */
        const val MAX_VIEW_LINES = 500
    }
}

// 鍵、ペアリングコード、曲のパス、署名の値、MAC、IP は書かない（N1-6）。呼び出し側が誤って渡しても行に出さない
private val SECRET_PATTERNS = listOf(
    Regex("""(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}""") to "<MAC>",
    Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""") to "<IP>",
    Regex("""\S*/\S*""") to "<パス>",
    Regex("""[A-Za-z0-9+_=-]{32,}""") to "<秘>",
    Regex("""\b\d{6}\b""") to "<コード>",
)

private fun clean(message: String): String =
    SECRET_PATTERNS.fold(message.replace(Regex("""[\t\r\n]+"""), " ")) { text, (pattern, mask) -> pattern.replace(text, mask) }
