package playlistsyncfox.desktop.watch

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import playlistsyncfox.desktop.DesktopValues
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
    private var stopped = false

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

    /** 場所の切り替えで世代を捨てるとき。この後は holder に書かない（読み取りの最中だった結果も捨てる）。 */
    fun stop() = synchronized(lock) { stopped = true }

    /** 取りこぼし（OVERFLOW）の後。更新日時を取り直し、保持値と違えば読み直す。 */
    fun checkModified() {
        if (xmlModifiedNow() != holder.held.value?.xmlModified) notifyChanged()
    }

    /**
     * P1・P3・P7 の要求の時。XML が無ければ待たずに「読めない」にして戻る（呼び出し側が E4 で応える。X2-1）。
     * 「読めない」の間は更新日時に関わらず読み直す（X2-3）。それ以外は更新日時が保持値と違えば読み直してから戻る。
     */
    suspend fun refreshForRequest() {
        if (!existsAfterGrace()) {
            applyUnlessStopped(LoadOutcome.GaveUp)
            return
        }

        val cycle = synchronized(lock) {
            when {
                phase != Phase.Idle -> running
                needsReload() -> startCycle(waitForStability = true)
                else -> null
            }
        }
        cycle?.await()
    }

    // 置き換えの数ミリ秒の欠けを吸収するため、無いときだけ 1 回短く待って確かめ直す
    private suspend fun existsAfterGrace(): Boolean {
        if (xmlModifiedNow() != null) return true
        delay(DesktopValues.XML_MISSING_GRACE.toMillis())
        return xmlModifiedNow() != null
    }

    // 停止した世代の結果は古い場所のものなので捨てる。stop と同じ lock の中で書くため、stop の後に書き込みは起きない
    private fun applyUnlessStopped(outcome: LoadOutcome) = synchronized(lock) {
        if (!stopped) holder.apply(outcome)
    }

    private fun needsReload(): Boolean =
        holder.status.value is XmlStatus.Unreadable || xmlModifiedNow() != holder.held.value?.xmlModified

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
        val cycle = scope.launch {
            var wait = waitForStability
            do {
                if (wait) waiter.awaitStable()
                synchronized(lock) {
                    phase = Phase.Loading
                    rereadRequested = false
                }
                applyUnlessStopped(loader.load())
                wait = true
            } while (rereadAfterPass())
        }
        // 停止済みの scope では本体が走らずに終わるため、本体の finally ではなく完了の通知で待ちを解く
        cycle.invokeOnCompletion {
            // 通知より先に次の周が始まっていたら、その周の phase は書き換えない
            synchronized(lock) { if (running === done) phase = Phase.Idle }
            done.complete(Unit)
        }
        return done
    }
}
