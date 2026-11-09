package playlistsyncfox.desktop.ui

import playlistsyncfox.desktop.server.ActivityTracker
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.server.ProgressBoard
import playlistsyncfox.desktop.server.SendingTrack
import playlistsyncfox.desktop.settings.LastSync
import playlistsyncfox.desktop.settings.Settings
import playlistsyncfox.desktop.watch.XmlStatus
import playlistsyncfox.shared.result.ProgressReport
import java.time.Duration
import java.time.Instant

// 画面の状態は 1 つの「PC の状態」にまとめ、画面はそれを写すだけにする（X5-5）。

enum class ServerFailureKind { KeyStorage, PortInUse, Other }

sealed interface ServerStatus {
    data object NotStarted : ServerStatus

    data object Running : ServerStatus

    data class Failed(val kind: ServerFailureKind) : ServerStatus
}

enum class Warning { XmlStale, PortInUse, ServerStartFailed, KeyStorageFailed, KeyMissing, CertificateRegenerated, SettingsReset }

class DeviceState(
    val deviceId: String,
    val displayName: String,
    val connected: Boolean,
    val keyMissing: Boolean,
    val lastSync: LastSync?,
)

class PcState(
    val server: ServerStatus,
    val xml: XmlStatus,
    val device: DeviceState?,
    val pairing: PairingView,
    /** [pairing] が [PairingView.Open] のときのコードの残り秒（0 以上。V3）。それ以外は 0。 */
    val pairingSecondsLeft: Long,
    val syncing: Boolean,
    val warnings: List<Warning>,
    /** W4 の接続先（IPv4 とポート）。 */
    val endpoints: List<String>,
    val fingerprint: String?,
    /** P8 の最後の値（V56 で消える）。D22。 */
    val progress: ProgressReport?,
    /** P2 の送信中の曲。D22。 */
    val sending: SendingTrack?,
)

/** W1 のトレイの表示。優先順は上から。[ServerFailed] は鍵の保管以外の起動の失敗（ポートの使用中を含む）。 */
enum class TrayStatus { KeyStorageFailed, ServerFailed, NotPaired, XmlUnreadable, Syncing, Idle }

fun trayStatus(state: PcState): TrayStatus = when {
    state.server == ServerStatus.Failed(ServerFailureKind.KeyStorage) -> TrayStatus.KeyStorageFailed
    state.server is ServerStatus.Failed -> TrayStatus.ServerFailed
    state.device == null -> TrayStatus.NotPaired
    state.xml is XmlStatus.Unreadable -> TrayStatus.XmlUnreadable
    state.syncing -> TrayStatus.Syncing
    else -> TrayStatus.Idle
}

/** 警告の印は `warnings` が 1 件以上で付ける。 */
fun hasWarningMark(state: PcState): Boolean = state.warnings.isNotEmpty()

class PcInputs(
    val server: ServerStatus,
    val xml: XmlStatus,
    val settings: Settings,
    val keyMissing: Set<String>,
    val pairing: PairingView,
    val activity: ActivityTracker,
    val progress: ProgressBoard,
    val certificateRegenerated: Boolean,
    val settingsReset: Boolean,
    val endpoints: List<String>,
    val fingerprint: String?,
)

private fun warningOf(kind: ServerFailureKind): Warning = when (kind) {
    ServerFailureKind.PortInUse -> Warning.PortInUse
    ServerFailureKind.Other -> Warning.ServerStartFailed
    ServerFailureKind.KeyStorage -> Warning.KeyStorageFailed
}

fun buildPcState(inputs: PcInputs, now: Instant): PcState {
    val settings = inputs.settings
    val device = settings.devices.firstOrNull()?.let {
        DeviceState(it.deviceId, it.displayName, inputs.activity.isConnected(now), it.deviceId in inputs.keyMissing, it.lastSync)
    }
    val pairing = inputs.pairing
    val pairingSecondsLeft = if (pairing is PairingView.Open) Duration.between(now, pairing.expiresAt).seconds.coerceAtLeast(0) else 0L
    val warnings = buildList {
        if (inputs.xml is XmlStatus.Stale) add(Warning.XmlStale)
        (inputs.server as? ServerStatus.Failed)?.let { add(warningOf(it.kind)) }
        if (inputs.keyMissing.isNotEmpty()) add(Warning.KeyMissing)
        if (inputs.certificateRegenerated && settings.devices.isNotEmpty()) add(Warning.CertificateRegenerated)
        if (inputs.settingsReset) add(Warning.SettingsReset)
    }
    return PcState(
        server = inputs.server,
        xml = inputs.xml,
        device = device,
        pairing = pairing,
        pairingSecondsLeft = pairingSecondsLeft,
        syncing = inputs.activity.isSyncing(now),
        warnings = warnings,
        endpoints = inputs.endpoints,
        fingerprint = inputs.fingerprint,
        progress = inputs.progress.current(),
        sending = inputs.activity.sendingTrack(),
    )
}
