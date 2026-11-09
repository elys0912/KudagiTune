package playlistsyncfox.android.sync

import androidx.work.Data
import androidx.work.WorkInfo
import playlistsyncfox.shared.result.SyncTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWorkerTest {
    @Test
    fun `X6-14 時間切れの停止の理由は Timeout に写し、アプリからの取り消しと理由の不明と取得できない場合は UserCancel に写す`() {
        assertEquals(StopCause.Timeout, stopCauseOf(WorkInfo.STOP_REASON_TIMEOUT))
        assertEquals(StopCause.Timeout, stopCauseOf(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT))
        assertEquals(StopCause.UserCancel, stopCauseOf(WorkInfo.STOP_REASON_CANCELLED_BY_APP))
        assertEquals(StopCause.UserCancel, stopCauseOf(WorkInfo.STOP_REASON_UNKNOWN))
        assertEquals(StopCause.UserCancel, stopCauseOf(null))
    }

    @Test
    fun `TA-237 再試行の回数が 0 なら初回、1 以上なら走り直しとして入力を作る`() {
        val data = Data.Builder()
            .putString(SyncWorker.KEY_TRIGGER, SyncWorker.TRIGGER_MANUAL)
            .putBoolean(SyncWorker.KEY_DELETE_APPROVED, true)
            .putInt(SyncWorker.KEY_APPROVED_DELETE_COUNT, 5)
            .putBoolean(SyncWorker.KEY_REACH_FAILED, true)
            .build()

        val first = runInputOf(data, runAttemptCount = 0)
        assertFalse(first.rerun)
        assertEquals(SyncTrigger.Manual, first.trigger)
        assertTrue(first.deleteApproved)
        assertEquals(5, first.approvedDeleteCount)
        assertTrue(first.reachFailed)

        assertTrue(runInputOf(data, runAttemptCount = 1).rerun)
        assertTrue(runInputOf(data, runAttemptCount = 3).rerun)
    }

    @Test
    fun `X6-14 入力データが空なら自動で、承認も到達の失敗も無い入力にする`() {
        val input = runInputOf(Data.EMPTY, runAttemptCount = 0)

        assertEquals(SyncTrigger.Auto, input.trigger)
        assertFalse(input.deleteApproved)
        assertEquals(0, input.approvedDeleteCount)
        assertFalse(input.reachFailed)
    }
}
