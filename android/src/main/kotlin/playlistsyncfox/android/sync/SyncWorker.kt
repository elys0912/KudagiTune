package playlistsyncfox.android.sync

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.Data
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

    /** Application が起動時に必ず設定する。未設定のまま [SyncWorker] が走ったら、成功にせず例外にする。 */
    @Volatile
    lateinit var factory: SyncEnvironmentFactory

    /** [factory] が設定済みか。lateinit の `isInitialized` は宣言の外から使えないため、ここで公開する。 */
    val isReady: Boolean get() = ::factory.isInitialized
}

/**
 * WorkManager の入力データから同期の入力を作る。[runAttemptCount] が 1 以上は、OS が止めた仕事とプロセスが
 * 落ちた仕事の走り直し。走り直しでは「消す」の承認を捨てる（V48）。
 */
internal fun runInputOf(data: Data, runAttemptCount: Int) = RunInput(
    trigger = if (data.getString(SyncWorker.KEY_TRIGGER) == SyncWorker.TRIGGER_MANUAL) SyncTrigger.Manual else SyncTrigger.Auto,
    deleteApproved = data.getBoolean(SyncWorker.KEY_DELETE_APPROVED, false),
    approvedDeleteCount = data.getInt(SyncWorker.KEY_APPROVED_DELETE_COUNT, 0),
    reachFailed = data.getBoolean(SyncWorker.KEY_REACH_FAILED, false),
    rerun = runAttemptCount > 0,
)

/**
 * OS が止めた理由（[WorkInfo] の `STOP_REASON_*`）から、時間切れか、アプリからの取り消しかを決める。
 * [stopReason] が null（Android 11 は理由を取れない）なら中断として扱う。どちらも「消す」をせず、次の同期で続きから進むので害が無い。
 */
internal fun stopCauseOf(stopReason: Int?): StopCause =
    if (stopReason == WorkInfo.STOP_REASON_TIMEOUT || stopReason == WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) {
        StopCause.Timeout
    } else {
        StopCause.UserCancel
    }

/**
 * 同期のワーカー（X6-3）。手動と自動で 1 つの実装を使う。結果は T3 に持つので、想定外の例外でも
 * `Result.success` を返し、再試行は WorkManager に求めない（X6-12）。
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val factory = SyncServices.factory
        val input = runInputOf(inputData, runAttemptCount)

        val hooks = WorkerHooks(
            promote = ::promote,
            stopCause = { stopCauseOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) stopReason else null) },
            manualActive = ::manualActive,
            notifyFinished = { run, progress, trigger -> SyncNotifications.postFinished(applicationContext, run, progress, trigger) },
        )
        val environment = factory.create(applicationContext, hooks)
        SyncNotifications.ensureChannels(applicationContext)

        coroutineScope {
            val throttle = NotificationThrottle()
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // T3 の行を作った後の例外は SyncRunner が「失敗」で閉じる。ここへ届くのは、行を作る前の例外と、閉じる処理の例外。
                // 進行状態は SyncRunner が消す。ログに残し、結果は T3 にあるので成功で終える（X6-12）
                environment.log.line("想定外の例外で同期を終えた：${e.javaClass.simpleName}")
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
        open = SyncNotifications.openIntent(applicationContext, SyncNotifications.OPEN_PROGRESS),
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
