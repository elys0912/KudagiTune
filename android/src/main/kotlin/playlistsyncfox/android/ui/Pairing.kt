package playlistsyncfox.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.discovery.DiscoveredPc
import playlistsyncfox.android.discovery.ManualCheck

// S2 ペアリング（X7-1-3）。入力の検査と画面の段までを持ち、P5・P6 と鍵の取り込みは [PairingGateway] の先。

/** 接続先 1 件。[kind] と [savedValue] は T4 の列に書く方式と値（X7-2-4）。 */
data class PairingTarget(val host: String, val port: Int, val name: String, val kind: String, val savedValue: String)

/** ゲートウェイが返す段の結果。P6 の成功で T4 と Keystore の保存が済んだ後に [Completed] を返す。 */
sealed interface PairingEvent {
    /** P5 の応答の時点で表示するだけ。保存の前。 */
    data class Fingerprint(val text: String) : PairingEvent

    data class Completed(val deviceId: String, val pcName: String) : PairingEvent

    /** コードが合わなかった（試行を 1 回使った）。 */
    data object CodeMismatch : PairingEvent

    /** PC が試行の上限（`pairing_locked`。V4 の待ちを含む）を返した。 */
    data object AttemptsExhausted : PairingEvent

    /** V32 の待ち（429）。 */
    data object Waiting : PairingEvent

    /** 新規を PC が受け付けない（`device_exists`）。 */
    data object DeviceExists : PairingEvent

    /** 接続、検査、鍵の導出の失敗。 */
    data class Failed(val notice: Notice) : PairingEvent

    /** P5・P6 の実体が未接続。何も送らず、何も保存しない。 */
    data object Unavailable : PairingEvent
}

/** P5・P6、鍵の導出、保存（X6-6）への入口。購読を止める（キャンセル）と、接続を切り、一時の鍵を捨てる。 */
fun interface PairingGateway {
    fun pair(target: PairingTarget, code: String): Flow<PairingEvent>
}

/** 実体ができるまでの入口。利用者に「まだ使えない」と分かる状態にする。 */
object UnavailablePairingGateway : PairingGateway {
    override fun pair(target: PairingTarget, code: String): Flow<PairingEvent> = flowOf(PairingEvent.Unavailable)
}

sealed interface PairingStage {
    /** 画面の表示から V13 の間。一覧（空でもよい）と手入力欄。 */
    data object Discovering : PairingStage

    /** 宛先の確定。コードの入力欄。[fingerprint] は P5 の応答が返った後。 */
    data class Target(val target: PairingTarget, val fingerprint: String? = null, val codeError: String? = null) : PairingStage

    data class Done(val pcName: String, val showCleanupHint: Boolean) : PairingStage

    data object AttemptsExhausted : PairingStage

    data object Waiting : PairingStage

    data class Failed(val notice: Notice) : PairingStage

    data object DeviceExists : PairingStage

    data object Unavailable : PairingStage
}

data class PairingUiState(
    val stage: PairingStage = PairingStage.Discovering,
    val found: List<DiscoveredPc> = emptyList(),
    /** V13 が過ぎても 1 台も無い。手入力欄を目立たせる。 */
    val notFound: Boolean = false,
    val manualError: String? = null,
    val sending: Boolean = false,
)

/** V1 ペアリングコードの桁数。 */
const val CODE_LENGTH = 6

/** V2 ペアリングコードの試行回数の上限。 */
const val CODE_ATTEMPTS = 5

/** V13 発見の待ち。 */
const val DISCOVERY_WAIT_MS = 10_000L

