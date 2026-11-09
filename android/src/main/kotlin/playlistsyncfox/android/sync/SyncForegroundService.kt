package playlistsyncfox.android.sync

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import playlistsyncfox.android.notify.SyncNotifications
import playlistsyncfox.shared.result.SyncTrigger

/**
 * 前面サービスの直接起動（X6-4、基本設計の直し 18）。Android 16 以降で長い処理の Worker が割り当て時間を使い切る場合に、
 * Worker の代わりに同じ [SyncRunner] を動かす余地。どちらで動かすかは C-10 の結果で決めるため、[SyncScheduler] は
 * まだこちらを呼ばない。
 */
class SyncForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    @Volatile
    private var stopCause = StopCause.UserCancel

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        // lateinit の `isInitialized` は宣言の外から使えないため、未設定の例外で判定する
        val factory = try {
            SyncServices.factory
        } catch (_: UninitializedPropertyAccessException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        SyncNotifications.ensureChannels(this)
        val cancel = PendingIntent.getService(
            this, 0, Intent(this, SyncForegroundService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        startForeground(
            SyncNotifications.PROGRESS_ID,
            SyncNotifications.progress(this, SyncProgress(SyncStage.Connecting), cancel, open = null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        val input = RunInput(
            trigger = if (intent?.getStringExtra(SyncWorker.KEY_TRIGGER) == SyncWorker.TRIGGER_AUTO) SyncTrigger.Auto else SyncTrigger.Manual,
            deleteApproved = intent?.getBooleanExtra(SyncWorker.KEY_DELETE_APPROVED, false) ?: false,
            approvedDeleteCount = intent?.getIntExtra(SyncWorker.KEY_APPROVED_DELETE_COUNT, 0) ?: 0,
            reachFailed = intent?.getBooleanExtra(SyncWorker.KEY_REACH_FAILED, false) ?: false,
        )
        val hooks = WorkerHooks(
            promote = { true },
            stopCause = { stopCause },
            manualActive = { false },
            notifyFinished = { run, progress, trigger -> SyncNotifications.postFinished(applicationContext, run, progress, trigger) },
        )
        job = scope.launch {
            try {
                SyncRunner(factory.create(applicationContext, hooks)).run(input)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 以降の `dataSync` の時間切れ。数秒以内に止める（E7）。 */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopCause = StopCause.Timeout
        job?.cancel()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "playlistsyncfox.android.sync.STOP"

        fun start(context: Context, input: Intent) {
            context.startForegroundService(input.setClass(context, SyncForegroundService::class.java))
        }
    }
}
