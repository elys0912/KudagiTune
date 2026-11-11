package playlistsyncfox.android.sync

import playlistsyncfox.shared.result.Transport
import kotlin.test.Test
import kotlin.test.assertEquals

/** 接続した方式の判定（B4-1 の 3、X3-5 の `transport`）。 */
class TransportDetectTest {
    private val wlan = LocalLink("wlan0", ipv4(192, 168, 1, 20), 24)
    private val usb = LocalLink("rndis0", ipv4(192, 168, 42, 129), 24)
    private val bluetooth = LocalLink("bt-pan", ipv4(192, 168, 44, 1), 24)

    @Test
    fun `TA-268 PC の IPv4 と同じ区画のインターフェースの名前で方式を決める`() {
        val links = listOf(wlan, usb, bluetooth)

        assertEquals(Transport.BluetoothTethering, detectTransport("192.168.44.10", Transport.UsbTethering, links))
        assertEquals(Transport.UsbTethering, detectTransport("192.168.42.10", Transport.UsbTethering, links))
        assertEquals(Transport.Lan, detectTransport("192.168.1.5", Transport.UsbTethering, links))
    }

    @Test
    fun `TA-268 区画の境界は prefixLength で決まり、1 ビット違えば別の区画`() {
        val links = listOf(LocalLink("bt-pan", ipv4(10, 0, 0, 1), 25))

        assertEquals(Transport.BluetoothTethering, detectTransport("10.0.0.127", Transport.UsbTethering, links))
        assertEquals(Transport.UsbTethering, detectTransport("10.0.0.128", Transport.UsbTethering, links))
    }

    @Test
    fun `TA-268 名前でない宛先、同じ区画が無い、知らない名前なら保存の列の種類のまま`() {
        assertEquals(Transport.Tailscale, detectTransport("pc.example.invalid", Transport.Tailscale, listOf(bluetooth)))
        assertEquals(Transport.UsbTethering, detectTransport("172.16.0.1", Transport.UsbTethering, listOf(bluetooth)))
        assertEquals(Transport.UsbTethering, detectTransport("192.168.44.10", Transport.UsbTethering, listOf(LocalLink("tun0", bluetooth.address, 24))))
    }

    private fun ipv4(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())
}
