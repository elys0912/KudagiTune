package playlistsyncfox.android

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.provider.Settings
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import playlistsyncfox.android.log.logFileName
import playlistsyncfox.android.notify.SyncNotifications
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.ui.AppScreen
import playlistsyncfox.android.ui.AppViewModel
import playlistsyncfox.android.ui.DarkColors
import playlistsyncfox.android.ui.LICENSE_TEXT_DIR
import playlistsyncfox.android.ui.LightColors
import playlistsyncfox.android.ui.NOTICE_DIR
import playlistsyncfox.android.ui.PermissionStep
import playlistsyncfox.android.ui.ReadAccess
import playlistsyncfox.android.ui.Screen
import playlistsyncfox.android.ui.ScreenActions
import playlistsyncfox.android.ui.parseLicenses
import playlistsyncfox.android.ui.permissionStep

class MainActivity : ComponentActivity() {
    private lateinit var model: AppViewModel

    private val prefs by lazy { getSharedPreferences("permissions", MODE_PRIVATE) }

    private val notificationLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.notificationDenied = true
        // 拒否でも同期は続ける
        model.syncPressed()
    }

    private val readLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) model.readDenied = true
        model.settings.refresh(notificationsAllowed(), if (granted) ReadAccess.Granted else ReadAccess.Denied)
    }

    private val saveLogLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) model.saveLog { text -> writeText(uri, text) }
    }

    private val deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        model.settings.refresh(notificationsAllowed(), readAccess())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = (application as PsfoxApp).graph
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(graph) as T
        })[AppViewModel::class.java]
        if (savedInstanceState == null) model.openFromNotification(intent.getStringExtra(SyncNotifications.EXTRA_OPEN))

        val actions = ScreenActions(
            onSyncButton = ::onSyncButton,
            onOpenSettings = { model.openSettings(notificationsAllowed(), readAccess()) },
            onNotificationHelp = ::openNotificationSettings,
            onRequestRead = ::requestRead,
            onDeleteLeftovers = ::deleteLeftovers,
            onShareLog = ::shareLog,
            onSaveLog = { saveLogLauncher.launch(logFileName(System.currentTimeMillis())) },
            onOpenStorageSettings = { startActivity(Intent(StorageManager.ACTION_MANAGE_STORAGE)) },
            licenses = {
                // 資産が読めなければ null（「読み込めない」を出す。アプリは止めない）
                readAsset("artifacts.json")?.let(::parseLicenses)
            },
            licenseText = { file -> readAsset("$LICENSE_TEXT_DIR/$file") },
            licenseNotice = { file -> readAsset("$NOTICE_DIR/$file") },
        )
        setContent {
            val screen by model.screen.collectAsState()
            // ダークのとき白い地が出ないよう、窓の地の色とステータスバーの文字色を配色に合わせる（D20）
            val dark = isSystemInDarkTheme()
            SideEffect { applySystemBars(dark) }
            BackHandler(enabled = screen != Screen.Home) { model.back() }
            AppScreen(model, actions)
        }
    }

    /** 資産の [path] を文字列で読む。無い・読めないときは null。 */
    private fun readAsset(path: String): String? =
        runCatching { assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()

    private fun applySystemBars(dark: Boolean) {
        window.setBackgroundDrawable(ColorDrawable((if (dark) DarkColors else LightColors).background.toArgb()))
        val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (dark) 0 else light, light)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        model.openFromNotification(intent.getStringExtra(SyncNotifications.EXTRA_OPEN))
    }

    private fun onSyncButton() = model.syncPressed(askNotification = ::askNotificationPermission)

    /**
     * 同期が始まると決まった後に、通知の許可を確かめる。拒否でも同期は続ける。
     * 許可の画面を出したときは true（同期は結果の後に始める）。
     */
    private fun askNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val permission = Manifest.permission.POST_NOTIFICATIONS
        val step = permissionStep(
            granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED,
            deniedThisLaunch = model.notificationDenied,
            blockedByOs = prefs.getBoolean(KEY_ASKED_NOTIFICATION, false) && !shouldShowRequestPermissionRationale(permission),
        )
        return when (step) {
            PermissionStep.Request -> {
                prefs.edit().putBoolean(KEY_ASKED_NOTIFICATION, true).apply()
                notificationLauncher.launch(permission)
                true
            }
            // 直後に S3 へ移っても消えないよう、画面の知らせでなくトーストで出す（X7-1）
            PermissionStep.GuideToSettings -> {
                Toast.makeText(this, "通知が出ません。出すには設定アプリで通知を許可してください", Toast.LENGTH_LONG).show()
                false
            }
            PermissionStep.Granted, PermissionStep.SkipDenied -> false
        }
    }

    private fun notificationsAllowed(): Boolean = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun readPermission() =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun readGranted() = checkSelfPermission(readPermission()) == PackageManager.PERMISSION_GRANTED

    private fun readAccess() = if (readGranted()) ReadAccess.Granted else ReadAccess.Requestable

    /** S5 の「以前の曲の整理」を開いたときの読み取りの権限。拒否なら項目を閉じる。 */
    private fun requestRead() {
        val permission = readPermission()
        val step = permissionStep(
            granted = readGranted(),
            deniedThisLaunch = model.readDenied,
            blockedByOs = prefs.getBoolean(KEY_ASKED_READ, false) && !shouldShowRequestPermissionRationale(permission),
        )
        when (step) {
            PermissionStep.Request -> {
                prefs.edit().putBoolean(KEY_ASKED_READ, true).apply()
                readLauncher.launch(permission)
            }
            PermissionStep.Granted -> model.settings.refresh(notificationsAllowed(), ReadAccess.Granted)
            PermissionStep.GuideToSettings, PermissionStep.SkipDenied -> model.settings.refresh(notificationsAllowed(), ReadAccess.Denied)
        }
    }

    /** 削除の同意の画面は OS が出す（J32）。 */
    private fun deleteLeftovers(files: List<StoredFile>) {
        if (files.isEmpty()) return
        val sender = MediaStore.createDeleteRequest(contentResolver, files.map { Uri.parse(it.uri) }).intentSender
        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
    }

    /** 利用者の操作による共有だけ。テキストで渡し、ファイルの共有の仕組みは持たない（X7-5-2）。 */
    private fun shareLog(text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, null))
    }

    /** 「ファイルに保存する」で利用者が選んだ場所へ書く。開けなければ例外にして、画面に失敗を出す。権限は要らない（保存先の選択が許可になる）。 */
    private fun writeText(uri: Uri, text: String) {
        val output = contentResolver.openOutputStream(uri, "wt") ?: error("保存先を開けない")
        output.use { it.write(text.toByteArray()) }
    }

    private companion object {
        const val KEY_ASKED_NOTIFICATION = "asked_notification"
        const val KEY_ASKED_READ = "asked_read"
    }
}
