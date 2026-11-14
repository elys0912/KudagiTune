package playlistsyncfox.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.desktop.discovery.Advertiser
import playlistsyncfox.desktop.discovery.MdnsRegistrar
import playlistsyncfox.desktop.vault.KeyRing
import playlistsyncfox.desktop.vault.SecretStore
import playlistsyncfox.desktop.vault.SecretStoreException
import playlistsyncfox.desktop.vault.TlsIdentity
import playlistsyncfox.desktop.vault.loadOrCreateTls
import playlistsyncfox.desktop.server.ActivityTracker
import playlistsyncfox.desktop.server.DeviceRegistry
import playlistsyncfox.desktop.server.ManifestService
import playlistsyncfox.desktop.server.PairingService
import playlistsyncfox.desktop.server.PairingView
import playlistsyncfox.desktop.server.ProgressBoard
import playlistsyncfox.desktop.server.RunningServer
import playlistsyncfox.desktop.server.ServerEnv
import playlistsyncfox.desktop.server.ServerLauncher
import playlistsyncfox.desktop.server.SignatureFailureCounter
import playlistsyncfox.desktop.server.TrackTable
import playlistsyncfox.desktop.settings.AppLog
import playlistsyncfox.desktop.settings.LogLine
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.ui.PcInputs
import playlistsyncfox.desktop.ui.PcState
import playlistsyncfox.desktop.ui.ServerFailureKind
import playlistsyncfox.desktop.ui.ServerStatus
import playlistsyncfox.desktop.ui.TransferSpeedMeter
import playlistsyncfox.desktop.ui.buildPcState
import playlistsyncfox.desktop.watch.DirectoryEventsOpener
import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.desktop.watch.LibraryHolder
import playlistsyncfox.desktop.watch.XmlStatus
import playlistsyncfox.desktop.watch.XmlWatch
import playlistsyncfox.itunes.library.LibraryReadOutcome
import playlistsyncfox.itunes.plist.readLibrary
import playlistsyncfox.shared.auth.NonceStore
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.formatFingerprint
import java.net.BindException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

/** 画面の状態を作り直す間隔。W4 のコードの残り秒と、コードの期限（V3）の反映を 1 秒単位で進める。 */
private const val STATE_REFRESH_MILLIS = 1_000L

/** 終了時に、送信中の接続が終わったかを確かめる間隔。猶予（V46）の間だけ繰り返す。 */
private const val SENDING_POLL_MILLIS = 100L

/** 設定、鍵、証明書、ログの置き場（B4-6）。 */
class AppPaths(val settingsFile: Path, val secretsFile: Path, val certFile: Path, val logDir: Path) {
    companion object {
        fun fromEnvironment(): AppPaths {
            val roaming = Path.of(System.getenv("APPDATA") ?: error("APPDATA が無い")).resolve("playlist-sync-fox")
            val local = Path.of(System.getenv("LOCALAPPDATA") ?: error("LOCALAPPDATA が無い")).resolve("playlist-sync-fox")
            return AppPaths(roaming.resolve("settings.json"), roaming.resolve("secrets.bin"), roaming.resolve("tls-cert.pem"), local.resolve("logs"))
        }
    }
}

/** W5 の XML の場所の変更の結果。 */
sealed interface XmlPathResult {
    data object Saved : XmlPathResult

    /** 読めない。保存しない。 */
    data object Unreadable : XmlPathResult
}

/**
 * 起動と終了の順（X5-6）と、画面の操作の窓口。判断は shared と itunes に任せ、ここは結線だけを持つ。
 * 外の世界（サーバー、mDNS、レジストリ、ファイルの監視、鍵の保管）は引数で受け、試験では偽物に差し替える。
 */
