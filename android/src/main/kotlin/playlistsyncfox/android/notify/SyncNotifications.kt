package playlistsyncfox.android.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.storage.StorageManager
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.result.SyncTrigger

/** 同期の通知（X7-3 のうち、ワーカーが前面へ昇格するときの通知と終了の通知）。チャンネル名は V52。 */
object SyncNotifications {
    const val CHANNEL_PROGRESS = "sync_progress"
    const val CHANNEL_RESULT = "sync_result"
    const val CHANNEL_RESULT_QUIET = "sync_result_quiet"

    const val PROGRESS_ID = 1001

    /** 終了の通知は 1 つだけにする（同じ番号で更新して積み上げない）。 */
    const val RESULT_ID = 1002

    /** 通知のタップで開く画面（S3 は進行、S4 は結果）。 */
    const val EXTRA_OPEN = "open"
    const val OPEN_PROGRESS = "progress"
    const val OPEN_RESULT = "result"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "同期の進行状況", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULT, "同期の結果", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RESULT_QUIET, "同期の結果（自動）", NotificationManager.IMPORTANCE_LOW))
    }

    /** 持続の通知。音と振動なし。「中断」は [cancel]、本体のタップは [open]。 */
    fun progress(context: Context, progress: SyncProgress, cancel: PendingIntent?, open: PendingIntent?): Notification {
        val builder = Notification.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("同期中")
            .setContentText(progressText(progress))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (progress.stage == SyncStage.Transfer && progress.totalBytes > 0) {
            builder.setProgress(PROGRESS_SCALE, (progress.doneBytes * PROGRESS_SCALE / progress.totalBytes).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (cancel != null) builder.addAction(Notification.Action.Builder(null, "中断する", cancel).build())
        return builder.build()
    }

    /** 終了の通知。自動は静かなチャンネルに出す。 */
    fun finished(context: Context, run: SyncRun, progress: SyncProgress, trigger: SyncTrigger, open: PendingIntent?): Notification {
        val channel = resultChannel(trigger)
        val title = when (run.result) {
            SyncRun.RESULT_SUCCESS -> "同期が完了しました"
            SyncRun.RESULT_PARTIAL -> "一部の曲で失敗しました"
            SyncRun.RESULT_INTERRUPTED -> "同期が中断されました"
            SyncRun.RESULT_NOT_STARTED -> "空き容量が足りないため、同期を開始できませんでした"
            else -> "同期に失敗しました"
        }
        val text = buildString {
            append("追加 ${run.added}、更新 ${run.replaced}、削除 ${run.removed}、失敗 ${run.failed}")
            // E7 は続きを次の同期で行う
            if (run.errorKind == "E7") append("。残りは次の同期で続けます")
            progress.shortageBytes?.let { append("。あと ${it / (1024 * 1024)} MiB の空きが必要です。タップするとストレージの設定を開きます") }
            // 自動が「消す」を飛ばしたときは、確認が要る旨を足す（J9）
            if (trigger == SyncTrigger.Auto && removalSkipped(run)) append("。削除の確認が必要な曲があります")
        }
        return Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
    }

    /** 手動は既定の重要度、自動は静かなチャンネル（利用者の中断を含む。X7-3-2）。 */
    fun resultChannel(trigger: SyncTrigger) = if (trigger == SyncTrigger.Manual) CHANNEL_RESULT else CHANNEL_RESULT_QUIET

    /** 終了の通知を出す。通知の権限が無ければ OS が出さないだけで、同期には影響しない。 */
    fun postFinished(context: Context, run: SyncRun, progress: SyncProgress, trigger: SyncTrigger) {
        // 容量不足は空きを作る画面へ、そのほかは S4 へ
        val open = if (progress.shortageBytes != null) storageIntent(context) else openIntent(context, OPEN_RESULT)
        context.getSystemService(NotificationManager::class.java)
            .notify(RESULT_ID, finished(context, run, progress, trigger, open))
    }

    /** アプリを開き、[route] の画面へ進ませる PendingIntent。 */
    fun openIntent(context: Context, route: String): PendingIntent? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, route.hashCode(), it.putExtra(EXTRA_OPEN, route), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

    private fun storageIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(context, 0, Intent(StorageManager.ACTION_MANAGE_STORAGE), PendingIntent.FLAG_IMMUTABLE)

    private fun removalSkipped(run: SyncRun) = run.resultJson?.let { runCatching { decodeResult(it).removalSkipped }.getOrNull() } == true

    private fun progressText(progress: SyncProgress): String = when (progress.stage) {
        SyncStage.Connecting -> "PC に接続しています"
        SyncStage.Manifest -> "曲の一覧を取得しています"
        SyncStage.Diff -> "変更点を確認しています"
        SyncStage.Capacity -> "空き容量を確認しています"
        SyncStage.Transfer -> "${progress.doneCount}/${progress.totalCount} 曲" + (progress.currentTitle?.let { "：$it" } ?: "")
        SyncStage.Playlist -> "プレイリストを保存しています"
        SyncStage.Finished -> "終了しました"
    }

    private const val PROGRESS_SCALE = 1000
}

/** 持続の通知の更新は、前回から V28 以上空かなければ捨てる。`stage` の変化と `finished` は間隔に関わらず通す（X7-3-3）。 */
class NotificationThrottle {
    private var lastAt: Long? = null
    private var lastStage: SyncStage? = null

    fun accept(progress: SyncProgress, now: Long): Boolean {
        val last = lastAt
        val pass = last == null || progress.stage != lastStage || progress.stage == SyncStage.Finished ||
            now - last >= SyncValues.NOTIFICATION_INTERVAL_MS
        if (pass) {
            lastAt = now
            lastStage = progress.stage
        }
        return pass
    }
}
