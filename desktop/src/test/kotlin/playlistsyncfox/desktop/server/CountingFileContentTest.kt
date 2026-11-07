package playlistsyncfox.desktop.server

import io.ktor.http.ContentType
import io.ktor.utils.io.readByte
import io.ktor.utils.io.readRemaining
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
import kotlin.test.assertTrue

class CountingFileContentTest {
    private val bytes = ByteArray(8 * 1024 * 1024) { it.toByte() }
    private val file = createTempDirectory("psfox-counting").resolve("track.mp3").also { it.writeBytes(bytes) }.toFile()
    private val activity = ActivityTracker(MutableClock(T0))

    private fun content() = CountingFileContent(file, ContentType.Application.OctetStream, activity.trackSendStarted("track", bytes.size.toLong()))

    @Test
    fun `TD-83 読み始めると sending に表示名と大きさが入って送った量が増え、読み終えて完了すると無くなる`() = runBlocking {
        activity.trackRequestStarted()
        val channel = content().readFrom()

        channel.readByte()
        val midway = activity.sendingTrack()
        assertEquals("track", midway?.displayName)
        assertEquals(bytes.size.toLong(), midway?.size)
        assertTrue(midway!!.sentBytes > 0)

        val rest = channel.readRemaining().readByteArray()
        assertContentEquals(bytes.copyOfRange(1, bytes.size), rest)
        assertEquals(bytes.size.toLong(), activity.sendingTrack()?.sentBytes)

        activity.trackRequestFinished()
        assertNull(activity.sendingTrack())
    }

    @Test
    fun `TD-83 範囲の読み出しは範囲の分だけ返して数える`() = runBlocking {
        val channel = content().readFrom(100L..199L)

        assertContentEquals(bytes.copyOfRange(100, 200), channel.readRemaining().readByteArray())
        withTimeout(5_000) { while (activity.sendingTrack()?.sentBytes != 100L) delay(10) }
    }
}