class PairingPresenter(
    private val scope: CoroutineScope,
    private val store: SyncStore,
    private val gateway: PairingGateway,
    /** 発見の開始。購読を止めると停止する。 */
    private val discover: () -> Flow<List<DiscoveredPc>>,
    private val checkManual: (String) -> ManualCheck,
) {
    private val mutableState = MutableStateFlow(PairingUiState())
    val state: StateFlow<PairingUiState> = mutableState

    private var discovering: Job? = null
    private var pairing: Job? = null
    private var mismatches = 0
    private var lastTarget: PairingTarget? = null

    /** S2 を開く。発見は S2 のときだけ行う。 */
    fun open() {
        mutableState.value = PairingUiState()
        mismatches = 0
        discovering?.cancel()
        discovering = scope.launch {
            launch {
                delay(DISCOVERY_WAIT_MS)
                mutableState.update { if (it.found.isEmpty()) it.copy(notFound = true) else it }
            }
            discover().collect { found -> mutableState.update { it.copy(found = found, notFound = it.notFound && found.isEmpty()) } }
        }
    }

    /** S2 を離れる。P6 の成功の前なら接続を切って何も残さない。成功の後の保存は取り消さない。 */
    fun close() {
        discovering?.cancel()
        pairing?.cancel()
        pairing = null
    }

    fun select(pc: DiscoveredPc) {
        val target = PairingTarget(pc.host, pc.port, pc.serviceName, kind = "lan", savedValue = pc.serviceName)
        mutableState.update { it.copy(stage = PairingStage.Target(target), manualError = null) }
    }

    fun submitManual(text: String) {
        when (val check = checkManual(text)) {
            is ManualCheck.Invalid -> mutableState.update { it.copy(manualError = check.reason.text) }
            is ManualCheck.Valid -> {
                val endpoint = check.endpoint
                val target = PairingTarget(endpoint.host, endpoint.port, endpoint.host, endpoint.kind, endpoint.savedValue)
                mutableState.update { it.copy(stage = PairingStage.Target(target), manualError = null) }
            }
        }
    }

    /** コードを送る。入力欄は呼び出し側が送信の直後に空にする。コードはここにも状態にも残さない。 */
    fun submitCode(code: String) {
        val current = mutableState.value.stage as? PairingStage.Target ?: return
        if (code.length != CODE_LENGTH || !code.all(Char::isDigit)) {
            mutableState.update { it.copy(stage = current.copy(codeError = "コードは $CODE_LENGTH 桁の数字")) }
            return
        }
        lastTarget = current.target
        mutableState.update { it.copy(stage = current.copy(codeError = null), sending = true) }
        pairing?.cancel()
        pairing = scope.launch {
            try {
                gateway.pair(current.target, code).collect { handle(current, it) }
            } finally {
                mutableState.update { it.copy(sending = false) }
            }
        }
    }

    /** 失敗の段から宛先の確定に戻る（「やり直す」）。 */
    fun retry() {
        val target = lastTarget
        mutableState.update { it.copy(stage = target?.let(PairingStage::Target) ?: PairingStage.Discovering) }
    }

    private suspend fun handle(current: PairingStage.Target, event: PairingEvent) {
        val stage = when (event) {
            is PairingEvent.Fingerprint -> current.copy(fingerprint = event.text)
            is PairingEvent.Completed -> PairingStage.Done(event.pcName, showCleanupHint = nothingPlaced(event.deviceId))
            PairingEvent.CodeMismatch -> if (++mismatches >= CODE_ATTEMPTS) {
                PairingStage.AttemptsExhausted
            } else {
                current.copy(codeError = "コードが違う")
            }
            PairingEvent.AttemptsExhausted -> PairingStage.AttemptsExhausted
            PairingEvent.Waiting -> PairingStage.Waiting
            PairingEvent.DeviceExists -> PairingStage.DeviceExists
            is PairingEvent.Failed -> PairingStage.Failed(event.notice)
            PairingEvent.Unavailable -> PairingStage.Unavailable
        }
        mutableState.update { it.copy(stage = stage) }
    }

    /** 完了の案内の条件：T1・T2 がともに空（J38）。 */
    private suspend fun nothingPlaced(deviceId: String) =
        store.placedTracks(deviceId).isEmpty() && store.playlists(deviceId).isEmpty()
}
