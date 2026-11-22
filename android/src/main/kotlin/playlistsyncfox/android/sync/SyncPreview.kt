package playlistsyncfox.android.sync

import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.net.Endpoint
import playlistsyncfox.android.net.SyncApi
import playlistsyncfox.shared.diff.Diff
import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.diff.computeDiff
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestReadResult
import playlistsyncfox.shared.manifest.readManifest
import playlistsyncfox.shared.result.Transport
import java.net.Inet4Address
import java.net.NetworkInterface

/** `last_endpoint` を先頭に、残りは LAN、Tailscale、USB（保存してあるものだけ）。 */
internal fun endpointOrder(pairing: Pairing): List<String> {
    val saved = listOf(
        PairingKeeper.LAN to pairing.endpointLan,
        PairingKeeper.TAILSCALE to pairing.endpointTailscale,
        PairingKeeper.USB to pairing.endpointUsb,
    ).filter { it.second != null }.map { it.first }
    val last = pairing.lastEndpoint
    return if (last != null && last in saved) listOf(last) + saved.filter { it != last } else saved
}

/** 端末側の 1 つのインターフェースの IPv4。[address] は 4 バイト。 */
class LocalLink(val interfaceName: String, val address: ByteArray, val prefixLength: Int)

/** Android のテザリングのインターフェース名の先頭（B4-1 の 3）。実機で確かめる（C-5、C-18）。 */
private val BLUETOOTH_PREFIXES = listOf("bt")
private val USB_PREFIXES = listOf("rndis", "usb", "ncm")
private val WLAN_PREFIXES = listOf("wlan")

/**
 * 接続した方式の判定（X3-5 の `transport`）。PC の [pcHost] と同じ区画の IPv4 を持つインターフェースの名前で決める。
 * [pcHost] が IPv4 でない、または同じ区画のインターフェースが無い・名前が知らない形なら [saved]（保存の列の種類）のまま。
 */
internal fun detectTransport(pcHost: String, saved: Transport, links: List<LocalLink>): Transport {
    val pcAddress = parseIpv4(pcHost) ?: return saved
    val link = links.filter { sameSubnet(it.address, pcAddress, it.prefixLength) }.maxByOrNull { it.prefixLength } ?: return saved

    val name = link.interfaceName
    return when {
        BLUETOOTH_PREFIXES.any(name::startsWith) -> Transport.BluetoothTethering
        USB_PREFIXES.any(name::startsWith) -> Transport.UsbTethering
        WLAN_PREFIXES.any(name::startsWith) -> Transport.Lan
        else -> saved
    }
}

private fun parseIpv4(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { it.toIntOrNull()?.takeIf { octet -> octet in 0..255 } ?: return null }
    return ByteArray(4) { octets[it].toByte() }
}

private fun sameSubnet(a: ByteArray, b: ByteArray, prefixLength: Int): Boolean {
    for (bit in 0 until prefixLength) {
        val mask = 0x80 ushr (bit % 8)
        if (a[bit / 8].toInt() and mask != b[bit / 8].toInt() and mask) return false
    }
    return true
}

/** 起動中のインターフェースの IPv4 を列挙する。列挙に失敗したら空。 */
fun listLocalLinks(): List<LocalLink> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { iface ->
            iface.interfaceAddresses
                .filter { it.address is Inet4Address }
                .map { LocalLink(iface.name, it.address.address, it.networkPrefixLength.toInt()) }
        }
}.getOrDefault(emptyList())

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

/** 到達確認が開いた接続の入れ物を [block] に渡し、[block] を抜けるときに全部閉じる。 */
internal suspend fun <T> withOpenedApis(block: suspend (MutableList<SyncApi>) -> T): T {
    val opened = mutableListOf<SyncApi>()
    try {
        return block(opened)
    } finally {
        opened.forEach { runCatching { it.close() } }
    }
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
            PairingKeeper.LAN -> {
                val service = resolveLan(saved)
                if (service != null && service.serviceName != saved.endpointLan) {
                    saved = saved.copy(endpointLan = service.serviceName)
                    store.updatePairing(saved)
                }
                service?.let { Endpoint(Transport.Lan, it.host, it.port) }
            }
            PairingKeeper.TAILSCALE -> parseHostPort(Transport.Tailscale, saved.endpointTailscale)
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

internal fun PlacedTrack.toSyncRecord() = SyncRecord(persistentId, sourceSize, sourceModified, relativePath)

/** マニフェストと、端末に置いた曲（T1）・MediaStore の現状との差分。同期本体と画面側が同じ計算をする。 */
internal suspend fun SyncEnvironment.diffFor(
    manifest: Manifest,
    placed: List<PlacedTrack>,
    recordedLibraryId: String?,
): Diff = computeDiff(
    tracks = manifest.tracks,
    records = placed.map { it.toSyncRecord() },
    // T1 の曲のうち MediaStore に行が今ある曲（pending も含む）。差分の入力に state は渡さない
    presentIds = writer.presentIds(placed),
    manifestLibraryId = manifest.libraryId,
    recordedLibraryId = recordedLibraryId,
    removalConfirmThreshold = SyncValues.REMOVAL_CONFIRM_THRESHOLD,
    unresolvedPlaylists = manifest.unresolvedPlaylists,
)

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
 * 手動の同期の前に、画面側が P1 と差分で「消す」の確認の要否を得る（X7-1-2 の手順 1〜4）。
 * 接続先は同期本体と同じ選び方（[reachPc]）で試し、P3 が通った 1 件で P1 を取る。書くのは、つながった接続先の記録だけ。
 */
class SyncPreview(private val env: SyncEnvironment) {
    suspend fun check(): RemovalCheck {
        // 設定が OFF なら消さないので、確認は要らない
        if (!env.deleteMissing) return RemovalCheck.Allowed
        val pairing = env.store.pairing() ?: return RemovalCheck.Failed(reachFailed = false)
        // 鍵が無いときの E2 はワーカーが記録する。ここでは届かなかったことにしない
        if (!env.aliasExists(pairing.keyAlias)) return RemovalCheck.Failed(reachFailed = false)
        val selectedIds = env.store.selectedPlaylists().map { it.playlistId }

        return withOpenedApis { opened ->
            when (val reach = env.reachPc(pairing, opened) { it.digest(selectedIds) }) {
                is Reach.Connected -> gateOf(pairing, reach.api, selectedIds)
                // E2、E4、500：届いているので、ほかの接続先は試さない。ワーカーが記録する
                is Reach.Stopped -> RemovalCheck.Failed(reachFailed = false)
                is Reach.Unreachable -> RemovalCheck.Failed(reachFailed = !reach.mismatch)
            }
        }
    }

    private suspend fun gateOf(pairing: Pairing, api: SyncApi, selectedIds: List<String>): RemovalCheck {
        val body = (api.manifest(selectedIds) as? ApiResult.Ok)?.value ?: return RemovalCheck.Failed(reachFailed = false)
        val manifest = (readManifest(body) as? ManifestReadResult.Loaded)?.manifest ?: return RemovalCheck.Failed(reachFailed = false)
        val placed = env.store.placedTracks(pairing.deviceId)
        // 使うのは toRemove の件数だけ
        val diff = env.diffFor(manifest, placed, recordedLibraryId(env.store.runs()))
        // 手動は 1 件でも消すなら確認を出す（D19）。`removalGate` は自動の判定に使う
        return if (diff.toRemove.isEmpty()) RemovalCheck.Allowed else RemovalCheck.NeedsConfirmation(diff.toRemove.size)
    }
}
