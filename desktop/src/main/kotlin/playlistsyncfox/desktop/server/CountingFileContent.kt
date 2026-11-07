package playlistsyncfox.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.content.OutgoingContent
import io.ktor.util.cio.readChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * `LocalFileContent` と同じくファイルをそのまま返し、読んで応答へ渡した量を [onSent] に知らせる（X5-1。D22）。
 * `ReadChannelContent` なので `PartialContent` の Range（206）もそのまま効く。数えるのは渡した量で、送信の緩衝の分だけ先に進む。
 */
class CountingFileContent(
    private val file: File,
    override val contentType: ContentType,
    private val onSent: (Int) -> Unit,
) : OutgoingContent.ReadChannelContent() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val contentLength: Long get() = file.length()

    override fun readFrom(): ByteReadChannel = counted(file.readChannel())

    override fun readFrom(range: LongRange): ByteReadChannel = counted(file.readChannel(range.first, range.last))

    private fun counted(source: ByteReadChannel): ByteReadChannel = scope.writer(autoFlush = true) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (true) {
                val read = source.readAvailable(buffer, 0, buffer.size)
                if (read < 0) break
                channel.writeFully(buffer, 0, read)
                onSent(read)
            }
        } finally {
            source.cancel(null) // 接続が切れても、ファイルを開いたままにしない
        }
    }.channel

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
    }
}
