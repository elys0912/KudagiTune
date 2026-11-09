package playlistsyncfox.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import playlistsyncfox.android.AppGraph
import playlistsyncfox.android.discovery.checkManualEndpoint
import playlistsyncfox.android.log.LogEntry
import playlistsyncfox.android.notify.SyncNotifications
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncServices

/** 画面の遷移は S1 を起点にする（X7-1-1 の 4）。 */
sealed interface Screen {
    data object Home : Screen

    data object Pairing : Screen

    /**
     * [awaitingStart] はこの画面から開始した直後で、ワーカーの最初の進捗を待っている間。
     * [staleFinished] は同期ボタンを押した時点から残っている前の実行の完了で、S4 へ進む判定から外す。
     */
    data class Progress(val awaitingStart: Boolean, val notice: String? = null, val staleFinished: SyncProgress? = null) : Screen

    data class Result(val runId: Long? = null) : Screen

    data object Settings : Screen

    /** S6。[notice] は同期ボタンから案内されたときの一文。 */
    data class Selection(val notice: String? = null) : Screen

    data object Log : Screen

    data object Licenses : Screen
}

/** 画面の状態を持つ。画面は状態を描くだけで、操作はここの関数を呼ぶだけ（X7-1-1 の 3）。 */
class AppViewModel(private val graph: AppGraph) : ViewModel() {
    private val store = graph.store

    /**
     * 権限の拒否の記録。拒否したら、同じ起動の間は再び求めない（X7-4-2 の 4）。
     * 画面の回転などで Activity が作り直されても消えないよう、Activity でなくここに持つ。
     */
    var notificationDenied = false
    var readDenied = false

    private val mutableScreen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = mutableScreen.asStateFlow()

    /** 「消す」の確認の画面を出している間の件数。 */
    private val mutableConfirm = MutableStateFlow<PressOutcome.Confirm?>(null)
    val confirm: StateFlow<PressOutcome.Confirm?> = mutableConfirm.asStateFlow()

    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    /** 同期が動いているか。S1 の状態、S4 の最新の行、S5 の削除の可否が使う。 */
    val syncing: StateFlow<Boolean> = SyncServices.progress
        .map(::isSyncing)
        .stateIn(viewModelScope, SharingStarted.Eagerly, isSyncing(SyncServices.progress.value))

    val home: StateFlow<HomeUiState> = combine(
        store.observePairing(),
        store.observeRuns(),
        store.observePlaylists(),
        store.observeSelectedPlaylists(),
        syncing,
    ) { pairing, runs, playlists, selected, syncing -> homeState(pairing, runs, playlists, selected.size, syncing) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(HomeKind.Unpaired))

