package playlistsyncfox.android.sync

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.net.ApiProvider
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.shared.result.SyncTrigger

/** NSD で見つかった PC（X7-2-1 の `DiscoveredPc` のうち到達確認が使う分）。[pcIdPrefix] は TXT `pid`。 */
class LanService(val serviceName: String, val host: String, val port: Int, val pcIdPrefix: String)

/** 同期の到達確認での NSD の引き直し（X6-3 の 2 の 2）。実体は NSD の発見（束 6）。 */
fun interface LanResolver {
    /** 見つかった PC を返す。待ちの上限（V14）は呼び出し側が置く。 */
    suspend fun discover(): List<LanService>
}

/** 診断のログに 1 行書く。鍵、コード、MAC、実 IP は入れない。 */
fun interface SyncLogger {
    fun line(text: String)
}

/** 手動の入力データ（X6-3 の入口）。[rerun] は OS が止めた仕事の走り直し。 */
class RunInput(
    val trigger: SyncTrigger,
    val deleteApproved: Boolean = false,
    val approvedDeleteCount: Int = 0,
    val reachFailed: Boolean = false,
    val rerun: Boolean = false,
)

/** 同期の手順が使う部品。本番は Worker が組み立て、試験は偽物を渡す。 */
class SyncEnvironment(
    val store: SyncStore,
    val writer: MediaWriter,
    val apis: ApiProvider,
    val lan: LanResolver,
    /** T4 の `key_alias` の別名が Keystore にあるか（X6-6）。 */
    val aliasExists: (String) -> Boolean,
    /** 保存先のボリューム名（T7 の `storage_volume`。D16）。 */
    val volume: String,
    /** [volume] が今つながっているか。つながっていなければ同期を始めない。 */
    val volumeAvailable: Boolean,
    /** 保存先の空き。測れなければ null。worker スレッドで呼ぶ。 */
    val freeBytes: suspend () -> Long?,
    /** `setForeground`。起動の制限などで例外になったら偽。 */
    val promote: suspend () -> Boolean,
    /** `manual-sync` が実行中か待機中か。 */
    val manualActive: suspend () -> Boolean,
    /** 終了の通知（X7-3）。T3 に残して終えたときだけ呼ぶ。 */
    val onFinished: (SyncRun, SyncProgress, SyncTrigger) -> Unit,
    val log: SyncLogger,
    val now: () -> Long,
    val progress: MutableStateFlow<SyncProgress?> = MutableStateFlow(null),
    /** 取り消しを受けたときの停止の理由。 */
    val stopCause: () -> StopCause = { StopCause.UserCancel },
    /** 同じプロセスで同時に走るのは 1 つだけ（X6-4）。 */
    val gate: Mutex = ProcessGate,
    val wait: suspend (Long) -> Unit = { delay(it) },
)

val ProcessGate = Mutex()

/** 同期を始められない理由（X6-3 の手順 0）。 */
enum class StartBlock { NoSelection, StorageMissing }

/** 選択が空、または保存先が無いとき、同期を始めない（D15、D16）。画面側も同じ判定を先に行う。 */
suspend fun SyncEnvironment.startBlock(): StartBlock? = when {
    store.selectedPlaylists().isEmpty() -> StartBlock.NoSelection
    !volumeAvailable -> StartBlock.StorageMissing
    else -> null
}
