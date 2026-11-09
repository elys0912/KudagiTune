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
import playlistsyncfox.desktop.settings.LogLevel
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.ui.ServerFailureKind
import playlistsyncfox.desktop.ui.ServerStatus
import playlistsyncfox.desktop.ui.TrayStatus
import playlistsyncfox.desktop.ui.Warning
import playlistsyncfox.desktop.ui.trayStatus
import playlistsyncfox.desktop.vault.loadOrCreateTls
import playlistsyncfox.desktop.watch.DirectoryEvents
import playlistsyncfox.desktop.watch.DirectoryEventsOpener
import playlistsyncfox.desktop.watch.XmlStatus
import playlistsyncfox.shared.auth.PairingOutcome
import playlistsyncfox.shared.auth.confirmPairing
import playlistsyncfox.shared.auth.startPairing
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
private class Harness(scope: CoroutineScope, val store: SecretStore = MemorySecretStore(), addresses: List<InetAddress> = listOf(ADDRESS)) {
    val root: Path = createTempDirectory("psfox-app")
    val order = mutableListOf<String>()
    var portInUse = false
    var launchFailure: Exception? = null
    var env: ServerEnv? = null
    var serverStarts = 0
    var watchOpens = 0
    val registered = mutableListOf<InetAddress>()
    val txtUpdates = mutableListOf<Map<String, String>>()
    val runKey = FakeRunKey()

    private val launcher = ServerLauncher { _, _: TlsIdentity, serverEnv ->
        serverStarts++
        if (portInUse) throw BindException("使用中")
        launchFailure?.let { throw it }
        env = serverEnv
        RunningServer { order += "server.stop" }
    }
    private val registrar = MdnsRegistrar { address: InetAddress, _: Advertisement ->
        registered += address
        object : MdnsRegistration {
            override fun updateTxt(txt: Map<String, String>) {
                txtUpdates += txt
            }

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

/** 設定に端末が 1 台ある状態にする。PcApp の起動の前に呼ぶ。 */
private fun Harness.addPairedDevice() {
    SettingsStore(paths.settingsFile).also { it.load() }.update {
        it.copy(devices = listOf(PairedDevice(DEVICE_ID, "端末", "2026-01-01T00:00:00.000Z")))
    }
}

/** 端末側の手順（shared の関数）で、開いているコードの P5・P6 を済ませる。 */
private fun Harness.completePairing() {
    val pairing = checkNotNull(env).pairing
    val deviceCrypto = PcCrypto()
    val init = startPairing(deviceCrypto, DEVICE_ID, (pairing.view.value as PairingView.Open).code)
    val started = pairing.start(DEVICE_ID, "端末", init.x.encoded)
    check(started is PairingOutcome.Ok)
    val spkiHash = deviceCrypto.sha256(loadOrCreateTls(store, paths.certFile, clock.instant()).spkiDer)
    val confirmation = checkNotNull(confirmPairing(deviceCrypto, init, app.settings.current.pcId, started.value.encoded, spkiHash))
    check(pairing.confirm(DEVICE_ID, confirmation.macD) is PairingOutcome.Ok)
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

    // ---- 画面向けの状態と警告（X5-2、X5-3、X5-4、X5-22） ----

    @Test
    fun `X5-22 起動すると、サーバーを起動した行が画面のログの欄に入る`() = runTest {
        val h = harness()

        h.app.start()
        runCurrent()

        val line = h.app.logLines.value.single { it.module == "server" }
        assertEquals(LogLevel.Info, line.level)
        assertEquals("サーバーを起動した port=47800", line.message)
    }

    @Test
    fun `X5-22 鍵を新しく作った起動で端末があれば、証明書の作り直しの警告が出る`() = runTest {
        val h = harness()
        h.addPairedDevice()

        h.app.start()
        runCurrent()

        assertTrue(Warning.CertificateRegenerated in h.app.state.value.warnings)
    }

    @Test
    fun `X5-22 鍵を新しく作った起動でも端末が無ければ、証明書の作り直しの警告は出ない`() = runTest {
        val h = harness()

        h.app.start()
        runCurrent()

        assertFalse(Warning.CertificateRegenerated in h.app.state.value.warnings)
    }

    @Test
    fun `X5-2 端末を解除して再ペアリングを済ませると、証明書の作り直しの警告が消えたままになる`() = runTest {
        val h = harness()
        h.addPairedDevice()
        h.app.start()
        runCurrent()
        assertTrue(Warning.CertificateRegenerated in h.app.state.value.warnings)

        h.app.unpair(DEVICE_ID)
        h.app.showPairingCode()
        h.completePairing()

        assertEquals(DEVICE_ID, h.app.state.value.device?.deviceId)
        assertFalse(Warning.CertificateRegenerated in h.app.state.value.warnings)
    }

    @Test
    fun `X5-22 設定を読めなかった起動では、退避して初期設定で起動した警告が出て、ログにも残る`() = runTest {
        val h = harness()
        Files.writeString(h.paths.settingsFile, "これは JSON ではない")

        h.app.start()
        runCurrent()

        assertTrue(Warning.SettingsReset in h.app.state.value.warnings)
        assertTrue(Files.exists(h.paths.settingsFile.resolveSibling("settings.json.bad")))
        assertTrue(h.app.logLines.value.any { it.level == LogLevel.Warn && it.module == "settings" })
    }

    @Test
    fun `X5-4 ポートの使用中以外の起動の失敗も、警告を出して待機中と表示せず、ログに理由を残す`() = runTest {
        val h = harness()
        h.launchFailure = IllegalStateException("TLS を組めない")

        h.app.start()
        runCurrent()

        assertEquals(ServerStatus.Failed(ServerFailureKind.Other), h.app.state.value.server)
        assertTrue(Warning.ServerStartFailed in h.app.state.value.warnings)
        assertEquals(TrayStatus.ServerFailed, trayStatus(h.app.state.value))
        val line = h.app.logLines.value.single { it.module == "server" }
        assertEquals(LogLevel.Warn, line.level)
        assertEquals("サーバーを起動できない port=47800 reason=IllegalStateException", line.message)
    }

    @Test
    fun `X5-3 端末がある間は、ペアリングコードを開かず、受付中の広告も出さない`() = runTest {
        val h = harness()
        h.addPairedDevice()
        h.app.start()
        runCurrent()

        h.app.showPairingCode()

        assertEquals(PairingView.Closed, h.app.state.value.pairing)
        assertTrue(h.txtUpdates.isEmpty())
    }

    @Test
    fun `X5-3 端末が無ければ、ペアリングコードを開いて受付中を広告する`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()

        h.app.showPairingCode()

        assertTrue(h.app.state.value.pairing is PairingView.Open)
        assertEquals(listOf("1"), h.txtUpdates.map { it["pair"] })
    }

    // ---- XML の場所の保存（X5-18、X5-9） ----

    @Test
    fun `X5-18 W5 で読める XML の場所を保存すると、その XML を読み直して読めた状態になる`() = runTest {
        val h = harness()
        h.app.start()
        // 既定の場所に XML が無いまま V6 の回数（間隔 1 秒を 2 回）を読んで諦める
        advanceTimeBy(3_500)
        runCurrent()
        assertEquals(XmlStatus.Unreadable(hasResult = false), h.app.state.value.xml)
        val xml = h.root.resolve("lib.xml").also { Files.writeString(it, EMPTY_LIBRARY_XML) }

        assertEquals(XmlPathResult.Saved, h.app.setXmlPath(xml.toString()))
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(XmlStatus.Normal, h.app.state.value.xml)
    }

    @Test
    fun `X5-9 XML の場所が未設定なら、実際に読んでいる既定の場所を返す`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()

        assertEquals(h.root.resolve("none.xml").toString(), h.app.xmlPathInUse)
    }

    @Test
    fun `X5-9 XML の場所を保存した後は、保存した場所を返す`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()
        val xml = h.root.resolve("lib.xml").also { Files.writeString(it, EMPTY_LIBRARY_XML) }
        h.app.setXmlPath(xml.toString())

        assertEquals(xml.toString(), h.app.xmlPathInUse)
    }

