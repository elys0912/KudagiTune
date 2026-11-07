package playlistsyncfox.android.discovery

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

private fun reasonOf(input: String) = assertIs<ManualCheck.Invalid>(checkManualEndpoint(input), input).reason

private fun endpointOf(input: String) = assertIs<ManualCheck.Valid>(checkManualEndpoint(input), input).endpoint

class ManualEndpointTest {
    @Test
    fun `TA-108 名前と IPv4 とポート付きの宛先は受理され、ポートが無ければ既定の 47800 になる`() {
        assertEquals(ManualEndpoint("pc-name.tailnet.example.invalid", 47800), endpointOf("pc-name.tailnet.example.invalid"))
        assertEquals(ManualEndpoint("192.0.2.10", 47800), endpointOf("192.0.2.10"))
        assertEquals(ManualEndpoint("192.0.2.10", 47800), endpointOf("  192.0.2.10:47800 "))
    }

    @Test
    fun `TA-109 空と URL とパスと IPv6 と範囲外の値は理由つきで拒否される`() {
        assertEquals(ManualReason.Empty, reasonOf(""))
        assertEquals(ManualReason.Empty, reasonOf("   "))
        assertEquals(ManualReason.BadChar, reasonOf("https://example.invalid"))
        assertEquals(ManualReason.BadChar, reasonOf("example.invalid/a"))
        assertEquals(ManualReason.BadShape, reasonOf("::1"))
        assertEquals(ManualReason.BadIp, reasonOf("192.0.2.256"))
        assertEquals(ManualReason.BadPort, reasonOf("192.0.2.10:0"))
        assertEquals(ManualReason.BadPort, reasonOf("192.0.2.10:65536"))
        assertEquals(ManualReason.BadName, reasonOf("-pc.example.invalid"))
        assertEquals(ManualReason.BadName, reasonOf("pc..example.invalid"))
    }

    @Test
    fun `TA-110 ホスト名はラベル 63 文字と全体 253 文字までが通り、1 つ超えると拒否される`() {
        val label63 = "a".repeat(63)
        assertNotNull(endpointOf("$label63.example.invalid"))
        assertEquals(ManualReason.BadName, reasonOf("${"a".repeat(64)}.example.invalid"))

        // 63 + 1 + 63 + 1 + 63 + 1 + 61 = 253
        val name253 = listOf(label63, label63, label63, "a".repeat(61)).joinToString(".")
        assertEquals(253, name253.length)
        assertNotNull(endpointOf(name253))
        assertEquals(ManualReason.BadName, reasonOf(name253 + "a"))
    }

    @Test
    fun `TA-111 保存する列は入力の形で決まり、名前は tailscale、IPv4 は usb になる`() {
        assertEquals("tailscale", endpointOf("pc.example.invalid").kind)
        assertEquals("usb", endpointOf("192.0.2.10").kind)
        assertEquals("192.0.2.10:47800", endpointOf("192.0.2.10").savedValue)
    }
}

class DiscoveredSelectionTest {
    private fun service(vararg txt: Pair<String, String>) = ResolvedService("psfox-01234567", "192.0.2.10", 47800, txt.toMap())

    private val valid = arrayOf("v" to "1", "pid" to "01234567", "pair" to "1")

    @Test
    fun `TA-112 v が読めない、知らない版、pid 欠け、pair 欠けは一覧に出ず、正しい 1 件だけ出る`() {
        assertNull(selectDiscovered(service("v" to "x", "pid" to "01234567", "pair" to "1"), onlyPairingOpen = true))
        assertNull(selectDiscovered(service("v" to "2", "pid" to "01234567", "pair" to "1"), onlyPairingOpen = true))
        assertNull(selectDiscovered(service("v" to "1", "pair" to "1"), onlyPairingOpen = true))
        assertNull(selectDiscovered(service("v" to "1", "pid" to "01234567"), onlyPairingOpen = true))

        val pc = assertNotNull(selectDiscovered(service(*valid), onlyPairingOpen = true))
        assertEquals("01234567", pc.pcIdPrefix)
        assertEquals(47800, pc.port)
    }

    @Test
    fun `TA-112 ペアリング前は pair が 0 のものを出さず、ペアリング済みの引き直しでは出す`() {
        val closed = service("v" to "1", "pid" to "01234567", "pair" to "0")

        assertNull(selectDiscovered(closed, onlyPairingOpen = true))
        assertEquals(false, assertNotNull(selectDiscovered(closed, onlyPairingOpen = false)).pairingOpen)
    }
}
