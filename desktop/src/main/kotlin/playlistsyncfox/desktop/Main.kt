package playlistsyncfox.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.discovery.JmdnsRegistrar
import playlistsyncfox.desktop.discovery.listIpv4Addresses
import playlistsyncfox.desktop.vault.DpapiCipher
import playlistsyncfox.desktop.vault.FileSecretStore
import playlistsyncfox.desktop.server.NettyServerLauncher
import playlistsyncfox.desktop.ui.MainWindowContent
import playlistsyncfox.desktop.ui.TrayStatus
import playlistsyncfox.desktop.ui.trayStatus
import playlistsyncfox.desktop.ui.trayText
import playlistsyncfox.desktop.watch.WatchServiceOpener
import java.awt.Desktop
import java.awt.image.BufferedImage
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import kotlin.system.exitProcess

private const val OPEN_SIGNAL_FILE = "open-window.signal"
private const val LOCK_FILE = "app.lock"

fun main(args: Array<String>) {
    val paths = AppPaths.fromEnvironment()
    Files.createDirectories(paths.settingsFile.parent)

    // 二重起動を防ぐ。2 つ目は 1 つ目に「窓を開く」印を残して終わる
    val lock = FileChannel.open(paths.settingsFile.resolveSibling(LOCK_FILE), StandardOpenOption.CREATE, StandardOpenOption.WRITE).tryLock()
    if (lock == null) {
        Files.writeString(paths.settingsFile.resolveSibling(OPEN_SIGNAL_FILE), "open")
        exitProcess(0)
    }

    // 配布物の起動だけ jpackage がパスを渡す。開発の実行で Java 本体をログイン時の起動に登録しない
    val packagedPath: String? = System.getProperty("jpackage.app-path")
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val app = PcApp(
        paths = paths,
        defaultXmlPath = Path.of(System.getProperty("user.home"), "Music", "iTunes", "iTunes Music Library.xml"),
        clock = Clock.systemUTC(),
        scope = scope,
        crypto = PcCrypto(),
        secretStore = FileSecretStore(paths.secretsFile, DpapiCipher()),
        serverLauncher = NettyServerLauncher(),
        registrar = JmdnsRegistrar(),
        enumerateAddresses = ::listIpv4Addresses,
        watchOpener = WatchServiceOpener(),
        autostart = Autostart(if (packagedPath != null) WindowsRunKey() else NoRunKey) { packagedPath.orEmpty() },
    )
    app.start()

    val startInBackground = "--background" in args
    application {
        val state by app.state.collectAsState()
        var windowOpen by remember { mutableStateOf(!startInBackground || app.firstRun) }
        var confirmExit by remember { mutableStateOf(false) }

        // 2 つ目の起動の印を見て、窓を開く
        remember {
            scope.launchSignalWatcher(paths.settingsFile.resolveSibling(OPEN_SIGNAL_FILE)) { windowOpen = true }
        }

        fun requestExit() {
            if (app.isSyncing()) confirmExit = true else shutdown(app, ::exitApplication)
        }

        val icon = remember { BitmapPainter(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).toComposeImageBitmap()) }
        Tray(
            icon = icon,
            tooltip = trayText(trayStatus(state)).let { if (state.warnings.isNotEmpty()) "$it（警告あり）" else it },
            onAction = { windowOpen = true },
        ) {
            Item("開く") { windowOpen = true }
            Item("ペアリングコードを表示") {
                app.showPairingCode()
                windowOpen = true
            }
            Item("設定") { windowOpen = true }
            Item("ログの場所を開く") { Desktop.getDesktop().open(paths.logDir.toFile()) }
            Item("終了") { requestExit() }
        }

        if (windowOpen) {
            Window(
                onCloseRequest = {
                    // 窓を閉じるとコードを無効にする
                    app.closePairing()
                    windowOpen = false
                },
                title = "playlist-sync-fox",
            ) {
                MainWindowContent(app, state)
            }
        }
        if (confirmExit) {
            AlertDialog(
                onDismissRequest = { confirmExit = false },
                title = { Text("同期中です") },
                text = { Text("終了すると同期が途中で止まる。続けて終了する？") },
                confirmButton = { Button(onClick = { shutdown(app, ::exitApplication) }) { Text("終了する") } },
                dismissButton = { Button(onClick = { confirmExit = false }) { Text("やめる") } },
            )
        }
    }
}

private fun shutdown(app: PcApp, exit: () -> Unit) {
    runBlocking { app.stop() }
    exit()
}

private fun CoroutineScope.launchSignalWatcher(signal: Path, onSignal: () -> Unit) = launch {
    while (true) {
        if (Files.deleteIfExists(signal)) onSignal()
        delay(1_000)
    }
}
