package playlistsyncfox.android.notify

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.Harness
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncStage
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.SyncTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationThrottleTest {
    private val interval = SyncValues.NOTIFICATION_INTERVAL_MS

    @Test
    fun `TA-113 前回の更新から V28 に満たない更新は捨て、V28 以上空いた更新は通す`() {
        val throttle = NotificationThrottle()
        val transfer = SyncProgress(SyncStage.Transfer, doneCount = 1)

        assertTrue(throttle.accept(transfer, now = 0))
        assertFalse(throttle.accept(transfer.copy(doneCount = 2), now = interval - 1))
        assertTrue(throttle.accept(transfer.copy(doneCount = 3), now = interval))
    }

    @Test
    fun `TA-114 間隔の内でも stage の変化と終了は通す`() {
        val throttle = NotificationThrottle()
        assertTrue(throttle.accept(SyncProgress(SyncStage.Manifest), now = 0))

        assertTrue(throttle.accept(SyncProgress(SyncStage.Transfer), now = 1))
        assertFalse(throttle.accept(SyncProgress(SyncStage.Transfer, doneCount = 1), now = 2))
        assertTrue(throttle.accept(SyncProgress(SyncStage.Finished), now = 3))
    }
}

class FinishedNotificationTest {
    private fun Harness.failWith(failure: ApiFailure) {
        lanApi.digestResult = ApiResult.Fail(failure)
    }

    private fun Harness.unreachable() {
        for (api in listOf(lanApi, tailApi, usbApi)) api.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
    }

    private val scenarios = listOf<Pair<String, Harness.() -> Unit>>(
        "E1" to { unreachable() },
        "E2" to { failWith(ApiFailure.Rejected(ErrorDetail.NoDetail, keyMismatch = true)) },
        "P3 の E4" to { failWith(ApiFailure.XmlUnavailable) },
        "P3 の 500" to { failWith(ApiFailure.ServerFault) },
    )

    @Test
    fun `TA-134 手動で T3 に残して終えた失敗は終了の通知を出す`() = runTest {
        for ((name, setup) in scenarios) {
            val manual = Harness(SyncTrigger.Manual).also(setup)
            manual.run()
            assertEquals(1, manual.finished.size, "手動：$name")
        }
    }

    @Test
    fun `TA-134 自動で T3 に残して終えた失敗は静かな通知として終了の通知を出す`() = runTest {
        // 自動の E1 は何も残さない（次のテスト）ので、E2、E4、500 だけ
        for ((name, setup) in scenarios.drop(1)) {
            val auto = Harness(SyncTrigger.Auto).also(setup)
            auto.run()
            assertEquals(1, auto.finished.size, "自動：$name")
            assertEquals(SyncTrigger.Auto, auto.finished.single().third)
        }
    }

    @Test
    fun `TA-134 何もせず終わった自動は終了の通知も前面の通知も出さない`() = runTest {
        val unreachable = Harness(SyncTrigger.Auto).also { it.unreachable() }
        unreachable.run()
        assertTrue(unreachable.finished.isEmpty())
        assertFalse("promote" in unreachable.events)

        // 手動の直後（V19 の内）の抑止
        val suppressed = Harness(SyncTrigger.Auto)
        suppressed.store.addRun(
            SyncRun(
                deviceId = DEVICE,
                trigger = SyncRun.TRIGGER_MANUAL,
                startedAt = suppressed.clock - 1000,
                endedAt = suppressed.clock - 500,
                result = SyncRun.RESULT_SUCCESS,
                contentDigest = "digest-1",
                reported = 1,
            ),
        )
        suppressed.run()
        assertTrue(suppressed.finished.isEmpty())
        assertFalse("promote" in suppressed.events)
    }

    @Test
    fun `TA-134 自動の終了の通知は静かなチャンネルに出し、手動は既定のチャンネルに出す`() {
        assertEquals(SyncNotifications.CHANNEL_RESULT, SyncNotifications.resultChannel(SyncTrigger.Manual))
        assertEquals(SyncNotifications.CHANNEL_RESULT_QUIET, SyncNotifications.resultChannel(SyncTrigger.Auto))
    }
}
