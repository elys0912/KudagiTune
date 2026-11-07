package playlistsyncfox.android

import android.app.Application
import android.content.Context
import android.os.Build
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
import playlistsyncfox.android.net.PairingClient
import playlistsyncfox.android.net.PinnedApiProvider
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.ResolverMediaStoreFiles
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.sync.RemovalCheck
import playlistsyncfox.android.sync.StopCause
import playlistsyncfox.android.sync.SyncPreview
import playlistsyncfox.android.sync.WorkerHooks
import playlistsyncfox.android.sync.SyncEnvironment
import playlistsyncfox.android.sync.SyncEnvironmentFactory
import playlistsyncfox.android.sync.SyncScheduler
import playlistsyncfox.android.sync.SyncServices
import playlistsyncfox.android.ui.DevicePairingGateway
import playlistsyncfox.android.ui.PairingGateway
import playlistsyncfox.android.vault.DeviceCrypto
import playlistsyncfox.android.vault.DeviceVault
import playlistsyncfox.shared.crypto.CryptoProvider
import java.io.File

class PsfoxApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        SyncServices.factory = graph.environmentFactory()
    }
}

/** 部品の組み立て（本番）。 */
class AppGraph(val application: Application) {
    private val app = application
    val store = RoomSyncStore(Room.databaseBuilder(app, AppDatabase::class.java, "playlist-sync-fox.db").build().syncDao())
    val log = AppLog(File(app.filesDir, "logs"), enabled = { true }, now = System::currentTimeMillis)
    val discovery = PcDiscovery(app)
    val scheduler = SyncScheduler(WorkManager.getInstance(app), store)

    val vault = DeviceVault()
    val crypto: CryptoProvider = DeviceCrypto(aliasHmac = vault::hmac)
    val keeper = PairingKeeper(store, vault, System::currentTimeMillis)
    val pairingGateway: PairingGateway = DevicePairingGateway(store, keeper, crypto, Build.MODEL) { host, port -> PairingClient.open(host, port) }

    /** 手動の同期の前に、画面側で P1 と差分から「消す」の確認の要否を得る。 */
    suspend fun checkRemoval(): RemovalCheck {
        val factory = environmentFactory()
        val idle = WorkerHooks(promote = { true }, stopCause = { StopCause.UserCancel }, manualActive = { false }, notifyFinished = { _, _, _ -> })
        return SyncPreview(factory.create(app, idle)).check()
    }

    fun environmentFactory() = SyncEnvironmentFactory { context, hooks -> environment(context, hooks) }

    private fun environment(context: Context, hooks: WorkerHooks): SyncEnvironment {
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