class PcApp(
    private val paths: AppPaths,
    private val defaultXmlPath: Path,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val crypto: CryptoProvider,
    private val secretStore: SecretStore,
    private val serverLauncher: ServerLauncher,
    private val registrar: MdnsRegistrar,
    private val enumerateAddresses: () -> List<InetAddress>,
    private val watchOpener: DirectoryEventsOpener,
    private val autostart: Autostart,
) {
    val log = AppLog(paths.logDir, clock)
    val settings = SettingsStore(paths.settingsFile)

    private val keyRing = KeyRing(secretStore)
    private val registry = DeviceRegistry(keyRing, settings, clock)
    private val holder = LibraryHolder(clock) { settings.current.staleDays }
    private val activity = ActivityTracker(clock)
    private val progress = ProgressBoard(clock)
    private val speedMeter = TransferSpeedMeter()
    private val tracks = TrackTable()
    private var pairing: PairingService? = null
    private var tls: TlsIdentity? = null
    private var running: RunningServer? = null
    private var ticker: Job? = null
    private var serverStatus: ServerStatus = ServerStatus.NotStarted
    private var settingsReset = false
    private var pcAdvertiser: Advertiser? = null

    // 書くスレッドと読むスレッドが違うので @Volatile にする
    // xmlWatch：EDT（W5 の保存）と main（起動）が書き、Netty（要求の処理）が読む
    @Volatile
    private var xmlWatch: XmlWatch? = null

    // certificateRegenerated：main（起動）と Netty（P6 の成功）が書き、EDT（画面）と Default（ticker）が読む
    @Volatile
    private var certificateRegenerated = false

    private val stateFlow = MutableStateFlow(snapshot())

    /** 画面が写す「PC の状態」。 */
    val state: StateFlow<PcState> = stateFlow

    /** ログの欄の行。行のたびに「PC の状態」を作り直さないよう、別の購読にする（X5-5）。 */
    val logLines: StateFlow<List<LogLine>> = log.lines

    /** 初回起動の印。W1 が W4 を自動で開く。 */
    var firstRun = false
        private set

    /** 実際に読んでいる XML の場所。未設定なら既定の場所。W5 の欄に出す。 */
    val xmlPathInUse: String get() = currentXmlPath().toString()

    // 起動の順 2〜7（X5-6）。1（二重起動の防止）と 8（トレイ）は呼び出し側
    fun start() {
        val load = settings.load()
        firstRun = load.firstRun
        settingsReset = load.rescuedAs != null
        if (settingsReset) log.warn("settings", "settings.json を読めず、退避して既定で起動した")
        applyAutostart(settings.current.launchOnLogin)

        val identity = loadKeys()
        startXmlWatch(currentXmlPath())
        if (identity != null) startServer(identity)

        ticker = scope.launch {
            while (isActive) {
                pairing?.refresh()
                refreshState()
                delay(STATE_REFRESH_MILLIS)
            }
        }
    }

    // 鍵の保管に失敗したらサーバーを起動しない（平文のファイルへ逃がさない。N1-4）
    private fun loadKeys(): TlsIdentity? = try {
        val identity = loadOrCreateTls(secretStore, paths.certFile, clock.instant())
        keyRing.load(settings.current.devices.map { it.deviceId })
        certificateRegenerated = identity.keyIsNew
        tls = identity
        identity
    } catch (_: SecretStoreException) {
        serverStatus = ServerStatus.Failed(ServerFailureKind.KeyStorage)
        log.error("secrets", "鍵の保管を復号できない。サーバーを起動しない")
        null
    } catch (e: Exception) {
        serverStatus = ServerStatus.Failed(ServerFailureKind.Other)
        log.error("secrets", "鍵と証明書を用意できない：${e.javaClass.simpleName}")
        null
    }

    private fun startServer(identity: TlsIdentity) {
        val service = PairingService(
            crypto, clock, settings.current.pcId, crypto.sha256(identity.spkiDer), settings, registry,
            onViewChanged = { pcAdvertiser?.setPair(pairing?.view?.value is PairingView.Open); refreshState() },
            // 端末が新しい証明書でペアリングし直したので、作り直しの警告は要らない
            onPaired = { certificateRegenerated = false; refreshState() },
        )
        pairing = service
        val env = ServerEnv(
            crypto, clock, clock.instant(), NonceStore(), keyRing, SignatureFailureCounter(clock), service,
            settings.current.pcId, settings, ManifestService(crypto, clock, settings, tracks, ::libraryForRequest),
            tracks, activity, progress, log,
        )
        launchServer(identity, env)
    }

    // ポートの使用中は警告を出して続行する。サーバーは止まった状態になる
    private fun launchServer(identity: TlsIdentity, env: ServerEnv) {
        val port = settings.current.port
        try {
            running = serverLauncher.start(port, identity, env)
            serverStatus = ServerStatus.Running
            log.info("server", "サーバーを起動した port=$port")
            val advertiser = Advertiser(scope, settings.current.pcId, enumerateAddresses, registrar)
            advertiser.start(port, pair = false)
            pcAdvertiser = advertiser
        } catch (e: Exception) {
            val inUse = generateSequence<Throwable>(e) { it.cause }.any { it is BindException }
            serverStatus = ServerStatus.Failed(if (inUse) ServerFailureKind.PortInUse else ServerFailureKind.Other)
            log.warn("server", "サーバーを起動できない port=$port reason=${if (inUse) "in_use" else e.javaClass.simpleName}")
        }
        refreshState()
    }

    // 未設定のときは既定の場所を読む
    private fun currentXmlPath(): Path = settings.current.xmlPath?.let(Path::of) ?: defaultXmlPath

    private fun startXmlWatch(path: Path) {
        xmlWatch?.stop()
        xmlWatch = XmlWatch(scope, path, holder, watchOpener, log).also { it.start() }
    }

    // 読めない状態の要求には null を返し、サーバーが E4 で応える。最後に読めた結果で応えない
    private suspend fun libraryForRequest(): HeldLibrary? {
        xmlWatch?.refreshForRequest()
        return holder.held.value.takeIf { holder.status.value !is XmlStatus.Unreadable }
    }

    private fun applyAutostart(enabled: Boolean) {
        try {
            autostart.apply(enabled)
        } catch (e: Exception) {
            log.warn("autostart", "ログイン時の起動を更新できない：${e.javaClass.simpleName}")
        }
    }

    // ---- 画面の操作 ----

    fun setLaunchOnLogin(enabled: Boolean) {
        settings.update { it.copy(launchOnLogin = enabled) }
        applyAutostart(enabled)
    }

    /** 読めれば保存して読み直す。読めなければ保存しない。 */
    fun setXmlPath(text: String): XmlPathResult {
        val path = try {
            Path.of(text)
        } catch (_: java.nio.file.InvalidPathException) {
            return XmlPathResult.Unreadable
        }
        val readable = try {
            readLibrary(Files.readAllBytes(path)) is LibraryReadOutcome.Read
        } catch (_: java.io.IOException) {
            false
        }
        if (!readable) return XmlPathResult.Unreadable
        settings.update { it.copy(xmlPath = text) }
        startXmlWatch(path)
        return XmlPathResult.Saved
    }

    /** 端末がある間は開かない。受付中（`pair=1`）を広告して、P5 が常に 403 になる状態を作らないため。 */
    fun showPairingCode() {
        if (settings.current.devices.isNotEmpty()) return
        pairing?.open()
    }

    fun closePairing() {
        pairing?.close()
    }

    fun unpair(deviceId: String) {
        registry.unpair(deviceId)
        refreshState()
    }

    // 終了の順 2〜6（X5-6）。1（同期中の確認）は呼び出し側
    suspend fun stop() {
        ticker?.cancel()
        pairing?.close()
        pcAdvertiser?.stop()
        // 送信中の接続は、猶予（V46）の後に切る
        withTimeoutOrNull(DesktopValues.SHUTDOWN_GRACE.toMillis()) {
            while (activity.sendingCount > 0) delay(SENDING_POLL_MILLIS)
        }
        running?.stop()
        running = null
        xmlWatch?.stop()
        // 設定は更新のたびに書き込み済みなので、残りは無い
    }

    /** W1 の確認の判定に使う。 */
    fun isSyncing(): Boolean = activity.isSyncing(clock.instant())

    private fun refreshState() {
        stateFlow.value = snapshot()
    }

    private fun snapshot(): PcState {
        val advertised = pcAdvertiser?.addresses?.value.orEmpty()
        val now = clock.instant()
        return buildPcState(
            PcInputs(
                server = serverStatus,
                xml = holder.status.value,
                settings = settings.current,
                keyMissing = keyRing.missing,
                pairing = pairing?.view?.value ?: PairingView.Closed,
                activity = activity,
                progress = progress,
                certificateRegenerated = certificateRegenerated,
                settingsReset = settingsReset,
                endpoints = advertised.map { "${it.hostAddress}:${settings.current.port}" },
                fingerprint = tls?.let { formatFingerprint(crypto, it.spkiDer) },
                speed = speedMeter.sample(now, activity.totalSentBytes, activity.isSyncing(now)),
            ),
            now,
        )
    }
}
