package playlistsyncfox.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.discovery.Advertisement
import playlistsyncfox.desktop.discovery.MdnsRegistrar
import playlistsyncfox.desktop.discovery.MdnsRegistration
import playlistsyncfox.desktop.vault.SecretStore
import playlistsyncfox.desktop.vault.TlsIdentity
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.server.RunningServer
import playlistsyncfox.desktop.server.ServerEnv
import playlistsyncfox.desktop.server.ServerLauncher
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.ui.ServerFailureKind
import playlistsyncfox.desktop.ui.ServerStatus
import playlistsyncfox.desktop.ui.TrayStatus
import playlistsyncfox.desktop.ui.Warning
import playlistsyncfox.desktop.ui.trayStatus
import playlistsyncfox.desktop.watch.DirectoryEvents
import playlistsyncfox.desktop.watch.DirectoryEventsOpener
import java.net.BindException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
private val ADDRESS: InetAddress = InetAddress.getByName("192.0.2.10")

/** 外の世界（サーバー、mDNS、監視、レジストリ）を偽物にした PcApp の一式。実ネットワークとレジストリは使わない。 */
private class Harness(scope: CoroutineScope, store: SecretStore = MemorySecretStore(), addresses: List<InetAddress> = listOf(ADDRESS)) {
    val root: Path = createTempDirectory("psfox-app")
    val order = mutableListOf<String>()
    var portInUse = false
    var env: ServerEnv? = null
    var serverStarts = 0
    var watchOpens = 0
    val registered = mutableListOf<InetAddress>()
    val runKey = FakeRunKey()

    private val launcher = ServerLauncher { _, _: TlsIdentity, serverEnv ->
        serverStarts++
        if (portInUse) throw BindException("使用中")
        env = serverEnv
        RunningServer { order += "server.stop" }
    }
    private val registrar = MdnsRegistrar { address: InetAddress, _: Advertisement ->
        registered += address
        object : MdnsRegistration {
            override fun updateTxt(txt: Map<String, String>) = Unit

            override fun close() {
                order += "mdns.unregister"
            }
        }
    }
    private val opener = DirectoryEventsOpener {
        watchOpens++
        object : DirectoryEvents {
            override suspend fun next(): List<playlistsyncfox.desktop.watch.DirEvent>? = awaitCancellation()

            override fun close() {
                order += "watch.close"
            }
        }
    }

    val paths = AppPaths(root.resolve("settings.json"), root.resolve("vault.bin"), root.resolve("tls-cert.pem"), root.resolve("logs"))
    val clock = MutableClock(T0.plusSeconds(60))
    val app = PcApp(
        paths, root.resolve("none.xml"), clock, scope, PcCrypto(), store, launcher, registrar, { addresses }, opener,
        Autostart(runKey) { "C:\\fake\\playlist-sync-fox.exe" },
    )
}

class FakeRunKey : RunKey {
    val values = HashMap<String, String>()

    override fun get(name: String) = values[name]

    override fun set(name: String, data: String) {
        values[name] = data
    }

    override fun delete(name: String) {
        values.remove(name)
    }
}

class PcAppTest {
    private fun TestScope.harness(store: SecretStore = MemorySecretStore(), addresses: List<InetAddress> = listOf(ADDRESS)) =
        Harness(backgroundScope, store, addresses)

    @Test
    fun `TD-19 鍵の保管の復号に失敗したらサーバーを起動せず、トレイは鍵の保管に失敗を出す`() = runTest {
        val h = harness(BrokenSecretStore())

        h.app.start()
        runCurrent()

        assertEquals(0, h.serverStarts)
        assertEquals(ServerStatus.Failed(ServerFailureKind.KeyStorage), h.app.state.value.server)
        assertTrue(Warning.KeyStorageFailed in h.app.state.value.warnings)
        assertEquals(TrayStatus.KeyStorageFailed, trayStatus(h.app.state.value))
        // 平文のファイルへ逃がさない
        assertFalse(Files.exists(h.paths.secretsFile))
    }

    @Test
    fun `TD-72 ポートが使用中でも元の値へ戻さず警告を出し、起動は続行する`() = runTest {
        val h = harness()
        h.portInUse = true

        h.app.start()
        runCurrent()

        assertEquals(ServerStatus.Failed(ServerFailureKind.PortInUse), h.app.state.value.server)
        assertTrue(Warning.PortInUse in h.app.state.value.warnings)
        assertEquals(1, h.watchOpens)
        assertTrue(h.registered.isEmpty())
    }

    @Test
    fun `TD-71 読めない xml_path は保存しない`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()

        val result = h.app.setXmlPath(h.root.resolve("無い.xml").toString())

