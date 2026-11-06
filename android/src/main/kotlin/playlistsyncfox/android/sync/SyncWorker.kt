package playlistsyncfox.android.sync

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.notify.NotificationThrottle
import playlistsyncfox.android.notify.SyncNotifications
import playlistsyncfox.shared.result.SyncTrigger

/** ワーカーが環境を作るときに渡す、Worker に依存する部品。 */
class WorkerHooks(
    val promote: suspend () -> Boolean,
    val stopCause: () -> StopCause,
    val manualActive: suspend () -> Boolean,
    /** 終了の通知を出す（X7-3）。`SyncEnvironment.onFinished` に渡す。 */
    val notifyFinished: (SyncRun, SyncProgress, SyncTrigger) -> Unit,
)

/** 本番の [SyncEnvironment] の組み立て。Keystore と NSD の実体を持つ側（Application）が設定する。 */
fun interface SyncEnvironmentFactory {
    fun create(context: Context, hooks: WorkerHooks): SyncEnvironment
}

object SyncServices {
    val progress = MutableStateFlow<SyncProgress?>(null)

    @Volatile
    var factory: SyncEnvironmentFactory? = null
}

/**
 * 同期のワーカー（X6-3）。手動と自動で 1 つの実装を使う。結果は T3 に持つので、常に `Result.success` を返し、
 * 再試行は WorkManager に求めない。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val factory = SyncServices.factory ?: return Result.success()
        val trigger = if (inputData.getString(KEY_TRIGGER) == TRIGGER_MANUAL) SyncTrigger.Manual else SyncTrigger.Auto
        // OS が止めた仕事とプロセスが落ちた仕事の走り直しでは、「消す」の承認を捨てる（V48）
        val rerun = runAttemptCount > 0
        val input = RunInput(
            trigger = trigger,
            deleteApproved = inputData.getBoolean(KEY_DELETE_APPROVED, false),
            approvedDeleteCount = inputData.getInt(KEY_APPROVED_DELETE_COUNT, 0),
            reachFailed = inputData.getBoolean(KEY_REACH_FAILED, false),
            rerun = rerun,
        )

        val hooks = WorkerHooks(
            promote = ::promote,
            stopCause = {
                // 時間切れ（ジョブ、前面サービス）か、アプリからの取り消しか。
                // Android 11 は理由を取れないので中断として扱う。どちらも「消す」をせず、次の同期で続きから進むので害が無い
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (stopReason == WorkInfo.STOP_REASON_TIMEOUT || stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT)
                ) {
                    StopCause.Timeout
                } else {
                    StopCause.UserCancel
                }
            },
            manualActive = ::manualActive,
            notifyFinished = { run, progress, trigger -> SyncNotifications.postFinished(applicationContext, run, progress, trigger) },
        )
        val environment = factory.create(applicationContext, hooks)
        SyncNotifications.ensureChannels(applicationContext)

        coroutineScope {
            val throttle = NotificationThrottle()
            val manager = applicationContext.getSystemService(android.app.NotificationManager::class.java)
            // 前面へ昇格した後の更新は V28 の間隔以上にする
            val updates = launch {
                environment.progress.collect { progress ->
                    if (progress != null && foregroundPromoted && progress.stage != SyncStage.Finished &&
                        throttle.accept(progress, environment.now())
                    ) {
                        manager.notify(SyncNotifications.PROGRESS_ID, progressNotification(progress))
                    }
                }
            }
            try {
                SyncRunner(environment).run(input)
            } finally {
                updates.cancel()
            }
        }
        return Result.success()
    }

    @Volatile
    private var foregroundPromoted = false

    /** `setForeground`。起動の制限などで例外になったら偽（T3 には残さず終える。X6-3）。 */
    private suspend fun promote(): Boolean = try {
        SyncNotifications.ensureChannels(applicationContext)
        setForeground(
            ForegroundInfo(
                SyncNotifications.PROGRESS_ID,
                progressNotification(SyncProgress(SyncStage.Connecting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            ),
        )
        foregroundPromoted = true
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun progressNotification(progress: SyncProgress) = SyncNotifications.progress(
        applicationContext,
        progress,
        cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id),
        open = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)?.let {
            android.app.PendingIntent.getActivity(applicationContext, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE)
        },
    )

    /** `manual-sync` が実行中か待機中か。 */
    private suspend fun manualActive(): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(applicationContext).getWorkInfosForUniqueWork(SyncScheduler.MANUAL).get()
            .any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        const val KEY_DELETE_APPROVED = "delete_approved"
        const val KEY_APPROVED_DELETE_COUNT = "approved_delete_count"
        const val KEY_REACH_FAILED = "reach_failed"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_AUTO = "auto"
    }
}
