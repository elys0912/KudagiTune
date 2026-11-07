package playlistsyncfox.android

import android.app.Application
import android.content.Context
import android.os.storage.StorageManager
import androidx.room.Room
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import playlistsyncfox.android.db.AppDatabase
import playlistsyncfox.android.db.RoomSyncStore
import playlistsyncfox.android.discovery.NsdLanResolver
import playlistsyncfox.android.discovery.PcDiscovery
import playlistsyncfox.android.log.AppLog
import playlistsyncfox.android.net.ApiProvider
import playlistsyncfox.android.net.PinnedApiProvider
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.ResolverMediaStoreFiles
import playlistsyncfox.android.sync.KeyVault
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.sync.RemovalCheck
import playlistsyncfox.android.sync.StopCause
import playlistsyncfox.android.sync.SyncPreview
import playlistsyncfox.android.sync.WorkerHooks
import playlistsyncfox.android.sync.SyncEnvironment
import playlistsyncfox.android.sync.SyncEnvironmentFactory
import playlistsyncfox.android.sync.SyncScheduler
import playlistsyncfox.android.sync.SyncServices
import playlistsyncfox.android.ui.PairingGateway
import playlistsyncfox.android.ui.UnavailablePairingGateway
import playlistsyncfox.shared.crypto.CryptoProvider
import java.io.File

class PsfoxApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        // 鍵の実体（K3）が無いうちは同期の環境を登録しない。ワーカーは何もせず終わる
        SyncServices.factory = graph.environmentFactory()
    }
}

/** 部品の組み立て（本番）。鍵の実体（android.keystore。K3）は未実装で、[crypto] と [vault] が揃うまで同期もペアリングも動かない。 */
class AppGraph(val application: Application) {
    private val app = application
    val store = RoomSyncStore(Room.databaseBuilder(app, AppDatabase::class.java, "playlist-sync-fox.db").build().syncDao())
    val log = AppLog(File(app.filesDir, "logs"), enabled = { true }, now = System::currentTimeMillis)
    val discovery = PcDiscovery(app)
    val scheduler = SyncScheduler(WorkManager.getInstance(app), store)

    /** K3 の実体。未実装。 */
    val crypto: CryptoProvider? = null
    val vault: KeyVault? = null

    val keeper: PairingKeeper? = vault?.let { PairingKeeper(store, it, System::currentTimeMillis) }

    /** P5・P6 の実体も鍵の実体に依存するので未接続。 */
    val pairingGateway: PairingGateway = UnavailablePairingGateway

    /** 手動の同期の前に、画面側で P1 と差分から「消す」の確認の要否を得る。環境が無ければ確認なしで起動する。 */
    suspend fun checkRemoval(): RemovalCheck {
        val factory = environmentFactory() ?: return RemovalCheck.Failed(reachFailed = false)
        val idle = WorkerHooks(promote = { true }, stopCause = { StopCause.UserCancel }, manualActive = { false }, notifyFinished = { _, _, _ -> })
        return SyncPreview(factory.create(app, idle)).check()
    }

    fun environmentFactory(): SyncEnvironmentFactory? {
        val crypto = crypto ?: return null
        val vault = vault ?: return null
        return SyncEnvironmentFactory { context, hooks -> environment(context, hooks, crypto, vault) }
    }

    private fun environment(context: Context, hooks: WorkerHooks, crypto: CryptoProvider, vault: KeyVault): SyncEnvironment {
        val pairing = runBlocking(Dispatchers.IO) { store.pairing() }
        val apis = pairing?.let { PinnedApiProvider(it.spkiHash, it.keyAlias, it.deviceId, crypto, nowSeconds = { System.currentTimeMillis() / 1000 }) }
            ?: ApiProvider { error("ペアリングが無い") }
        val storage = context.getSystemService(StorageManager::class.java)
        return SyncEnvironment(
            store = store,
            writer = MediaWriter(store, ResolverMediaStoreFiles(context), pairing?.deviceId.orEmpty(), System::currentTimeMillis),
            apis = apis,
            lan = NsdLanResolver(discovery),
            aliasExists = { vault.exists(it) },
            freeBytes = { runCatching { storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) }.getOrNull() },
            promote = hooks.promote,
            manualActive = hooks.manualActive,
            onFinished = hooks.notifyFinished,
            log = log.asSyncLogger(),
            now = System::currentTimeMillis,
            progress = SyncServices.progress,
            stopCause = hooks.stopCause,
        )
    }
}
