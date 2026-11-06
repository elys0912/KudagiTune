package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.DesktopValues
import java.time.Clock
import java.time.Instant

/**
 * 署名の失敗と P5 の連続を接続元の IP ごとに数える（X5-1）。合わせて V43 に達したら V32 の間、429 を返す。
 * 数はメモリだけ。P6 の不一致は数えない（コードの試行の側で扱う）。
 *
 * 「P5 の連続」は、間に P6 を挟まない P5 の数と読む。P5 の後に P6 が来る正規の流れ（不一致で P5 からやり直す場合を含む）は、
 * 繰り返しても数が積み上がらない。
 */
class SignatureFailureCounter(private val clock: Clock) {
    private class Counts(var signatureFailures: Int = 0, var pairingStarts: Int = 0)

    private val counts = HashMap<String, Counts>()
    private val blockedUntil = HashMap<String, Instant>()

    @Synchronized
    fun isBlocked(address: String): Boolean {
        val until = blockedUntil[address] ?: return false
        if (clock.instant() < until) return true
        blockedUntil.remove(address)
        return false
    }

    /** V43 回目の失敗の応答は通常どおり返り、次の要求から 429 になる。 */
    @Synchronized
    fun recordFailure(address: String) = add(address) { it.signatureFailures++ }

    @Synchronized
    fun recordPairingStart(address: String) = add(address) { it.pairingStarts++ }

    /** P6 は数えない。P5 の連続を途切れさせる。 */
    @Synchronized
    fun recordPairingConfirm(address: String) {
        counts[address]?.pairingStarts = 0
    }

    @Synchronized
    fun recordSuccess(address: String) {
        counts[address]?.signatureFailures = 0
    }

    private fun add(address: String, increment: (Counts) -> Unit) {
        val current = counts.getOrPut(address) { Counts() }
        increment(current)
        if (current.signatureFailures + current.pairingStarts >= DesktopValues.SIGNATURE_FAILURE_LIMIT) {
            counts.remove(address)
            blockedUntil[address] = clock.instant().plus(DesktopValues.SIGNATURE_BLOCK)
        }
    }
}
