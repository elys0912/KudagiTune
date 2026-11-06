package playlistsyncfox.desktop.discovery

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PC_ID = "abcdef01-2345-4678-8abc-def012345678"
private val ADDRESS_A: InetAddress = InetAddress.getByName("192.0.2.10")
private val ADDRESS_B: InetAddress = InetAddress.getByName("192.0.2.20")
private val ADDRESS_C: InetAddress = InetAddress.getByName("192.0.2.30")

private class FakeRegistrar : MdnsRegistrar {
    val registered = mutableListOf<Pair<InetAddress, Advertisement>>()
    val closed = mutableListOf<InetAddress>()
    val txtUpdates = mutableListOf<Map<String, String>>()

    override fun register(address: InetAddress, ad: Advertisement): MdnsRegistration {
        registered += address to ad
        return object : MdnsRegistration {
            override fun updateTxt(txt: Map<String, String>) {
                txtUpdates += txt
            }

            override fun close() {
                closed += address
            }
        }
    }
}

class AdvertiserTest {
    @Test
    fun `TD-32 登録する TXT は版と pc_id の先頭 8 桁と pair だけで、名前は psfox- と先頭 8 桁`() {
        val ad = advertisementOf(PC_ID, 47800, pair = false)

        assertEquals("psfox-abcdef01", ad.instanceName)
        assertEquals(47800, ad.port)
        assertEquals(mapOf("v" to "1", "pid" to "abcdef01", "pair" to "0"), ad.txt)
    }

    @Test
    fun `TD-33 コードを開く・閉じると TXT の pair が切り替わる`() = runTest {
        val registrar = FakeRegistrar()
        val advertiser = Advertiser(backgroundScope, PC_ID, { listOf(ADDRESS_A) }, registrar)
        advertiser.start(47800, pair = false)

        advertiser.setPair(true)
        advertiser.setPair(false)

        assertEquals(listOf("1", "0"), registrar.txtUpdates.map { it.getValue("pair") })
    }

    @Test
    fun `TD-34 インターフェースが 0 件なら広告せず、接続先の一覧は空になる`() = runTest {
        val registrar = FakeRegistrar()
        val advertiser = Advertiser(backgroundScope, PC_ID, { emptyList() }, registrar)

        advertiser.start(47800, pair = false)

        assertTrue(registrar.registered.isEmpty())
        assertTrue(advertiser.addresses.value.isEmpty())
    }

    @Test
    fun `TD-34 インターフェースが 2 件なら両方に登録する`() = runTest {
        val registrar = FakeRegistrar()
        val advertiser = Advertiser(backgroundScope, PC_ID, { listOf(ADDRESS_A, ADDRESS_B) }, registrar)

        advertiser.start(47800, pair = false)

        assertEquals(listOf(ADDRESS_A, ADDRESS_B), registrar.registered.map { it.first })
        assertEquals(listOf(ADDRESS_A, ADDRESS_B), advertiser.addresses.value)
    }

    @Test
    fun `TD-76 列挙の結果が変わったら V45 の間隔の後に登録し直す`() = runTest {
        val registrar = FakeRegistrar()
        var found = listOf(ADDRESS_A)
        val advertiser = Advertiser(backgroundScope, PC_ID, { found }, registrar)
        advertiser.start(47800, pair = false)

        found = listOf(ADDRESS_A, ADDRESS_C)
        advanceTimeBy(29_999)
        runCurrent()
        assertEquals(1, registrar.registered.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(ADDRESS_A, ADDRESS_A, ADDRESS_C), registrar.registered.map { it.first })
        assertEquals(listOf(ADDRESS_A), registrar.closed)
    }

    @Test
    fun `TD-76 列挙の結果が同じなら登録し直さない`() = runTest {
        val registrar = FakeRegistrar()
        val advertiser = Advertiser(backgroundScope, PC_ID, { listOf(ADDRESS_A) }, registrar)
        advertiser.start(47800, pair = false)

        advanceTimeBy(95_000)
        runCurrent()

        assertEquals(1, registrar.registered.size)
    }
}
