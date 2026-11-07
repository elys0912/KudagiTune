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
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.android.sync.SyncStage

const val NO_SELECTION_NOTICE = "同期するプレイリストを選んでください"
const val STORAGE_MISSING_NOTICE = "保存先の SD が見つからない。SD を確かめるか、設定で保存先を替える"
const val STORAGE_CHANGED_NOTICE = "保存先を替えた。次の同期で新しい場所へ全曲を取り直す"

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

    val home: StateFlow<HomeUiState> = combine(
        store.observePairing(),
        store.observeRuns(),
        store.observePlaylists(),
        store.observeSelectedPlaylists(),
        SyncServices.progress,
    ) { pairing, runs, playlists, selected, progress -> homeState(pairing, runs, playlists, selected.size, isSyncing(progress)) }
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

    val selection = SelectionPresenter(
        scope = viewModelScope,
        store = store,
        fetch = { withContext(Dispatchers.IO) { graph.fetchPlaylists() } },
        now = System::currentTimeMillis,
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
                deleteMissingSetting = store.setting(SyncValues.DELETE_MISSING_KEY),
                relocatedCount = store.relocatedUris().size,
                storage = storageUi(graph.volumes.mounted(), graph.storageVolume()),
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

    /** 「PC に無い曲を端末から消す」の切り替え。 */
    fun setDeleteMissing(enabled: Boolean) {
        viewModelScope.launch {
            store.putSetting(SyncValues.DELETE_MISSING_KEY, enabled.toString())
            settings.update { it?.copy(deleteMissing = enabled) }
        }
    }

    /** 「以前の場所の曲を消す」。確認は画面が済ませてある。同期が走っている間は消さない。 */
    fun deleteRelocated() {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { cleanRelocated(writer(), isSyncing(SyncServices.progress.value)) }
            message.value = if (deleted == null) "同期が動いている間は消せない" else "以前の場所の曲を $deleted 件消した"
            settings.update { it?.copy(relocatedCount = store.relocatedUris().size) }
        }
    }

    /** 保存先の切り替え（D16）。T1、T2 は動かさず、次の同期が新しい場所へ取り直す。 */
    fun setStorage(volume: String) {
        viewModelScope.launch {
            store.putSetting(SyncValues.STORAGE_VOLUME_KEY, volume)
            settings.update { it?.copy(storage = storageUi(graph.volumes.mounted(), volume)) }
            message.value = STORAGE_CHANGED_NOTICE
        }
    }

    fun unpair() {
        viewModelScope.launch {
            if (graph.keeper.unpair()) {
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
        return MediaWriter(store, ResolverMediaStoreFiles(graph.application, graph.storageVolume()), deviceId, System::currentTimeMillis)
    }
}
