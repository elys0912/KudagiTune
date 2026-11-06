package playlistsyncfox.android.sync

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import playlistsyncfox.android.db.SyncStore
import java.util.concurrent.TimeUnit

enum class ManualStart { Started, AutoRunning, AlreadyRunning }

/** WorkManager の登録（X6-4）。自動は `auto-sync`（周期）、手動は `manual-sync`（1 回）。 */
class SyncScheduler(private val workManager: WorkManager, private val store: SyncStore) {
    /**
     * 手動の開始。自動が実行中なら新しい仕事を作らない（自動は自動のまま。J34）。
     * 同じ名前が進行中でも始めない。
     */
    suspend fun startManual(deleteApproved: Boolean, approvedDeleteCount: Int, reachFailed: Boolean): ManualStart {
        if (isActive(AUTO, runningOnly = true)) return ManualStart.AutoRunning
        if (isActive(MANUAL, runningOnly = false)) return ManualStart.AlreadyRunning

        val data = Data.Builder()
            .putString(SyncWorker.KEY_TRIGGER, SyncWorker.TRIGGER_MANUAL)
            .putBoolean(SyncWorker.KEY_DELETE_APPROVED, deleteApproved)
            .putInt(SyncWorker.KEY_APPROVED_DELETE_COUNT, approvedDeleteCount)
            .putBoolean(SyncWorker.KEY_REACH_FAILED, reachFailed)
            .build()
        // 制約なし。従量制でも手動は通し、接続の可否は P3 の結果で決める
        val request = OneTimeWorkRequestBuilder<SyncWorker>().setInputData(data).build()
        workManager.enqueueUniqueWork(MANUAL, ExistingWorkPolicy.KEEP, request)
        return ManualStart.Started
    }

    /**
     * S5 の切り替え。T7 を更新してから登録または解除し、失敗したら T7 を戻す。成功なら真。
     */
    suspend fun setAutoEnabled(enabled: Boolean): Boolean {
        val before = store.setting(AUTO_ENABLED_KEY)
        store.putSetting(AUTO_ENABLED_KEY, enabled.toString())
        return try {
            if (enabled) registerAuto() else workManager.cancelUniqueWork(AUTO)
            true
        } catch (_: Exception) {
            store.putSetting(AUTO_ENABLED_KEY, before ?: false.toString())
            false
        }
    }

    private fun registerAuto() {
        val data = Data.Builder().putString(SyncWorker.KEY_TRIGGER, SyncWorker.TRIGGER_AUTO).build()
        // 従量制でないネットワークだけ。SSID は見ない（D13）
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val request = PeriodicWorkRequestBuilder<SyncWorker>(SyncValues.AUTO_INTERVAL_HOURS, TimeUnit.HOURS)
            .setInputData(data)
            .setConstraints(constraints)
            .build()
        // 同じ名前は 1 つだけ。既にあれば残す
        workManager.enqueueUniquePeriodicWork(AUTO, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private suspend fun isActive(name: String, runningOnly: Boolean): Boolean = withContext(Dispatchers.IO) {
        workManager.getWorkInfosForUniqueWork(name).get().any {
            it.state == WorkInfo.State.RUNNING ||
                (!runningOnly && (it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED))
        }
    }

    companion object {
        const val AUTO = "auto-sync"
        const val MANUAL = "manual-sync"
        const val AUTO_ENABLED_KEY = "auto_sync_enabled"
    }
}
