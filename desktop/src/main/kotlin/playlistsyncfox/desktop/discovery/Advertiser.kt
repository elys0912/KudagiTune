package playlistsyncfox.desktop.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import playlistsyncfox.desktop.DesktopValues
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

/** サービスの型（Q1 で変わりうる）。名前はここだけに置く。 */
const val SERVICE_TYPE = "_psfox._tcp.local."

/** TXT の `v`：広告の形の版。 */
private const val ADVERTISEMENT_VERSION = 1

private const val PC_ID_PREFIX_LENGTH = 8

class Advertisement(val instanceName: String, val port: Int, val txt: Map<String, String>)

/** TXT に曲名、人名、PC のホスト名を入れない（N1-6）。 */
fun advertisementOf(pcId: String, port: Int, pair: Boolean): Advertisement {
    val prefix = pcId.take(PC_ID_PREFIX_LENGTH)
    return Advertisement(
        instanceName = "psfox-$prefix",
        port = port,
        txt = mapOf("v" to ADVERTISEMENT_VERSION.toString(), "pid" to prefix, "pair" to if (pair) "1" else "0"),
    )
}

/** 1 つのインターフェースへの登録。実体は JmDNS。 */
interface MdnsRegistration : AutoCloseable {
    /** 登録を変えて TXT を更新する。再起動しない。 */
    fun updateTxt(txt: Map<String, String>)
}

fun interface MdnsRegistrar {
    fun register(address: InetAddress, ad: Advertisement): MdnsRegistration
}

/** JmDNS：インターフェースごとに 1 つのインスタンス。複数アドレスの同時の広告の挙動は未確認で、C-13 で確かめる。 */
class JmdnsRegistrar : MdnsRegistrar {
    override fun register(address: InetAddress, ad: Advertisement): MdnsRegistration {
        val jmdns = JmDNS.create(address)
        val info = ServiceInfo.create(SERVICE_TYPE, ad.instanceName, ad.port, 0, 0, ad.txt)
        jmdns.registerService(info)
        return object : MdnsRegistration {
            override fun updateTxt(txt: Map<String, String>) = info.setText(txt)

            override fun close() {
                jmdns.unregisterAllServices()
                jmdns.close()
            }
        }
    }
}

/** 起動中の IPv4 のインターフェースからループバックを除いたアドレス。仮想アダプタは除かない（X5-3）。 */
fun listIpv4Addresses(): List<InetAddress> =
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()

/**
 * mDNS の広告（X5-3）。サーバーの起動が成功した後に始め、停止の前に解除する。
 * インターフェースが 0 件なら広告しない（サーバーは起動する）。V45 の間隔で列挙し直し、変われば登録し直す。
 */
class Advertiser(
    private val scope: CoroutineScope,
    private val pcId: String,
    private val enumerate: () -> List<InetAddress>,
    private val registrar: MdnsRegistrar,
) {
    private var port = 0
    private var pair = false
    private var registrations: Map<InetAddress, MdnsRegistration> = emptyMap()
    private var poller: Job? = null
    private val addressesFlow = MutableStateFlow<List<InetAddress>>(emptyList())

    /** 広告しているアドレス。W4 の接続先の一覧になる。 */
    val addresses: StateFlow<List<InetAddress>> = addressesFlow

    @Synchronized
    fun start(port: Int, pair: Boolean) {
        this.port = port
        this.pair = pair
        reregister(enumerate())
        poller = scope.launch {
            while (isActive) {
                delay(DesktopValues.NETWORK_POLL.toMillis())
                val found = enumerate()
                synchronized(this@Advertiser) { if (found != addressesFlow.value) reregister(found) }
            }
        }
    }

    /** コードの表示の開始と終了のたびに呼ぶ。 */
    @Synchronized
    fun setPair(pair: Boolean) {
        this.pair = pair
        registrations.values.forEach { it.updateTxt(advertisementOf(pcId, port, pair).txt) }
    }

    @Synchronized
    fun stop() {
        poller?.cancel()
        poller = null
        registrations.values.forEach(MdnsRegistration::close)
        registrations = emptyMap()
        addressesFlow.value = emptyList()
    }

    private fun reregister(found: List<InetAddress>) {
        registrations.values.forEach(MdnsRegistration::close)
        val ad = advertisementOf(pcId, port, pair)
        registrations = found.associateWith { registrar.register(it, ad) }
        addressesFlow.value = found
    }
}