        assertEquals(XmlPathResult.Unreadable, result)
        assertEquals(null, h.app.settings.current.xmlPath)
    }

    @Test
    fun `TD-71 読める xml_path は保存する`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()
        val xml = h.root.resolve("lib.xml").also { Files.writeString(it, EMPTY_LIBRARY_XML) }

        assertEquals(XmlPathResult.Saved, h.app.setXmlPath(xml.toString()))

        assertEquals(xml.toString(), h.app.settings.current.xmlPath)
    }

    @Test
    fun `TD-34 インターフェースが 0 件でもサーバーは起動し、接続先は空で mDNS の警告は出ない`() = runTest {
        val h = harness(addresses = emptyList())

        h.app.start()
        runCurrent()

        assertEquals(ServerStatus.Running, h.app.state.value.server)
        assertTrue(h.app.state.value.endpoints.isEmpty())
        assertTrue(h.registered.isEmpty())
        assertTrue(h.app.state.value.warnings.isEmpty())
    }

    @Test
    fun `TD-34 インターフェースが 2 件なら接続先が 2 行出る`() = runTest {
        val h = harness(addresses = listOf(ADDRESS, InetAddress.getByName("192.0.2.20")))

        h.app.start()
        runCurrent()

        assertEquals(listOf("192.0.2.10:47800", "192.0.2.20:47800"), h.app.state.value.endpoints)
    }

    @Test
    fun `TD-77 起動時に鍵が読めない端末は鍵が無いとして W3 に警告が出る`() = runTest {
        val h = harness()
        SettingsStore(h.paths.settingsFile).also { it.load() }.update {
            it.copy(devices = listOf(PairedDevice(DEVICE_ID, "端末", "2026-01-01T00:00:00.000Z")))
        }

        h.app.start()
        runCurrent()

        assertTrue(h.app.state.value.device!!.keyMissing)
        assertTrue(Warning.KeyMissing in h.app.state.value.warnings)
    }

    @Test
    fun `TD-29 鍵の読み込みの後のログに鍵の値が含まれない`() = runTest {
        val store = MemorySecretStore()
        val key = ByteArray(32) { (0x30 + it).toByte() }
        store.write("device/$DEVICE_ID", key)
        val h = harness(store)
        SettingsStore(h.paths.settingsFile).also { it.load() }.update {
            it.copy(devices = listOf(PairedDevice(DEVICE_ID, "端末", "2026-01-01T00:00:00.000Z")))
        }

        h.app.start()
        runCurrent()

        val log = Files.list(h.paths.logDir).use { s -> s.toList().joinToString("\n") { String(it.readBytes()) } }
        assertTrue(log.isNotBlank())
        assertFalse(log.contains(Base64.getEncoder().encodeToString(key)))
        assertFalse(log.contains(key.toHexString()))
    }

    @Test
    fun `TD-63 送信中が 0 件なら待たずに、コード・mDNS・サーバー・監視の順で止まる`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()
        h.app.showPairingCode()
        assertTrue(h.app.state.value.pairing is PairingView.Open)
        h.order.clear()

        h.app.stop()
        runCurrent()

        assertEquals(0, currentTime)
        assertEquals(PairingView.Closed, h.app.state.value.pairing)
        assertEquals(listOf("mdns.unregister", "server.stop", "watch.close"), h.order)
        // 設定は更新のたびに書き込み済み。ファイルが現在の値と一致する
        assertEquals(h.app.settings.current, SettingsStore(h.paths.settingsFile).load().settings)
    }

    @Test
    fun `TD-63 送信中が 1 件なら V46 の後にサーバーを止める`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()
        checkNotNull(h.env).activity.trackRequestStarted()
        h.order.clear()

        backgroundScope.launch { h.app.stop() }
        advanceTimeBy(4_999)
        runCurrent()
        assertFalse("server.stop" in h.order)

        advanceTimeBy(1)
        runCurrent()
        assertTrue("server.stop" in h.order)
        assertNotNull(h.env)
    }

    // ---- Run キーの窓口（TD-64） ----

    private val autostartPath = "C:\\fake\\app.exe"

    @Test
    fun `TD-64 登録すると値の名前 playlist-sync-fox で、現在のパスと --background が入る`() {
        val runKey = FakeRunKey()

        Autostart(runKey) { autostartPath }.apply(enabled = true)

        assertEquals(mapOf("playlist-sync-fox" to "\"C:\\fake\\app.exe\" --background"), runKey.values)
    }

    @Test
    fun `TD-64 パスが変わった起動では値が現在のパスに書き直される`() {
        val runKey = FakeRunKey()
        Autostart(runKey) { "C:\\old\\app.exe" }.apply(enabled = true)

        Autostart(runKey) { "C:\\new\\app.exe" }.apply(enabled = true)

        assertEquals(mapOf("playlist-sync-fox" to "\"C:\\new\\app.exe\" --background"), runKey.values)
    }

    @Test
    fun `TD-64 無効にすると値が消え、値が無いときの解除も失敗しない`() {
        val runKey = FakeRunKey()
        val autostart = Autostart(runKey) { autostartPath }
        autostart.apply(enabled = true)

        autostart.apply(enabled = false)
        autostart.apply(enabled = false)

        assertTrue(runKey.values.isEmpty())
    }
}
