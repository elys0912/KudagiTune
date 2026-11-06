package playlistsyncfox.desktop.server

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.shared.auth.AuthValues
import playlistsyncfox.shared.auth.PairingOutcome
import playlistsyncfox.shared.auth.PairingState
import playlistsyncfox.shared.auth.handlePairingConfirm
import playlistsyncfox.shared.auth.handlePairingStart
import playlistsyncfox.shared.auth.openPairing
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint
import java.time.Clock
import java.time.Instant

/** W4 に写すペアリングの状態。コードは開いている間だけ持つ。 */
sealed interface PairingView {
    data object Closed : PairingView

    /** V4 の待ち。[until] の後にコードを作り直せる。 */
    data class Locked(val until: Instant) : PairingView

    data class Open(val code: String, val expiresAt: Instant, val failedAttempts: Int) : PairingView {
        override fun toString() = "PairingView.Open"
    }
}

/**
 * ペアリングの状態機械の持ち主（X4-2。M3 が持つ）。判断は shared の関数に任せ、状態の保持、直列化、保存の結線だけを持つ。
 * 状態はメモリだけ。再起動で閉に戻る。
 */
class PairingService(
    private val crypto: CryptoProvider,
    private val clock: Clock,
    private val pcId: String,
    private val spkiHash: ByteArray,
    private val settings: SettingsStore,
    private val registry: DeviceRegistry,
    private val onViewChanged: () -> Unit = {},
) {
    private var state: PairingState = PairingState.Closed()
    private var deviceName: String? = null
    private val viewFlow = MutableStateFlow<PairingView>(PairingView.Closed)

    val view: StateFlow<PairingView> = viewFlow

    private fun now() = clock.instant().epochSecond

    @Synchronized
    fun open(): PairingOutcome<String> {
        val step = openPairing(crypto, state, now())
        state = step.state
        publish()
        return step.outcome
    }

    /** 画面の「閉じる」、窓を閉じる、アプリの終了。V4 の待ちは残す。 */
    @Synchronized
    fun close() {
        if (state !is PairingState.Closed) state = PairingState.Closed()
        publish()
    }

    /** 期限（V3）の経過を反映する。画面の時計から呼ぶ。 */
    @Synchronized
    fun refresh() = publish()

    @Synchronized
    fun start(deviceId: String, name: String, x: ByteArray): PairingOutcome<EcPoint> {
        val step = handlePairingStart(
            crypto, state, deviceId, x,
            registeredDeviceCount = settings.current.devices.size,
            pcId = pcId,
            spkiHash = spkiHash,
            now = now(),
        )
        state = step.state
        if (step.outcome is PairingOutcome.Ok) deviceName = name
        publish()
        return step.outcome
    }

    @Synchronized
    fun confirm(deviceId: String, macD: ByteArray): PairingOutcome<ByteArray> {
        val step = handlePairingConfirm(crypto, state, deviceId, macD, now()) { id, key ->
            registry.register(id, deviceName.orEmpty(), key)
        }
        state = step.state
        publish()
        return step.outcome
    }

    // 共有の状態から画面の状態を作る。H2 のコードと失敗回数は共有側の内部なので、直前の開の値を残す
    private fun publish() {
        val current = state
        val previous = viewFlow.value
        val next = when (current) {
            is PairingState.Open -> PairingView.Open(
                current.code,
                Instant.ofEpochSecond(current.openedAt + AuthValues.CODE_VALID_SECONDS),
                current.failedAttempts,
            )
            is PairingState.Started -> previous
            is PairingState.Closed -> current.lockedUntil?.takeIf { now() < it }?.let { PairingView.Locked(Instant.ofEpochSecond(it)) }
                ?: PairingView.Closed
        }
        val expired = next is PairingView.Open && !clock.instant().isBefore(next.expiresAt)
        if (expired) state = PairingState.Closed()
        val shown = if (expired) PairingView.Closed else next
        if (shown != previous) {
            viewFlow.value = shown
            onViewChanged()
        }
    }
}
