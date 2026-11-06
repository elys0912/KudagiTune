package playlistsyncfox.desktop.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import playlistsyncfox.itunes.library.LibraryReadResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

class HeldLibrary(val result: LibraryReadResult, val xmlModified: Instant)

/** XML の状態の 4 つ（X2-4）と、まだ読んでいない状態。 */
sealed interface XmlStatus {
    data object Unchecked : XmlStatus

    data object Normal : XmlStatus

    data class Stale(val days: Long) : XmlStatus

    /** 諦めた。[hasResult] は、最後に読めた結果を残しているか。 */
    data class Unreadable(val hasResult: Boolean) : XmlStatus
}

/** 最後に読めた結果と状態を持つ。読み取りの中は古い結果を触らない（途中の状態を見せない）。 */
class LibraryHolder(private val clock: Clock, private val staleDays: () -> Int) {
    private val heldFlow = MutableStateFlow<HeldLibrary?>(null)
    private val statusFlow = MutableStateFlow<XmlStatus>(XmlStatus.Unchecked)

    val held: StateFlow<HeldLibrary?> = heldFlow
    val status: StateFlow<XmlStatus> = statusFlow

    fun apply(outcome: LoadOutcome) {
        when (outcome) {
            is LoadOutcome.Loaded -> {
                val loaded = HeldLibrary(outcome.result, outcome.xmlModified)
                heldFlow.value = loaded
                statusFlow.value = judge(loaded)
            }
            LoadOutcome.GaveUp -> statusFlow.value = XmlStatus.Unreadable(hasResult = heldFlow.value != null)
        }
    }

    /** 古さの日数の設定が変わったときに、読めている状態だけ判定し直す。 */
    fun recomputeStale() {
        val held = heldFlow.value ?: return
        if (statusFlow.value is XmlStatus.Normal || statusFlow.value is XmlStatus.Stale) statusFlow.value = judge(held)
    }

    // V5 ちょうどは正常。超えたら古い。0 は警告しない
    private fun judge(held: HeldLibrary): XmlStatus {
        val age = Duration.between(held.xmlModified, clock.instant())
        val limit = staleDays()
        return if (limit > 0 && age > Duration.ofDays(limit.toLong())) XmlStatus.Stale(age.toDays()) else XmlStatus.Normal
    }
}
