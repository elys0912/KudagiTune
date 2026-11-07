package playlistsyncfox.android.sync

import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.net.Endpoint
import playlistsyncfox.shared.diff.RemovalGate
import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.diff.computeDiff
import playlistsyncfox.shared.manifest.ManifestReadResult
import playlistsyncfox.shared.manifest.readManifest
import playlistsyncfox.shared.result.Transport

internal const val LAN = "lan"
internal const val TAILSCALE = "tailscale"
internal const val USB = "usb"

/** `last_endpoint` を先頭に、残りは LAN、Tailscale、USB（保存してあるものだけ）。 */
internal fun endpointOrder(pairing: Pairing): List<String> {
    val saved = listOf(LAN to pairing.endpointLan, TAILSCALE to pairing.endpointTailscale, USB to pairing.endpointUsb)
        .filter { it.second != null }.map { it.first }
    val last = pairing.lastEndpoint
    return if (last != null && last in saved) listOf(last) + saved.filter { it != last } else saved
}

internal fun parseHostPort(transport: Transport, text: String?): Endpoint? {
    val host = text?.substringBeforeLast(':', "")?.takeIf { it.isNotEmpty() }
    val port = text?.substringAfterLast(':', "")?.toIntOrNull()
    return if (host != null && port != null) Endpoint(transport, host, port) else null
}

/** 同期ボタンの後に、画面側が P1 と差分で得る「消す」の確認の要否（X7-1-2 の手順 1〜4）。 */
sealed interface RemovalCheck {
    data object Allowed : RemovalCheck

    /** `LibraryChanged` か `NeedsConfirmation`。[toRemove] は消す件数（1 件以上）。 */
    data class NeedsConfirmation(val toRemove: Int) : RemovalCheck

    /** P1 の取得か差分の計算に失敗した。[reachFailed] は全部の接続先が応えず、証明書の不一致が無いとき。 */
    data class Failed(val reachFailed: Boolean) : RemovalCheck
}

/**
 * 手動の同期の前に、画面側が P1 と差分で「消す」の確認の要否を得る（X7-1-2 の手順 1〜2）。
 * 接続先は到達確認と同じ順で試し、P3 が通った 1 件で P1 を取る。何も書かない。
 */
class SyncPreview(private val env: SyncEnvironment) {
    suspend fun check(): RemovalCheck {
        val pairing = env.store.pairing() ?: return RemovalCheck.Failed(reachFailed = false)
        // 鍵が無いときの E2 はワーカーが記録する。ここでは届かなかったことにしない
        if (!env.aliasExists(pairing.keyAlias)) return RemovalCheck.Failed(reachFailed = false)

        var mismatch = false
        for (kind in endpointOrder(pairing)) {
            val endpoint = endpointFor(pairing, kind) ?: continue
            val api = env.apis.open(endpoint)
            try {
                when (val reach = api.digest()) {
                    is ApiResult.Ok -> return gateOf(pairing, api)
                    is ApiResult.Fail -> when (reach.failure) {
                        ApiFailure.Disconnected -> Unit
                        ApiFailure.PinMismatch -> mismatch = true
                        // E2、E4、500：届いているので、ほかの接続先は試さない。ワーカーが記録する
                        else -> return RemovalCheck.Failed(reachFailed = false)
                    }
                }
            } finally {
                runCatching { api.close() }
            }
        }
        return RemovalCheck.Failed(reachFailed = !mismatch)
    }

    private suspend fun gateOf(pairing: Pairing, api: playlistsyncfox.android.net.SyncApi): RemovalCheck {
        val body = (api.manifest() as? ApiResult.Ok)?.value ?: return RemovalCheck.Failed(reachFailed = false)
        val manifest = (readManifest(body) as? ManifestReadResult.Loaded)?.manifest ?: return RemovalCheck.Failed(reachFailed = false)
        val placed = env.store.placedTracks(pairing.deviceId)
        val diff = computeDiff(
            tracks = manifest.tracks,
            records = placed.map { SyncRecord(it.persistentId, it.sourceSize, it.sourceModified, it.relativePath) },
            presentIds = env.writer.presentIds(placed),
            manifestLibraryId = manifest.libraryId,
            recordedLibraryId = recordedLibraryId(env.store.runs()),
            removalConfirmThreshold = SyncValues.REMOVAL_CONFIRM_THRESHOLD,
            unresolvedPlaylists = manifest.unresolvedPlaylists,
        )
        return when {
            diff.removalGate == RemovalGate.Allowed || diff.toRemove.isEmpty() -> RemovalCheck.Allowed
            else -> RemovalCheck.NeedsConfirmation(diff.toRemove.size)
        }
    }

    private suspend fun endpointFor(pairing: Pairing, kind: String): Endpoint? = when (kind) {
        LAN -> {
            val found = withTimeoutOrNull(SyncValues.NSD_RESOLVE_WAIT_MS) { env.lan.discover() }
            found?.firstOrNull { it.pcIdPrefix.isNotEmpty() && pairing.pcId.startsWith(it.pcIdPrefix) }
                ?.let { Endpoint(Transport.Lan, it.host, it.port) }
        }
        TAILSCALE -> parseHostPort(Transport.Tailscale, pairing.endpointTailscale)
        else -> parseHostPort(Transport.UsbTethering, pairing.endpointUsb)
    }
}
