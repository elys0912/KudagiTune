package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.shared.result.ProgressReport
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * P8 で端末から受けた最後の進み具合（X5-1。D22）。メモリだけで、保存しない。
 * P4 を受けたとき、または最後の P8 から V56 を超えて更新が無いときに消える。
 */
class ProgressBoard(private val clock: Clock) {
    private class Held(val report: ProgressReport, val at: Instant)

    private val held = AtomicReference<Held?>(null)

    fun update(report: ProgressReport) = held.set(Held(report, clock.instant()))

    fun clear() = held.set(null)

    /** V56 ちょうどまでは残る。 */
    fun current(): ProgressReport? =
        held.get()?.takeIf { Duration.between(it.at, clock.instant()) <= DesktopValues.PROGRESS_TTL }?.report
}
