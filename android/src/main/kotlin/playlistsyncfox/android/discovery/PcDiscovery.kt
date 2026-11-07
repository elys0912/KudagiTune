package playlistsyncfox.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.android.sync.LanResolver
import playlistsyncfox.android.sync.LanService
import java.net.Inet4Address
import kotlin.coroutines.resume

/** 発見した PC 1 台（X7-2-1）。 */
data class DiscoveredPc(
    val serviceName: String,
    val host: String,
    val port: Int,
    val protocolVersion: Int,
    val pcIdPrefix: String,
    val pairingOpen: Boolean,
)

/** 解決したサービス。TXT は文字列にしてある。`NsdServiceInfo` を包み、選別を JVM で試せるようにする。 */
class ResolvedService(val serviceName: String, val host: String, val port: Int, val txt: Map<String, String>)

/** 端末が対応するプロトコルの版（B4-1 の 1）。 */
const val SUPPORTED_PROTOCOL_VERSION = 1

/**
 * 解決したサービスから一覧に出すものを選ぶ。TXT の `v` が読めない、知らない版、`pid`・`pair` の欠けは捨てる。
 * [onlyPairingOpen] はペアリング前（T4 が無い）で、`pair` が 1 のものだけを出す。
 */
fun selectDiscovered(service: ResolvedService, onlyPairingOpen: Boolean): DiscoveredPc? {
    val version = service.txt["v"]?.toIntOrNull() ?: return null
    if (version > SUPPORTED_PROTOCOL_VERSION) return null
    val pid = service.txt["pid"]?.takeIf { it.isNotEmpty() } ?: return null
    val open = when (service.txt["pair"]) {
        "1" -> true
        "0" -> false
        else -> return null
    }
    if (onlyPairingOpen && !open) return null
    return DiscoveredPc(service.serviceName, service.host, service.port, version, pid, open)
}

/** `_psfox._tcp` の発見。購読している間だけ `MulticastLock` を取り、止めたら放す。 */
class PcDiscovery(context: Context) {
    private val appContext = context.applicationContext

    /** 見つかった PC の一覧を流す。[onlyPairingOpen] はペアリング前の絞り込み。 */
    fun discover(onlyPairingOpen: Boolean): Flow<List<DiscoveredPc>> = callbackFlow {
        val nsd = appContext.getSystemService(NsdManager::class.java)
        val wifi = appContext.getSystemService(WifiManager::class.java)
        // T 拡張 7 より前の端末で mDNS を受けるために要る
        val lock = wifi.createMulticastLock(LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire()
        }

        val found = LinkedHashMap<String, DiscoveredPc>()
        val queue = Channel<NsdServiceInfo>(Channel.UNLIMITED)

        // 解決は 1 件ずつ順に行う（同時実行を避ける）
        launch {
            for (info in queue) {
                val resolved = withTimeoutOrNull(RESOLVE_WAIT_MS) { resolve(nsd, info) } ?: continue
                selectDiscovered(resolved, onlyPairingOpen)?.let { found[it.serviceName] = it }
                trySend(found.values.toList())
            }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') == SERVICE_TYPE.trimEnd('.')) queue.trySend(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                found.remove(serviceInfo.serviceName)
                trySend(found.values.toList())
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        trySend(emptyList())

        awaitClose {
            runCatching { nsd.stopServiceDiscovery(listener) }
            queue.close()
            if (lock.isHeld) lock.release()
        }
    }

    // API 34 で非推奨になったが、minSdk 30 では他に手段が無い
    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): ResolvedService? =
        suspendCancellableCoroutine { continuation ->
            nsd.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host as? Inet4Address
                        val resolved = address?.hostAddress?.let { host ->
                            val txt = serviceInfo.attributes.mapValues { (_, value) -> value?.decodeToString().orEmpty() }
                            ResolvedService(serviceInfo.serviceName, host, serviceInfo.port, txt)
                        }
                        if (continuation.isActive) continuation.resume(resolved)
                    }
                },
            )
        }

    private companion object {
        const val SERVICE_TYPE = "_psfox._tcp."
        const val LOCK_TAG = "playlist-sync-fox-nsd"
        const val RESOLVE_WAIT_MS = 5_000L
    }
}

/** 同期の到達確認での NSD の引き直し（X6-3 の 2 の 2）。呼び出し側の待ち（V14）の内に戻る。 */
class NsdLanResolver(private val discovery: PcDiscovery) : LanResolver {
    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun discover(): List<LanService> {
        var latest = emptyList<DiscoveredPc>()
        // 1 台見つかってから少し待ち、同時に出ている別の PC も拾う
        val settled = withTimeoutOrNull(WINDOW_MS) {
            discovery.discover(onlyPairingOpen = false)
                .onEach { latest = it }
                .mapLatest { list -> if (list.isNotEmpty()) { delay(SETTLE_MS); list } else null }
                .filterNotNull()
                .first()
        }
        return (settled ?: latest).map { LanService(it.serviceName, it.host, it.port, it.pcIdPrefix) }
    }

    private companion object {
        const val WINDOW_MS = 2_500L
        const val SETTLE_MS = 300L
    }
}