    // ---- ログイン時の起動の結線（X5-19） ----

    @Test
    fun `X5-19 ログイン時の起動の切り替えは、Run キーの解除と登録と設定の保存に届く`() = runTest {
        val h = harness()
        h.app.start()
        runCurrent()

        h.app.setLaunchOnLogin(false)
        assertTrue(h.runKey.values.isEmpty())
        assertFalse(h.app.settings.current.launchOnLogin)

        h.app.setLaunchOnLogin(true)
        assertEquals(mapOf("playlist-sync-fox" to "\"C:\\fake\\playlist-sync-fox.exe\" --background"), h.runKey.values)
        assertTrue(h.app.settings.current.launchOnLogin)
    }

    @Test
    fun `X5-19 起動のとき、設定が有効なら Run キーを現在のパスに書き直す`() = runTest {
        val h = harness()
        h.runKey.set("playlist-sync-fox", "\"C:\\old\\app.exe\" --background")

        h.app.start()
        runCurrent()

        assertEquals(mapOf("playlist-sync-fox" to "\"C:\\fake\\playlist-sync-fox.exe\" --background"), h.runKey.values)
    }

    @Test
    fun `X5-19 起動のとき、設定が無効なら Run キーを解除する`() = runTest {
        val h = harness()
        SettingsStore(h.paths.settingsFile).also { it.load() }.update { it.copy(launchOnLogin = false) }
        h.runKey.set("playlist-sync-fox", "\"C:\\old\\app.exe\" --background")

        h.app.start()
        runCurrent()

        assertTrue(h.runKey.values.isEmpty())
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
