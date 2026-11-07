package playlistsyncfox.android.log

import playlistsyncfox.android.sync.SyncLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors

enum class LogLevel(val text: String) { Info("情報"), Warn("警告"), Error("エラー") }

/** 画面のログの欄に流す 1 行（X7-5-1）。ファイルへ書く行と同じ、伏せた後の内容。[at] は UTC のエポックミリ秒。 */
data class LogEntry(val at: Long, val level: LogLevel, val module: String, val message: String, val errorNumber: Int?)

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
    private val screenLines = MutableStateFlow<List<LogEntry>>(emptyList())

    /** 画面の欄（D21）：直近 V55 行。新しい行が後ろ。起動の前の行はファイルから読み戻さない。 */
    val recent: StateFlow<List<LogEntry>> = screenLines

    fun log(level: LogLevel, module: String, message: String, errorNumber: Int? = null) {
        if (!enabled()) return
        val at = now()
        val text = clean(message)
        val line = listOf(Instant.ofEpochMilli(at).toString(), level.text, module, text, errorNumber?.let { "E$it" }.orEmpty())
            .joinToString("\t")
        // ファイルの書き込みに失敗しても、画面の欄には入れる
        screenLines.update { (it + LogEntry(at, level, module, text, errorNumber)).takeLast(SCREEN_LINES) }
        executor.execute { runCatching { append(line) } }
    }

    /** 同期の診断のログ（`SyncLogger`）として使う。 */
    fun asSyncLogger() = object : SyncLogger {
        override fun line(text: String) = log(LogLevel.Info, "sync", text)

        override fun failure(text: String, errorNumber: Int) = log(LogLevel.Warn, "sync", text, errorNumber)
    }

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

        /** V55 画面のログの欄がメモリに持つ行数。 */
        const val SCREEN_LINES = 200
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
