package playlistsyncfox.desktop.ui

import java.time.Duration
import java.time.Instant

// 同期中の転送速度（ADR 0019）。PC が送ったバイト数の合計を、状態を作り直すたびに時刻つきで受け取る。メモリだけに持つ。

/** 線に出す期間。画面の横軸の幅にも使う。 */
internal val SPEED_LINE_WINDOW: Duration = Duration.ofSeconds(60)

/** 現在速度を均す期間。1 秒ごとの値は曲の切り替わりで揺れるため、数秒で均す。 */
private val CURRENT_SPEED_WINDOW: Duration = Duration.ofSeconds(3)

/** 線の 1 点。[secondsAgo] は今からさかのぼった秒数。 */
class SpeedPoint(val secondsAgo: Double, val bytesPerSecond: Double)

class TransferSpeed(val currentBytesPerSecond: Double, val averageBytesPerSecond: Double, val points: List<SpeedPoint>)

class TransferSpeedMeter {
    private class Sample(val at: Instant, val totalBytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var first: Sample? = null
    private var lastGrowth: Sample? = null

    /**
     * 同期中なら、今までの記録から速度を返す。同期中でなければ記録を捨てて null を返す。
     * 平均は、最初の記録から最後にバイト数が増えた記録までで割る。送り終えた後も同期中の表示が続く間（V33）に、平均が下がっていかないようにするため。
     */
    @Synchronized
    fun sample(now: Instant, totalBytes: Long, syncing: Boolean): TransferSpeed? {
        if (!syncing) {
            samples.clear()
            first = null
            lastGrowth = null
            return null
        }

        val current = Sample(now, totalBytes)
        val start = first ?: current.also { first = it }
        if (totalBytes > (lastGrowth ?: start).totalBytes) lastGrowth = current
        samples.addLast(current)
        while (Duration.between(samples.first().at, now) > SPEED_LINE_WINDOW) samples.removeFirst()

        val points = samples.zipWithNext { from, to ->
            SpeedPoint(Duration.between(to.at, now).toMillis() / 1000.0, rate(from, to))
        }
        val recent = samples.first { Duration.between(it.at, now) <= CURRENT_SPEED_WINDOW }

        return TransferSpeed(rate(recent, current), rate(start, lastGrowth ?: start), points)
    }

    private fun rate(from: Sample, to: Sample): Double {
        val seconds = Duration.between(from.at, to.at).toMillis() / 1000.0
        return if (seconds <= 0) 0.0 else (to.totalBytes - from.totalBytes) / seconds
    }
}
