package playlistsyncfox.desktop.ui

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TransferSpeedMeterTest {
    private val start = Instant.parse("2026-11-14T05:00:00Z")
    private val megabyte = 1_048_576L

    private fun at(seconds: Long) = start.plusSeconds(seconds)

    @Test
    fun `1 秒に 1 MB ずつ送ると、現在速度も平均も 1 MB 毎秒になる`() {
        val meter = TransferSpeedMeter()
        (0L..3L).forEach { meter.sample(at(it), it * megabyte, syncing = true) }
        val speed = assertNotNull(meter.sample(at(4), 4 * megabyte, syncing = true))

        assertEquals(megabyte.toDouble(), speed.currentBytesPerSecond)
        assertEquals(megabyte.toDouble(), speed.averageBytesPerSecond)
        assertEquals(4, speed.points.size)
    }

    @Test
    fun `送り終えた後も同期中の間は、平均を送り終えた時点までで割り、現在速度は 0 になる`() {
        val meter = TransferSpeedMeter()
        (0L..4L).forEach { meter.sample(at(it), it * megabyte, syncing = true) }
        // 4 MB を送り終えた後、同期中の表示が続く（V33）
        val speed = assertNotNull(meter.sample(at(30), 4 * megabyte, syncing = true))

        assertEquals(megabyte.toDouble(), speed.averageBytesPerSecond)
        assertEquals(0.0, speed.currentBytesPerSecond)
    }

    @Test
    fun `同期が終わると null を返し、次の同期は 0 から測り直す`() {
        val meter = TransferSpeedMeter()
        meter.sample(at(0), 0, syncing = true)
        meter.sample(at(2), 10 * megabyte, syncing = true)
        assertNull(meter.sample(at(100), 10 * megabyte, syncing = false))

        // 次の同期の最初の記録だけでは、まだ速度を出せない
        val next = assertNotNull(meter.sample(at(200), 10 * megabyte, syncing = true))
        assertEquals(0.0, next.averageBytesPerSecond)
        assertEquals(0, next.points.size)
    }
}
