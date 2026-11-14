package playlistsyncfox.android.ui

import kotlinx.coroutines.flow.MutableStateFlow
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.sync.ManualStart
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.sync.RemovalCheck
import playlistsyncfox.android.sync.StartBlock
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage

// S1 一覧（X7-1-2）。

enum class HomeKind { Unpaired, NotRun, Done, Syncing }

/** 最後の同期。[interruptedTrace] は `ended_at` が NULL の行を「中断」として出すもの（日時は `started_at`）。 */
data class LastRun(val run: SyncRun, val word: String, val atMillis: Long, val interruptedTrace: Boolean)

/** [missingOnPc] は、マニフェストを取れた最後の同期で PC に見つからなかったもの。曲数の代わりに「PC に見つかりません」と出す。 */
data class PlaylistRow(val name: String, val trackCount: Int, val missingOnPc: Boolean)

data class HomeUiState(
    val kind: HomeKind,
    val pcName: String? = null,
    val endpoint: String? = null,
    val last: LastRun? = null,
    /** [last] で接続した方式の表示語。PC に届かずに終わった実行や `result_json` が無い行は null（X7-1-2）。 */
    val lastTransport: String? = null,
    val playlists: List<PlaylistRow> = emptyList(),
    /** 選んだプレイリストの数（T8）。0 なら同期ボタンは S6 へ案内する。 */
    val selectedCount: Int = 0,
)

/** 同期が動いているか。`finished` を過ぎた状態は動いていない。 */
fun isSyncing(progress: SyncProgress?): Boolean = progress != null && progress.stage != SyncStage.Finished

/**
 * 最後の同期の行（S1 と S4 で共通）。`ended_at` が NULL でない最新の行。NULL の行は、同期中なら出さず
 * （その前の行が無ければ無し）、同期中でなければ「中断」として出す。[runs] は新しい順。
 */
fun latestRun(runs: List<SyncRun>, syncing: Boolean): LastRun? {
    val row = (if (syncing) runs.firstOrNull { it.endedAt != null } else runs.firstOrNull()) ?: return null
    val ended = row.endedAt
    return if (ended == null) {
        LastRun(row, resultWord(SyncRun.RESULT_INTERRUPTED), row.startedAt, interruptedTrace = true)
    } else {
        LastRun(row, resultWord(row.result), ended, interruptedTrace = false)
    }
}

/** T4 の `last_endpoint` が指す方式の値。空なら「未接続」（J30）。 */
fun endpointText(pairing: Pairing): String {
    val value = when (pairing.lastEndpoint) {
        PairingKeeper.LAN -> pairing.endpointLan
        PairingKeeper.TAILSCALE -> pairing.endpointTailscale
        PairingKeeper.USB -> pairing.endpointUsb
        else -> null
    }
    return value?.takeIf { it.isNotEmpty() } ?: "未接続"
}

/** 接続に成功する前に止まった実行の E の種別。この実行の `transport` は保存の列の種類で、判定した方式ではない（B4-1 の 3）。 */
private val NOT_CONNECTED_ERROR_KINDS = setOf("E1", "E2", "E4")

/** `result_json` の `transport` の表示語。JSON は DB から来るため、壊れていれば null。 */
private fun transportLabelOf(row: SyncRun): String? {
    if (row.errorKind in NOT_CONNECTED_ERROR_KINDS) return null

    return row.resultJson?.let { runCatching { transportWord(decodeResult(it).transport) }.getOrNull() }
}

/**
 * マニフェストを取れた最後の同期（`content_digest` がある行）で、PC に見つからなかったプレイリストの ID。
 * PC に届かなかった同期の行では判定しない。JSON は DB から来るため、壊れていれば空。[runs] は新しい順。
 */
private fun unresolvedPlaylistIds(runs: List<SyncRun>): Set<String> {
    val json = runs.firstOrNull { it.contentDigest != null }?.resultJson ?: return emptySet()

    return runCatching { decodeResult(json).unresolvedPlaylists.toSet() }.getOrDefault(emptySet())
}

fun homeState(pairing: Pairing?, runs: List<SyncRun>, playlists: List<WrittenPlaylist>, selectedCount: Int, syncing: Boolean): HomeUiState {
    if (pairing == null) return HomeUiState(HomeKind.Unpaired)
    val last = latestRun(runs, syncing)
    val unresolved = unresolvedPlaylistIds(runs)
    return HomeUiState(
        kind = when {
            syncing -> HomeKind.Syncing
            last == null -> HomeKind.NotRun
            else -> HomeKind.Done
        },
        pcName = pairing.pcName,
        endpoint = endpointText(pairing),
        last = last,
        lastTransport = last?.takeIf { !it.interruptedTrace }?.let { transportLabelOf(it.run) },
        playlists = playlists.map { PlaylistRow(it.name, it.trackCount, missingOnPc = it.playlistId in unresolved) },
        selectedCount = selectedCount,
    )
}

