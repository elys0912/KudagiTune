package playlistsyncfox.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import playlistsyncfox.android.AppGraph
import playlistsyncfox.android.discovery.checkManualEndpoint
import playlistsyncfox.android.notify.SyncNotifications
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.ResolverMediaStoreFiles
import playlistsyncfox.android.sync.SyncServices
import playlistsyncfox.android.sync.SyncStage

/** 画面の遷移は S1 を起点にする（X7-1-1 の 4）。 */
sealed interface Screen {
    data object Home : Screen

    data object Pairing : Screen

    /** [awaitingStart] はこの画面から開始した直後で、ワーカーの最初の進捗を待っている間。 */
    data class Progress(val awaitingStart: Boolean, val notice: String? = null) : Screen

    data class Result(val runId: Long? = null) : Screen

    data object Settings : Screen

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

    val home: StateFlow<HomeUiState> = combine(
        store.observePairing(),
        store.observeRuns(),
        store.observePlaylists(),
        SyncServices.progress,
    ) { pairing, runs, playlists, progress -> homeState(pairing, runs, playlists, isSyncing(progress)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState(HomeKind.Unpaired))

    val progress: StateFlow<ProgressUiState?> = SyncServices.progress
        .combine(screen) { progress, _ -> progress?.let(::progressUi) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val result = MutableStateFlow(ResultScreen(null, emptyList()))
    val settings = MutableStateFlow<SettingsUiState?>(null)
    val logLines = MutableStateFlow<List<String>>(emptyList())

    val pairing = PairingPresenter(
        scope = viewModelScope,
        store = store,
        gateway = graph.pairingGateway,
        discover = { graph.discovery.discover(onlyPairingOpen = true) },
        checkManual = ::checkManualEndpoint,
    )

    private val manualSync = ManualSyncFlow(
        autoRunning = graph.scheduler::autoRunning,
        check = { withContext(Dispatchers.IO) { graph.checkRemoval() } },
        start = { approvedCount, reachFailed ->
            // 前回の終了の状態が残っていると、開始した直後に S4 へ進んでしまう
            SyncServices.progress.value = null
            graph.scheduler.startManual(approvedCount != null, approvedCount ?: 0, reachFailed)
        },
    )

    init {
        // S3 の自動の遷移（finished で S4、動いていなければ S1）
        viewModelScope.launch {
            SyncServices.progress.combine(screen) { progress, current -> progress to current }.collect { (progress, current) ->
                if (current !is Screen.Progress) return@collect
                when (progressNav(progress, current.awaitingStart)) {
                    ProgressNav.ToResult -> openResult(null)
                    ProgressNav.ToHome -> screen.value = Screen.Home
                    ProgressNav.Stay ->
                        if (current.awaitingStart && progress != null && progress.stage != SyncStage.Finished) {
                            screen.value = current.copy(awaitingStart = false)
                        }
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
        }
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
            result.value = loadResult(store, runId, isSyncing(SyncServices.progress.value))
            screen.value = Screen.Result(runId)
        }
    }

    fun openSettings(notificationsAllowed: Boolean, readGranted: Boolean) {
        screen.value = Screen.Settings
        refreshSettings(notificationsAllowed, readGranted)
    }

    fun refreshSettings(notificationsAllowed: Boolean, readGranted: Boolean, denied: Boolean = false) {
        viewModelScope.launch {
            val cleanup = when {
                denied -> CleanupState.Denied
                !readGranted -> CleanupState.NeedsPermission
                else -> CleanupState.Found(withContext(Dispatchers.IO) { writer().leftovers() })
            }
            settings.value = settingsUi(
                autoSetting = store.setting(playlistsyncfox.android.sync.SyncScheduler.AUTO_ENABLED_KEY),
                pairing = store.pairing(),
                notificationsAllowed = notificationsAllowed,
                logBytes = graph.log.sizeBytes(),
                cleanup = cleanup,
            )
        }
    }

    fun toggleAuto(enabled: Boolean) {
        viewModelScope.launch {
            val ok = graph.scheduler.setAutoEnabled(enabled)
            if (!ok) message.value = "自動起動の登録に失敗した"
            settings.update { it?.copy(autoEnabled = store.setting(playlistsyncfox.android.sync.SyncScheduler.AUTO_ENABLED_KEY) == "true") }
        }
    }

    /** 解除。Keystore の実体が無いうちは何も消さず、できない旨を出す。 */
    fun unpair() {
        viewModelScope.launch {
            val keeper = graph.keeper
            if (keeper == null) {
                message.value = "解除はまだ使えない"
                return@launch
            }
            if (keeper.unpair()) {
                screen.value = Screen.Home
            } else {
                message.value = "鍵の削除に失敗したため、解除は済んでいない"
            }
        }
    }

    fun openLog() {
        logLines.value = graph.log.tail().asReversed()
        screen.value = Screen.Log
    }

    /** 共有する本文。閲覧と同じ切り詰め。 */
    fun logText(): String = graph.log.tail().joinToString("\n")

    fun clearLog() {
        graph.log.clear()
        logLines.value = emptyList()
    }

    fun openLicenses() {
        screen.value = Screen.Licenses
    }

    private suspend fun writer(): MediaWriter {
        val deviceId = store.pairing()?.deviceId ?: store.setting("device_id").orEmpty()
        return MediaWriter(store, ResolverMediaStoreFiles(graph.application), deviceId, System::currentTimeMillis)
    }
}
