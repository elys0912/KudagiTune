package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.DesktopValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** P2 の送信中の曲（X5-1。D22）。表示名は実ファイル名から拡張子を除いたもの。 */
data class SendingTrack(val displayName: String, val sentBytes: Long, val size: Long)

/**
 * 直近の署名つき要求と P2 の要求の時刻、P2 の送信中の数（メモリだけ）。W3 の接続中と W1 の同期中の判定に使う。
 * 保存はしない（`last_seen_at` を持たない。X5-4）。
 */
class ActivityTracker(private val clock: Clock) {
    @Volatile
    private var lastSigned: Instant? = null

    @Volatile
    private var lastTrackRequest: Instant? = null
    private val sending = AtomicInteger(0)
    private val sendingTrack = AtomicReference<Sending?>(null)
    private val totalSent = AtomicLong(0)

    private class Sending(val displayName: String, val size: Long) {
        val sent = AtomicLong(0)
    }

    fun signedRequest() {
        lastSigned = clock.instant()
    }

    fun trackRequestStarted() {
        lastTrackRequest = clock.instant()
        sending.incrementAndGet()
    }

    fun trackRequestFinished() {
        if (sending.decrementAndGet() == 0) sendingTrack.set(null)
    }

    /** P4 を受けた。端末の同期は終わったので、V33 を待たずに同期中の判定を終える（ADR 0022）。送信中の P2 は終わるまで同期中のまま。 */
    fun syncReported() {
        lastTrackRequest = null
    }

    /** P2 で送り始めた曲を登録する。戻りの関数に、応答へ渡したバイト数を足していく（X5-1。D22）。 */
    fun trackSendStarted(displayName: String, size: Long): (Int) -> Unit {
        val track = Sending(displayName, size)
        sendingTrack.set(track)
        return {
            track.sent.addAndGet(it.toLong())
            totalSent.addAndGet(it.toLong())
        }
    }

    /** 起動してから P2 で送ったバイト数の合計。転送速度の計測に使う（ADR 0019）。 */
    val totalSentBytes: Long get() = totalSent.get()

    /** P2 の送信中の曲。無ければ null。 */
    fun sendingTrack(): SendingTrack? = sendingTrack.get()?.let { SendingTrack(it.displayName, it.sent.get(), it.size) }

    /** P2 の送信中の数。終了時に待つ対象にもなる。 */
    val sendingCount: Int get() = sending.get()

    /** V33 ちょうどまでは接続中。 */
    fun isConnected(now: Instant): Boolean = within(lastSigned, now)

    /** P2 の送信中、または P2 の最後の要求から V33 以内（P4 を受けた後は除く）。P1 だけでは同期中にしない。 */
    fun isSyncing(now: Instant): Boolean = sending.get() > 0 || within(lastTrackRequest, now)

    private fun within(at: Instant?, now: Instant) = at != null && Duration.between(at, now) <= DesktopValues.ACTIVITY_WINDOW
}
