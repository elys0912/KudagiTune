package playlistsyncfox.android.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import playlistsyncfox.android.net.SyncApi
import playlistsyncfox.shared.result.ProgressReport
import playlistsyncfox.shared.result.ProgressStage
import playlistsyncfox.shared.result.writeProgressReport

/**
 * P8 の送信（X6-3）。同期の手順とは別の流れで送る。P3 が通った後から P4 の前までが対象。
 * 同じ stage は V28 以上あけ、送っている途中は送らない。失敗は無視し、再送せず、T3 に残さない。
 * [scope] は [close] で取り消す。
 */
internal class ProgressReporter(private val scope: CoroutineScope, private val now: () -> Long) {
    private var sentAt: Long? = null
    private var sentStage: ProgressStage? = null
    @Volatile private var sending = false
    private var closed = false

    /** 送る候補。[api] が null（P3 の前）なら送らない。 */
    fun offer(api: SyncApi?, progress: SyncProgress) {
        if (api == null || closed || sending) return
        val stage = progressStageOf(progress.stage) ?: return

        val currentTime = now()
        val last = sentAt
        if (stage == sentStage && last != null && currentTime - last < SyncValues.NOTIFICATION_INTERVAL_MS) return

        sentAt = currentTime
        sentStage = stage
        sending = true
        val body = writeProgressReport(ProgressReport(stage, progress.doneCount, progress.totalCount, progress.doneBytes, progress.totalBytes))
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                api.progress(body)
            } catch (_: Exception) {
                // 失敗しても同期は止めない
            } finally {
                sending = false
            }
        }
    }

    /** 送っている途中のものも片付ける。P4 の前と、実行の終わりで呼ぶ。 */
    fun close() {
        closed = true
        scope.cancel()
    }

    /** 完了（Finished）は P8 で送らないので null。 */
    private fun progressStageOf(stage: SyncStage): ProgressStage? = when (stage) {
        SyncStage.Connecting -> ProgressStage.Connecting
        SyncStage.Manifest -> ProgressStage.Manifest
        SyncStage.Diff -> ProgressStage.Diff
        SyncStage.Capacity -> ProgressStage.Capacity
        SyncStage.Transfer -> ProgressStage.Transfer
        SyncStage.Playlist -> ProgressStage.Playlist
        SyncStage.Finished -> null
    }
}
