package playlistsyncfox.android.sync

import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.net.Endpoint
import playlistsyncfox.android.net.SyncApi
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

/** 到達確認の結果（X6-3 の 2 の 2）。[T] は到達確認に使った通信の成功の値。 */
internal sealed interface Reach<out T> {
    /** [api] は閉じずに返す。呼び出し側が渡した `opened` に入っているので、まとめて閉じる。 */
    class Connected<out T>(val api: SyncApi, val endpoint: Endpoint, val value: T) : Reach<T>

    /** 全部の接続先が応えない。[mismatch] は証明書の固定の不一致が 1 つでもあったか。 */
    class Unreachable(val mismatch: Boolean) : Reach<Nothing>

    /** E2、E4、500 などを受けた。PC には届いているので、次の接続先を試さない。 */
    class Stopped(val failure: ApiFailure) : Reach<Nothing>
}

/**
 * 到達確認（X6-3 の 2 の 2）。[endpointOrder] の順に、[probe] が成功する接続先を探す。同期本体と画面側が同じ選び方をする。
 * つながった接続先を `last_endpoint` に、NSD で引き直した名が保存と違うときは `endpoint_lan` に保存する。
 * 開いた接続は [opened] に足す。応えなかった接続は閉じて外す。
 */
internal suspend fun <T> SyncEnvironment.reachPc(
    pairing: Pairing,
    opened: MutableList<SyncApi>,
    probe: suspend (SyncApi) -> ApiResult<T>,
): Reach<T> {
    var saved = pairing
    var mismatch = false
    for (kind in endpointOrder(saved)) {
        val endpoint = when (kind) {
            LAN -> {
                val service = resolveLan(saved)
                if (service != null && service.serviceName != saved.endpointLan) {
                    saved = saved.copy(endpointLan = service.serviceName)
                    store.updatePairing(saved)
                }
                service?.let { Endpoint(Transport.Lan, it.host, it.port) }
            }
            TAILSCALE -> parseHostPort(Transport.Tailscale, saved.endpointTailscale)
            else -> parseHostPort(Transport.UsbTethering, saved.endpointUsb)
        } ?: continue
        val candidate = apis.open(endpoint).also { opened += it }
        when (val result = probe(candidate)) {
            is ApiResult.Ok -> {
                if (saved.lastEndpoint != kind) store.updatePairing(saved.copy(lastEndpoint = kind))
                return Reach.Connected(candidate, endpoint, result.value)
            }
            is ApiResult.Fail -> when (val failure = result.failure) {
                ApiFailure.Disconnected -> log.line("$kind は応えない")
                ApiFailure.PinMismatch -> {
                    log.line("$kind は証明書の固定が合わない")
                    mismatch = true
                }
                else -> return Reach.Stopped(failure)
            }
        }
        runCatching { candidate.close() }
        opened.remove(candidate)
    }
    return Reach.Unreachable(mismatch)
}

/** T4 の `pc_id` の先頭 8 桁と前方一致する PC を NSD から引き直す（X6-3 の 2 の 2）。 */
private suspend fun SyncEnvironment.resolveLan(pairing: Pairing): LanService? {
    val found = withTimeoutOrNull(SyncValues.NSD_RESOLVE_WAIT_MS) { lan.discover() }
    val service = found?.firstOrNull { it.pcIdPrefix.isNotEmpty() && pairing.pcId.startsWith(it.pcIdPrefix) }
    if (service == null) log.line("LAN は引き直しで見つからない")
    return service
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
        // 設定が OFF なら消さないので、確認は要らない
        if (!env.deleteMissing) return RemovalCheck.Allowed
        val pairing = env.store.pairing() ?: return RemovalCheck.Failed(reachFailed = false)
        // 鍵が無いときの E2 はワーカーが記録する。ここでは届かなかったことにしない
        if (!env.aliasExists(pairing.keyAlias)) return RemovalCheck.Failed(reachFailed = false)
        val selectedIds = env.store.selectedPlaylists().map { it.playlistId }

        val opened = mutableListOf<SyncApi>()
        try {
            return when (val reach = env.reachPc(pairing, opened) { it.digest(selectedIds) }) {
                is Reach.Connected -> gateOf(pairing, reach.api, selectedIds)
                // E2、E4、500：届いているので、ほかの接続先は試さない。ワーカーが記録する
                is Reach.Stopped -> RemovalCheck.Failed(reachFailed = false)
                is Reach.Unreachable -> RemovalCheck.Failed(reachFailed = !reach.mismatch)
            }
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun gateOf(pairing: Pairing, api: playlistsyncfox.android.net.SyncApi, selectedIds: List<String>): RemovalCheck {
        val body = (api.manifest(selectedIds) as? ApiResult.Ok)?.value ?: return RemovalCheck.Failed(reachFailed = false)
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
            isPlayable = env.isPlayable,
        )
        // 手動は 1 件でも消すなら確認を出す（D19）。`removalGate` は自動の判定に使う
        return if (diff.toRemove.isEmpty()) RemovalCheck.Allowed else RemovalCheck.NeedsConfirmation(diff.toRemove.size)
    }
}
