package playlistsyncfox.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
import playlistsyncfox.android.sync.SyncServices

const val NO_SELECTION_NOTICE = "同期するプレイリストを選んでください"
const val STORAGE_MISSING_NOTICE = "保存先の SD カードが見つかりません。SD カードを確認するか、設定で保存先を変更してください"
const val STORAGE_CHANGED_NOTICE = "保存先を変更しました。次の同期で、新しい保存先へ全曲を転送し直します"
const val STORAGE_LEFTOVER_NOTICE = "前の場所に曲が残っています。次の同期の前に、「前の保存先の曲を削除する」で、先に前の場所の曲を消してください"
const val LOG_SAVED_NOTICE = "ログをファイルに保存しました"
const val LOG_SAVE_FAILED_NOTICE = "ログを保存できませんでした"

/** 画面の遷移は S1 を起点にする（X7-1-1 の 4）。 */
sealed interface Screen {
    data object Home : Screen

    data object Pairing : Screen

    /** [awaitingStart] はこの画面から開始した直後で、ワーカーの最初の進捗を待っている間。 */
    data class Progress(val awaitingStart: Boolean, val notice: String? = null) : Screen

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

    val screen = MutableStateFlow<Screen>(Screen.Home)

    /** 「消す」の確認の画面を出している間の件数。 */
    val confirm = MutableStateFlow<PressOutcome.Confirm?>(null)
    val message = MutableStateFlow<String?>(null)

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

    val result = MutableStateFlow(ResultScreen(null, emptyList()))
    val logLines = MutableStateFlow<List<String>>(emptyList())

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
        showMessage = { message.value = it },
    )

    private val manualSync = ManualSyncFlow(
        autoRunning = graph.scheduler::autoRunning,
        block = { withContext(Dispatchers.IO) { graph.startBlock() } },
        check = { withContext(Dispatchers.IO) { graph.checkRemoval() } },
        start = { approvedCount, reachFailed ->
            // 前回の終了の状態が残っていると、開始した直後に S4 へ進んでしまう
            SyncServices.progress.value = null
            graph.scheduler.startManual(approvedCount != null, approvedCount ?: 0, reachFailed)
        },
    )

    init {
        // 画面の下の知らせは、その画面にいる間だけ出す。移ったら消す
        viewModelScope.launch { screen.drop(1).collect { message.value = null } }
        // S3 の自動の遷移（finished で S4、動いていなければ S1）
        viewModelScope.launch {
            SyncServices.progress.combine(screen) { progress, current -> progress to current }.collect { (progress, current) ->
                if (current !is Screen.Progress) return@collect
                when (progressNav(progress, current.awaitingStart)) {
                    ProgressNav.ToResult -> openResult(null)
                    ProgressNav.ToHome -> screen.value = Screen.Home
                    ProgressNav.Started -> screen.value = current.copy(awaitingStart = false)
                    ProgressNav.Stay -> Unit
                }
            }
        }
    }

    fun back() {
        if (screen.value == Screen.Pairing) pairing.close()
        screen.value = when (screen.value) {
            Screen.Log, Screen.Licenses -> Screen.Settings
            else -> Screen.Home
        }
    }

    /** 通知のタップ。 */
    fun openFromNotification(route: String?) {
        when (route) {
            SyncNotifications.OPEN_PROGRESS -> screen.value = Screen.Progress(awaitingStart = false)
            SyncNotifications.OPEN_RESULT -> openResult(null)
        }
    }

    fun syncPressed() {
        viewModelScope.launch { apply(manualSync.press(paired = store.pairing() != null)) }
    }

    fun answerConfirm(approved: Boolean) {
        val pending = confirm.value ?: return
        confirm.value = null
        viewModelScope.launch { apply(manualSync.answer(pending, approved)) }
    }

    private fun apply(outcome: PressOutcome) {
        when (outcome) {
            PressOutcome.ToPairing -> {
                screen.value = Screen.Pairing
                pairing.open()
            }
            is PressOutcome.ToProgress -> screen.value = Screen.Progress(awaitingStart = SyncServices.progress.value == null, notice = outcome.notice)
            is PressOutcome.Confirm -> confirm.value = outcome
            PressOutcome.ToSelection -> openSelection(NO_SELECTION_NOTICE)
            PressOutcome.StorageMissing -> message.value = STORAGE_MISSING_NOTICE
        }
    }

    /** S6 を開き、PC の一覧を取る（P7）。 */
    fun openSelection(notice: String? = null) {
        screen.value = Screen.Selection(notice)
        selection.load()
    }

    fun showProgress() {
        screen.value = Screen.Progress(awaitingStart = false)
    }

    /** S3 の「中断」。X6-3 の中断を呼び、`finished` を待って S4 へ進む。 */
    fun cancelSync() {
        graph.scheduler.cancelManual()
    }

    fun openResult(runId: Long?) {
        viewModelScope.launch {
            result.value = loadResult(store, runId, syncing.value)
            screen.value = Screen.Result(runId)
        }
    }

    fun openSettings(notificationsAllowed: Boolean, readGranted: Boolean) {
        screen.value = Screen.Settings
        settings.refresh(notificationsAllowed, readGranted)
    }

    fun unpair() {
        viewModelScope.launch {
            if (settings.unpair()) screen.value = Screen.Home
        }
    }

    fun openLog() {
        logLines.value = graph.log.tail().asReversed()
        screen.value = Screen.Log
    }

    /** 共有する本文。閲覧と同じ切り詰め。 */
    fun logText(): String = graph.log.tail().joinToString("\n")

    /** 「ファイルに保存する」。本文は全世代の全行。書き込み先は [write] が持ち、失敗は画面の下の知らせに出す。 */
    fun saveLog(write: (String) -> Unit) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { runCatching { write(graph.log.exportText()) }.isSuccess }
            message.value = if (saved) LOG_SAVED_NOTICE else LOG_SAVE_FAILED_NOTICE
        }
    }

    fun clearLog() {
        graph.log.clear()
        logLines.value = emptyList()
    }

    fun openLicenses() {
        screen.value = Screen.Licenses
    }
}
