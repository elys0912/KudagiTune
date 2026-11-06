package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.DesktopValues
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

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

    fun signedRequest() {
        lastSigned = clock.instant()
    }

    fun trackRequestStarted() {
        lastTrackRequest = clock.instant()
        sending.incrementAndGet()
    }

    fun trackRequestFinished() {
        sending.decrementAndGet()
    }

    /** P2 の送信中の数。終了時に待つ対象にもなる。 */
    val sendingCount: Int get() = sending.get()

    /** V33 ちょうどまでは接続中。 */
    fun isConnected(now: Instant): Boolean = within(lastSigned, now)

    /** P2 の送信中、または P2 の最後の要求から V33 以内。P1 だけでは同期中にしない。 */
    fun isSyncing(now: Instant): Boolean = sending.get() > 0 || within(lastTrackRequest, now)

    private fun within(at: Instant?, now: Instant) = at != null && Duration.between(at, now) <= DesktopValues.ACTIVITY_WINDOW
}