    val progress: StateFlow<ProgressUiState?> = SyncServices.progress
        .map { it?.let(::progressUi) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutableResult = MutableStateFlow(ResultScreenState(null, emptyList()))
    val result: StateFlow<ResultScreenState> = mutableResult.asStateFlow()

    private val mutableLogLines = MutableStateFlow<List<String>>(emptyList())
    val logLines: StateFlow<List<String>> = mutableLogLines.asStateFlow()

    /** S3 のログの欄（X7-1-4）。同期のワーカーと同じ `AppLog` の流れ。 */
    val logPane: StateFlow<List<LogEntry>> = graph.log.recent

    val pairing = PairingPresenter(
        scope = viewModelScope,
        store = store,
        gateway = graph.pairingGateway,
        discover = { graph.discovery.discover(onlyPairingOpen = true) },
        checkManual = ::checkManualEndpoint,
    )

    val selection = SelectionPresenter(
        scope = viewModelScope,
        store = store,
        fetch = { withContext(Dispatchers.IO) { graph.fetchPlaylists() } },
        now = System::currentTimeMillis,
    )

    val settings = SettingsPresenter(
        scope = viewModelScope,
        store = store,
        setAutoEnabled = graph.scheduler::setAutoEnabled,
        mountedVolumes = graph.volumes::mounted,
        storageVolume = graph::storageVolume,
        logBytes = graph.log::sizeBytes,
        cleanupWriter = graph::cleanupWriter,
        syncing = { syncing.value },
        unpairDevice = graph.keeper::unpair,
        showMessage = { mutableMessage.value = it },
    )

    private val manualSync = ManualSyncFlow(
        autoRunning = graph.scheduler::autoRunning,
        block = { withContext(Dispatchers.IO) { graph.startBlock() } },
        check = { withContext(Dispatchers.IO) { graph.checkRemoval() } },
        start = { approvedCount, reachFailed ->
            startManualSync(SyncServices.progress) {
                graph.scheduler.startManual(approvedCount != null, approvedCount ?: 0, reachFailed)
            }
        },
    )

    init {
        // 画面の下の知らせは、その画面にいる間だけ出す。移ったら消す
        viewModelScope.launch { screen.drop(1).collect { mutableMessage.value = null } }
        // S2 を離れたら、どの経路で離れても（通知のタップを含む）PC の発見と接続を止める
        viewModelScope.launch { screen.collect { if (it != Screen.Pairing) pairing.close() } }
        // S3 の自動の遷移（finished で S4、動いていなければ S1）
        viewModelScope.launch {
            SyncServices.progress.combine(screen) { progress, current -> progress to current }.collect { (progress, current) ->
                if (current !is Screen.Progress) return@collect
                when (progressNav(progress, current.awaitingStart, current.staleFinished)) {
                    ProgressNav.ToResult -> openResult(null)
                    ProgressNav.ToHome -> mutableScreen.value = Screen.Home
                    ProgressNav.Started -> mutableScreen.value = current.copy(awaitingStart = false)
                    ProgressNav.Stay -> Unit
                }
            }
        }
    }

    fun back() {
        mutableScreen.value = when (screen.value) {
            Screen.Log, Screen.Licenses -> Screen.Settings
            else -> Screen.Home
        }
    }

    /** 通知のタップ。 */
    fun openFromNotification(route: String?) {
        when (route) {
            SyncNotifications.OPEN_PROGRESS -> mutableScreen.value = Screen.Progress(awaitingStart = false)
            SyncNotifications.OPEN_RESULT -> openResult(null)
        }
    }

    fun syncPressed() {
        val shownAtPress = SyncServices.progress.value
        viewModelScope.launch { apply(manualSync.press(paired = store.pairing() != null), shownAtPress) }
    }

    fun answerConfirm(approved: Boolean) {
        val pending = confirm.value ?: return
        mutableConfirm.value = null
        val shownAtPress = SyncServices.progress.value
        viewModelScope.launch { apply(manualSync.answer(pending, approved), shownAtPress) }
    }

    /** [shownAtPress] は同期ボタンを押した時点の進捗。 */
    private fun apply(outcome: PressOutcome, shownAtPress: SyncProgress?) {
        when (outcome) {
            PressOutcome.ToPairing -> {
                mutableScreen.value = Screen.Pairing
                pairing.open()
            }
            is PressOutcome.ToProgress -> {
                val now = SyncServices.progress.value
                val stale = staleFinished(shownAtPress, now)
                mutableScreen.value = Screen.Progress(awaitingStart = now == null || stale != null, notice = outcome.notice, staleFinished = stale)
            }
            is PressOutcome.Confirm -> mutableConfirm.value = outcome
            PressOutcome.ToSelection -> openSelection(NO_SELECTION_NOTICE)
            PressOutcome.StorageMissing -> mutableMessage.value = STORAGE_MISSING_NOTICE
        }
    }

    /** S6 を開き、PC の一覧を取る（P7）。 */
    fun openSelection(notice: String? = null) {
        mutableScreen.value = Screen.Selection(notice)
        selection.load()
    }

    fun showProgress() {
        mutableScreen.value = Screen.Progress(awaitingStart = false)
    }

    /** S3 の「中断」。X6-3 の中断を呼び、`finished` を待って S4 へ進む。 */
    fun cancelSync() {
        graph.scheduler.cancelManual()
    }

    fun openResult(runId: Long?) {
        viewModelScope.launch {
            mutableResult.value = loadResult(store, runId, syncing.value)
            mutableScreen.value = Screen.Result(runId)
        }
    }

    fun openSettings(notificationsAllowed: Boolean, readAccess: ReadAccess) {
        mutableScreen.value = Screen.Settings
        settings.refresh(notificationsAllowed, readAccess)
    }

    fun unpair() {
        viewModelScope.launch {
            if (settings.unpair()) mutableScreen.value = Screen.Home
        }
    }

    fun openLog() {
        viewModelScope.launch {
            mutableLogLines.value = withContext(Dispatchers.IO) { graph.log.tail() }.asReversed()
            mutableScreen.value = Screen.Log
        }
    }

    /** 共有する本文を [share] に渡す。本文は閲覧と同じ切り詰め。 */
    fun shareLog(share: (String) -> Unit) {
        viewModelScope.launch {
            share(withContext(Dispatchers.IO) { graph.log.tail().joinToString("\n") })
        }
    }

    /** 「ファイルに保存する」。本文は全世代の全行。書き込み先は [write] が持ち、失敗は画面の下の知らせに出す。 */
    fun saveLog(write: (String) -> Unit) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { write(graph.log.exportText()) }.isSuccess }
            mutableMessage.value = if (saved) LOG_SAVED_NOTICE else LOG_SAVE_FAILED_NOTICE
        }
    }

    fun clearLog() {
        graph.log.clear()
        mutableLogLines.value = emptyList()
    }

    fun openLicenses() {
        mutableScreen.value = Screen.Licenses
    }
}
