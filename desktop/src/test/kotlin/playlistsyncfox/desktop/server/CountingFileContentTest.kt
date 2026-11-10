package playlistsyncfox.desktop.server

import io.ktor.http.ContentType
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readByte
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.readByteArray
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CountingFileContentTest {
    private val bytes = ByteArray(8 * 1024 * 1024) { it.toByte() }
    private val file = createTempDirectory("psfox-counting").resolve("track.mp3").also { it.writeBytes(bytes) }.toFile()
    private val activity = ActivityTracker(MutableClock(T0))

    private fun content() = CountingFileContent(file, ContentType.Application.OctetStream, activity.trackSendStarted("track", bytes.size.toLong()))

    // 送り出し側は「書き込み → 送った量を数える」の順のため、読んだ直後は数え終わっていない。条件が満たされるまで待つ
    private suspend fun awaitSending(condition: (SendingTrack) -> Boolean): SendingTrack = withTimeout(5_000) {
        while (activity.sendingTrack()?.let(condition) != true) delay(10)
        checkNotNull(activity.sendingTrack())
    }

    @Test
    fun `TD-83 読み始めると sending に表示名と大きさが入って送った量が増え、読み終えて完了すると無くなる`() = runBlocking {
        activity.trackRequestStarted()
        val channel = content().readFrom()

        channel.readByte()
        val midway = awaitSending { it.sentBytes > 0 }
        assertEquals("track", midway.displayName)
        assertEquals(bytes.size.toLong(), midway.size)

        val rest = channel.readBuffer().readByteArray()
        assertContentEquals(bytes.copyOfRange(1, bytes.size), rest)
        awaitSending { it.sentBytes == bytes.size.toLong() }

        activity.trackRequestFinished()
        assertNull(activity.sendingTrack())
    }

    @Test
    fun `TD-83 範囲の読み出しは範囲の分だけ返して数える`() = runBlocking {
        val channel = content().readFrom(100L..199L)

        assertContentEquals(bytes.copyOfRange(100, 200), channel.readBuffer().readByteArray())
        assertEquals(100L, awaitSending { it.sentBytes >= 100L }.sentBytes)
    }
}
