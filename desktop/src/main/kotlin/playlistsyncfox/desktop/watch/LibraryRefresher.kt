package playlistsyncfox.desktop.watch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 読み直しを 1 本に直列にする（X2-4 の 2〜4、7、8）。
 * 通知が重複しても、待ちの中のものは 1 つに畳む。読み取りの最中の通知は、読み終えた後にもう 1 回読む。
 * 要求の時は、進行中の読み直しがあれば終わるのを待って同じ結果を使う。
 */
class LibraryRefresher(
    private val scope: CoroutineScope,
    private val loader: LibraryLoader,
    private val waiter: StabilityWaiter,
    private val holder: LibraryHolder,
    private val xmlModifiedNow: () -> Instant?,
) {
    private enum class Phase { Idle, Waiting, Loading }

    private val lock = Any()
    private var phase = Phase.Idle
    private var rereadRequested = false
    private var running: CompletableDeferred<Unit>? = null

    /** 起動のとき。安定待ちは省く。 */
    fun start() {
        synchronized(lock) { if (phase == Phase.Idle) startCycle(waitForStability = false) }
    }

    /** 監視からの通知。 */
    fun notifyChanged() {
        synchronized(lock) {
            when (phase) {
                Phase.Idle -> startCycle(waitForStability = true)
                Phase.Waiting -> Unit
                Phase.Loading -> rereadRequested = true
            }
        }
    }

    /** 取りこぼし（OVERFLOW）の後。更新日時を取り直し、保持値と違えば読み直す。 */
    fun checkModified() {
        if (xmlModifiedNow() != holder.held.value?.xmlModified) notifyChanged()
    }

    /** P1・P3 の要求の時。更新日時が保持値と違えば、読み直してから戻る。 */
    suspend fun refreshForRequest() {
        val cycle = synchronized(lock) {
            when {
                phase != Phase.Idle -> running
                xmlModifiedNow() != holder.held.value?.xmlModified -> startCycle(waitForStability = true)
                else -> null
            }
        }
        cycle?.await()
    }

    // 読み取りの最中に通知があれば、安定待ちに戻ってもう 1 回読む
    private fun rereadAfterPass(): Boolean = synchronized(lock) {
        phase = if (rereadRequested) Phase.Waiting else Phase.Idle
        rereadRequested
    }

    // lock の中で呼ぶ
    private fun startCycle(waitForStability: Boolean): CompletableDeferred<Unit> {
        val done = CompletableDeferred<Unit>()
        running = done
        phase = if (waitForStability) Phase.Waiting else Phase.Loading
        rereadRequested = false
        scope.launch {
            try {
                var wait = waitForStability
                do {
                    if (wait) waiter.awaitStable()
                    synchronized(lock) {
                        phase = Phase.Loading
                        rereadRequested = false
                    }
                    holder.apply(loader.load())
                    wait = true
                } while (rereadAfterPass())
            } finally {
                synchronized(lock) { phase = Phase.Idle }
                done.complete(Unit)
            }
        }
        return done
    }
}