sealed interface PressOutcome {
    data object ToPairing : PressOutcome

    /** S3 へ進む。[notice] は S3 に出す案内。 */
    data class ToProgress(val notice: String? = null) : PressOutcome

    data class Confirm(val toRemove: Int) : PressOutcome

    /** 選択が空。同期を始めず S6 へ案内する（D15）。 */
    data object ToSelection : PressOutcome

    /** 保存先の SD が見つからない。同期を始めず案内する。本体へ切り替えない（D16）。 */
    data object StorageMissing : PressOutcome

    /** 通知の許可の画面を出した。同期は許可の結果の後に、画面側（MainActivity）が `syncPressed` を呼んで始める。 */
    data object AwaitingNotificationPermission : PressOutcome
}

/**
 * 手動の同期の開始（X7-1-2）。確認の要否は画面側が決め、ワーカーの起動は [start] に任せる。
 * [start] の引数は、承認した件数（承認しないなら null）と `reach_failed`。
 */
class ManualSyncFlow(
    private val autoRunning: suspend () -> Boolean,
    private val block: suspend () -> StartBlock?,
    private val check: suspend () -> RemovalCheck,
    private val start: suspend (approvedCount: Int?, reachFailed: Boolean) -> ManualStart,
) {
    /**
     * [askNotification] は通知の許可を求める。同期が始まると決まった後（選択があり、保存先がある）にだけ呼び、
     * 許可の画面を出したときは true を返す（X7-3）。拒否でも同期は続ける。
     */
    suspend fun press(paired: Boolean, askNotification: () -> Boolean = { false }): PressOutcome {
        if (!paired) return PressOutcome.ToPairing
        // 実行中の自動があれば、確認を出さずに S3 へ入る。自動は自動のまま（J34）
        if (autoRunning()) return PressOutcome.ToProgress()
        when (block()) {
            StartBlock.NoSelection -> return PressOutcome.ToSelection
            StartBlock.StorageMissing -> return PressOutcome.StorageMissing
            null -> Unit
        }
        if (askNotification()) return PressOutcome.AwaitingNotificationPermission
        return when (val result = check()) {
            RemovalCheck.Allowed -> started(null, reachFailed = false)
            is RemovalCheck.NeedsConfirmation -> PressOutcome.Confirm(result.toRemove)
            is RemovalCheck.Failed -> started(null, result.reachFailed)
        }
    }

    /** 承認なら件数を添えて開始する。取り消しは「消す」だけ飛ばして同期を始める（J26）。 */
    suspend fun answer(confirm: PressOutcome.Confirm, approved: Boolean): PressOutcome {
        val count = if (approved) confirm.toRemove else null
        return started(count, reachFailed = false)
    }

    private suspend fun started(approvedCount: Int?, reachFailed: Boolean): PressOutcome {
        val result = start(approvedCount, reachFailed)
        val unused = approvedCount != null && result == ManualStart.AutoRunning
        return PressOutcome.ToProgress(APPROVAL_UNUSED_NOTICE.takeIf { unused })
    }
}

/**
 * 手動の同期の開始。前回の終了の進捗が [progress] に残っていると、開始した直後に S4 へ進むため、新しい仕事を作ったときだけ消す。
 * 作らなかったとき（自動か手動が実行中）は、走っているワーカーの進捗を残す。新しいワーカーが先に出した進捗も消さない。
 */
suspend fun startManualSync(progress: MutableStateFlow<SyncProgress?>, start: suspend () -> ManualStart): ManualStart {
    val before = progress.value
    val result = start()
    if (result == ManualStart.Started) progress.compareAndSet(before, null)
    return result
}

/**
 * 同期ボタンを押した時点の進捗 [atPress] が、開始の処理の後（[now]）も同じ完了（`finished`）のまま残っているときに、その完了を返す。
 * 自動が始まる直前だと進捗が消されず、S3 が前の実行の完了を新しい実行の完了と取り違えて S4 へ進むため、S3 が無視する。
 * 進捗が入れ替わっていれば null。内容が同じでも別の完了は、新しい実行のものとして扱う。
 */
fun staleFinished(atPress: SyncProgress?, now: SyncProgress?): SyncProgress? =
    atPress?.takeIf { it.stage == SyncStage.Finished && it === now }
