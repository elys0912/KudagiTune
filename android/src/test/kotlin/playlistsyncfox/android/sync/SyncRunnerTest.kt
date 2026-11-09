package playlistsyncfox.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.Harness
import playlistsyncfox.android.ROOT
import playlistsyncfox.android.TrackScript
import playlistsyncfox.android.db.PendingTransfer
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.db.decodeResult
import playlistsyncfox.android.id
import playlistsyncfox.android.manifestJson
import playlistsyncfox.android.manifestOf
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.pairing
import playlistsyncfox.android.track
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.ProgressReport
import playlistsyncfox.shared.result.ProgressStage
import playlistsyncfox.shared.result.readProgressReport
import playlistsyncfox.shared.result.SyncError
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import playlistsyncfox.shared.result.UnplayableReason
import playlistsyncfox.shared.result.WriteStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MIN = 60_000L

private fun Harness.sync(tracks: List<ManifestTrack>, playlists: List<ManifestPlaylist> = emptyList(), digest: String = "digest-1") {
    setManifest(manifestOf(tracks, playlists, digest = digest))
    serveTracks(tracks)
}

/** T1 の行と、その実体（MediaStore の行）を作る。内容は [tracks] のマニフェストの値と同じ。 */
private fun Harness.seedPlaced(tracks: List<ManifestTrack>, state: String = PlacedTrack.STATE_PLACED) {
    for (t in tracks) {
        val path = "Artist/Album/${t.fileName}"
        val uri = media.addExisting("${ROOT}Artist/Album/", t.fileName, bytes = t.size.toInt())
        store.placed[t.persistentId] = PlacedTrack(DEVICE, t.persistentId, path, uri, t.size, t.modifiedAt, t.size, state, 0)
    }
}

private fun Harness.addRun(
    trigger: String = SyncRun.TRIGGER_AUTO,
    result: String = SyncRun.RESULT_SUCCESS,
    startedAt: Long = clock - 600 * MIN,
    endedAt: Long? = startedAt + 1000,
    errorKind: String? = null,
    digest: String? = null,
    libraryId: String? = null,
    reported: Int = 1,
    resultJson: String? = null,
) = store.addRun(
    SyncRun(
        deviceId = DEVICE, trigger = trigger, startedAt = startedAt, endedAt = endedAt, result = result,
        errorKind = errorKind, contentDigest = digest, libraryId = libraryId, reported = reported, resultJson = resultJson,
    ),
)

private fun SyncRun.result() = decodeResult(resultJson!!)

class SyncRunnerTest {
    private val tracks = (1..3).map { track(it) }

    @Test
    fun `手動の同期で全曲が置かれ T3 に success が残り P4 で報告し終了を通知する`() = runTest {
        val h = Harness()
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals(3, row.added)
        assertEquals(3, h.store.placed.size)
        assertEquals("digest-1", row.contentDigest)
        assertEquals("LIB1", row.libraryId)
        assertEquals(1, row.reported)
        assertEquals(1, h.lanApi.reports.size)
        assertEquals(listOf("promote", "finished"), h.events)
        assertEquals(SyncStage.Finished, h.progress.value?.stage)
        assertEquals("Mix.m3u8", h.store.playlistRows.getValue(id(50)).relativePath)
        assertTrue(h.media.entries.values.none { it.pending })
    }

    @Test
    fun `TA-012 手動は保存した接続先が全て応えないと E1 で閉じて finished を立てる`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

        val row = assertNotNull(h.run())

        assertEquals(listOf(Transport.Lan, Transport.Tailscale), h.provider.opened.map { it.transport })
        assertEquals(SyncRun.RESULT_FAILED, row.result)
        assertEquals("E1", row.errorKind)
        assertEquals(0, row.reported)
        assertEquals(SyncRun.RESULT_FAILED, h.progress.value?.outcome)
        assertEquals(SyncStage.Finished, h.progress.value?.stage)
        assertEquals(1, h.finished.size)
    }

    @Test
    fun `TA-012 自動は応えなくても T3 に行を残さず診断のログ 1 行で終わる`() = runTest {
        val h = Harness(SyncTrigger.Auto)
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

        assertNull(h.run())

        assertTrue(h.runs.isEmpty())
        assertTrue(h.finished.isEmpty())
        assertFalse("promote" in h.events)
    }

    @Test
    fun `TA-013 P3 が署名の不一致を返すと同期を止め再試行せず T3 に E2 が残る`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Rejected(ErrorDetail.NoDetail, keyMismatch = true))

        val row = assertNotNull(h.run())

        assertEquals("E2", row.errorKind)
        assertEquals(1, h.lanApi.digestCalls)
        assertEquals(0, h.tailApi.digestCalls)
        assertEquals(0, h.lanApi.manifestCalls)
        assertEquals(true, row.result().keyMismatch)
        assertEquals(ErrorDetail.NoDetail, row.result().errorDetail)
    }

    @Test
    fun `TA-047 手動も自動も P3 の E2 を T3 に残し空の件数と key_mismatch と error_detail を書く`() = runTest {
        for (trigger in listOf(SyncTrigger.Manual, SyncTrigger.Auto)) {
            val h = Harness(trigger)
            h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Rejected(ErrorDetail.ClockSkew, keyMismatch = false))

            val row = assertNotNull(h.run(), trigger.name)

            val result = row.result()
            assertEquals(0, result.counts.toAdd)
            assertEquals(false, result.keyMismatch)
            assertEquals(ErrorDetail.ClockSkew, result.errorDetail)
            assertEquals(0, row.reported)
            assertEquals(1, h.finished.size)
            assertEquals(trigger, h.finished.single().third)
        }
    }

    @Test
    fun `TA-074 Keystore の別名が無ければ P3 を送らず key_mismatch が真の E2 で残す`() = runTest {
        for (trigger in listOf(SyncTrigger.Manual, SyncTrigger.Auto)) {
            val h = Harness(trigger)
            h.aliasExists = false

            val row = assertNotNull(h.run(), trigger.name)

            assertEquals("E2", row.errorKind)
            assertEquals(true, row.result().keyMismatch)
            assertEquals(0, h.lanApi.digestCalls + h.tailApi.digestCalls)
        }
    }

    @Test
    fun `TA-054 証明書の固定が合わない接続先は応えないと同じで次を試し E2 にならない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.PinMismatch)
        h.setManifest(manifestOf(tracks), h.tailApi)
        h.serveTracks(tracks, h.tailApi)

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals("tailscale", h.store.pairingRow?.lastEndpoint)
    }

    @Test
    fun `TA-054 全件が応えず 1 件でも不一致があれば手動も自動も E2 で残る`() = runTest {
        for (trigger in listOf(SyncTrigger.Manual, SyncTrigger.Auto)) {
            val h = Harness(trigger)
            h.lanApi.digestResult = ApiResult.Fail(ApiFailure.PinMismatch)
            h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

            val row = assertNotNull(h.run(), trigger.name)

            assertEquals("E2", row.errorKind)
            assertEquals(true, row.result().keyMismatch)
            assertEquals(0, row.reported)
        }
    }

    @Test
    fun `TA-054 全件が応えず不一致も無ければ E1`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

        assertEquals("E1", assertNotNull(h.run()).errorKind)
    }

    @Test
    fun `TA-070 P3 の 503 と 500 は次の接続先を試さず手動も自動も T3 に残す`() = runTest {
        for (trigger in listOf(SyncTrigger.Manual, SyncTrigger.Auto)) {
            for ((failure, kind) in listOf(ApiFailure.XmlUnavailable to "E4", ApiFailure.ServerFault to null)) {
                val h = Harness(trigger)
                h.lanApi.digestResult = ApiResult.Fail(failure)

                val row = assertNotNull(h.run())

                assertEquals(kind, row.errorKind)
                assertEquals(SyncRun.RESULT_FAILED, row.result)
                assertEquals(0, h.tailApi.digestCalls)
                assertNull(row.contentDigest)
                assertNull(row.libraryId)
                assertEquals(1, h.finished.size)
                assertNull(autoSuppression(h.store.runRows, pairedAt = 0, now = h.clock))
            }
        }
    }

    @Test
    fun `TA-039 last_endpoint の方式を先頭に試し失敗したら残りを順に試す`() = runTest {
        val h = Harness()
        h.store.pairingRow = pairing(lastEndpoint = "tailscale", usb = "192.0.2.20:47800")
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.usbApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.sync(tracks)

        h.run()

        assertEquals(listOf(Transport.Tailscale, Transport.Lan), h.provider.opened.map { it.transport })
        assertEquals("lan", h.store.pairingRow?.lastEndpoint)
    }

    @Test
    fun `TA-061 NSD の引き直しで前方一致する PC は保存と別のサービス名でも使い endpoint_lan を更新する`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.lanServices = listOf(LanService("psfox-01234567-2", Harness.LAN_HOST, 47800, "01234567"))

        h.run()

        assertEquals(Transport.Lan, h.provider.opened.first().transport)
        assertEquals("psfox-01234567-2", h.store.pairingRow?.endpointLan)
    }

    @Test
    fun `TA-061 前方一致しない PC と見つからないサービスは使わず次の接続先へ`() = runTest {
        for (found in listOf(listOf(LanService("psfox-ffffffff", Harness.LAN_HOST, 47800, "ffffffff")), emptyList())) {
            val h = Harness()
            h.sync(tracks)
            h.setManifest(manifestOf(tracks), h.tailApi)
            h.lanServices = found

            h.run()

            assertEquals(listOf(Transport.Tailscale), h.provider.opened.map { it.transport })
            assertEquals("psfox-01234567", h.store.pairingRow?.endpointLan)
        }
    }

    @Test
    fun `TA-065 NSD の引き直しが V14 を超えたらその接続先は使わず次へ進む`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.setManifest(manifestOf(tracks), h.tailApi)
        h.lanDelayMs = SyncValues.NSD_RESOLVE_WAIT_MS + 1

        h.run()

        assertEquals(listOf(Transport.Tailscale), h.provider.opened.map { it.transport })
    }

    @Test
    fun `TA-064 手動で reach_failed が真なら接続を試さず E1 の手順へ進み偽なら順に試す`() = runTest {
        val skipped = Harness()
        skipped.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

        val row = assertNotNull(skipped.run(reachFailed = true))

        assertEquals("E1", row.errorKind)
        assertTrue(skipped.provider.opened.isEmpty())

        val tried = Harness()
        tried.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        tried.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        tried.run(reachFailed = false)
        assertEquals(2, tried.provider.opened.size)
    }

    @Test
    fun `TA-071 手動は到達確認の前に前面へ昇格するので E1 で終わる場合も昇格済み`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        var discoveredAfterPromote = false
        h.onDiscover = { discoveredAfterPromote = "promote" in h.events }

        h.run()

        assertTrue(discoveredAfterPromote)
    }

    @Test
    fun `TA-053 抑止で終わる自動は前面の通知を出さず抑止されない自動は出す`() = runTest {
        // 届かない自動
        val unreachable = Harness(SyncTrigger.Auto)
        unreachable.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        unreachable.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        unreachable.run()
        assertFalse("promote" in unreachable.events)

        // digest が同じで抑止される自動
        val same = Harness(SyncTrigger.Auto)
        same.sync(tracks)
        same.addRun(digest = "digest-1")
        assertNull(same.run())
        assertFalse("promote" in same.events)

        // 抑止されない自動
        val go = Harness(SyncTrigger.Auto)
        go.sync(tracks)
        assertNotNull(go.run())
        assertEquals("promote", go.events.first())
    }

    @Test
    fun `TA-053 始めの整理と到達確認の間の stage は connecting`() = runTest {
        val h = Harness()
        h.sync(tracks)
        var stage: SyncStage? = null
        h.onDiscover = { stage = h.progress.value?.stage }

        h.run()

        assertEquals(SyncStage.Connecting, stage)
    }

    @Test
    fun `TA-062 前面への昇格が拒否されたら手動も自動も T3 に残さず終える`() = runTest {
        val manual = Harness()
        manual.promoteResult = false
        manual.sync(tracks)
        assertNull(manual.run())
        assertTrue(manual.runs.isEmpty())
        assertTrue(manual.logs.isNotEmpty())

        val auto = Harness(SyncTrigger.Auto)
        auto.promoteResult = false
        auto.sync(tracks)
        assertNull(auto.run())
        assertTrue(auto.runs.isEmpty())
        assertTrue(auto.store.placed.isEmpty())
    }

    @Test
    fun `TA-025 自動は前回の完了と同じ digest で success なら同期せず T3 に残さない`() = runTest {
        val h = Harness(SyncTrigger.Auto)
        h.sync(tracks)
        h.addRun(digest = "digest-1")

        assertNull(h.run())

        assertEquals(1, h.runs.size)
        assertTrue(h.store.placed.isEmpty())
    }

    @Test
    fun `TA-025 基準の行が partial なら自動は続行し手動は同じ digest でも常に続行する`() = runTest {
        val partial = Harness(SyncTrigger.Auto)
        partial.sync(tracks)
        partial.addRun(result = SyncRun.RESULT_PARTIAL, digest = "digest-1")
        assertNotNull(partial.run())

        val manual = Harness()
        manual.sync(tracks)
        manual.addRun(digest = "digest-1")
        assertNotNull(manual.run())
        assertEquals(3, manual.store.placed.size)
    }

    @Test
    fun `TA-063 手動の直後の自動は P3 の前に抑止し T3 に残さない`() = runTest {
        val h = Harness(SyncTrigger.Auto)
        h.sync(tracks)
        h.addRun(trigger = SyncRun.TRIGGER_MANUAL, startedAt = h.clock - 10 * MIN, endedAt = h.clock - 10 * MIN)

        assertNull(h.run())

        assertEquals(0, h.lanApi.digestCalls)
        assertEquals(1, h.runs.size)
    }

    @Test
    fun `TA-035 手動が実行中か待機中の自動は何もせず終え手動の ended_at が空の行を閉じない`() = runTest {
        val h = Harness(SyncTrigger.Auto)
        h.sync(tracks)
        val open = h.addRun(trigger = SyncRun.TRIGGER_MANUAL, endedAt = null, startedAt = h.clock - MIN)
        h.manualActive = true

        assertNull(h.run())

        assertEquals(open, h.runs.single())
        assertEquals(0, h.lanApi.digestCalls)
    }

    @Test
    fun `TA-035 同じプロセスで別の同期が動いている間は始めない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.gate.lock()

        assertNull(h.run())

        assertTrue(h.runs.isEmpty())
    }

    @Test
    fun `TA-027 強制終了の後は T5 の回収と pending の m3u8 の削除と ended_at が空の行を interrupted で閉じる`() = runTest {
        val h = Harness()
        h.sync(emptyList())
        val staleUri = h.media.insertPendingTrack("Old.mp3", "audio/mpeg", "${ROOT}A/B/")!!
        h.store.pendingRows[id(9)] = PendingTransfer(id(9), staleUri, 10, 5, 1, h.clock - 8L * 24 * 60 * MIN)
        val orphan = h.media.insertPendingPlaylist("Half.m3u8", "audio/x-mpegurl", ROOT)!!
        val open = h.addRun(startedAt = h.clock - 30 * MIN, endedAt = null, result = SyncRun.RESULT_FAILED, reported = 0)

        h.run()

        assertTrue(h.store.pendingRows.isEmpty())
        assertTrue(staleUri !in h.media.entries)
        assertTrue(orphan !in h.media.entries)
        val closed = h.runs.first { it.runId == open.runId }
        assertEquals(SyncRun.RESULT_INTERRUPTED, closed.result)
        assertEquals(closed.startedAt, closed.endedAt)
        assertNull(closed.errorKind)
        assertEquals(1, closed.reported)
        // 閉じた跡（reported が 1）は再送しない。送るのは今回の行の 1 件だけ
        assertEquals(1, h.lanApi.reports.size)
    }

    @Test
    fun `TA-048 未送信の行は P3 が通った後に古い順に全部 1 回ずつ再送し失敗は次の同期で再び試す`() = runTest {
        val h = Harness()
        h.sync(emptyList())
        val older = h.addRun(startedAt = h.clock - 300 * MIN, reported = 0, resultJson = sampleJson("2026-11-01T00:00:00.000Z"))
        val newer = h.addRun(startedAt = h.clock - 200 * MIN, reported = 0, resultJson = sampleJson("2026-11-02T00:00:00.000Z"))
        h.lanApi.reportResult = ApiResult.Fail(ApiFailure.Disconnected)

        val row = assertNotNull(h.run())

        assertEquals(3, h.lanApi.reports.size)
        assertTrue("2026-11-01" in h.lanApi.reports[0])
        assertTrue("2026-11-02" in h.lanApi.reports[1])
        assertEquals(listOf(0, 0), listOf(older, newer).map { id -> h.runs.first { it.runId == id.runId }.reported })
        assertEquals(0, row.reported)

        // 次の同期で再び送る
        h.lanApi.reportResult = ApiResult.Ok(Unit)
        h.lanApi.reports.clear()
        h.clock += 10 * MIN
        h.run()
        assertEquals(4, h.lanApi.reports.size)
        assertTrue(h.runs.all { it.reported == 1 })
    }

    @Test
    fun `TA-048 抑止に当たる自動と届かない同期は P4 を送らない`() = runTest {
        val suppressed = Harness(SyncTrigger.Auto)
        suppressed.sync(tracks)
        suppressed.addRun(trigger = SyncRun.TRIGGER_MANUAL, startedAt = suppressed.clock - 5 * MIN, endedAt = suppressed.clock - 5 * MIN, reported = 1)
        suppressed.addRun(startedAt = suppressed.clock - 300 * MIN, reported = 0, resultJson = sampleJson("2026-11-01T00:00:00.000Z"))
        suppressed.run()
        assertTrue(suppressed.lanApi.reports.isEmpty())

        val down = Harness()
        down.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        down.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        down.addRun(startedAt = down.clock - 300 * MIN, reported = 0, resultJson = sampleJson("2026-11-01T00:00:00.000Z"))
        down.run()
        assertTrue(down.lanApi.reports.isEmpty() && down.tailApi.reports.isEmpty())
    }

    @Test
    fun `TA-049 手動の E1 で閉じた行は reported が 0 のまま次の P3 の後に再送される`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        val failed = assertNotNull(h.run())
        assertEquals(0, failed.reported)
        assertNotNull(failed.resultJson)

        h.sync(emptyList())
        h.clock += MIN
        h.run()

        assertEquals(2, h.lanApi.reports.size)
        assertEquals("failed", Regex("\"outcome\":\"(\\w+)\"").find(h.lanApi.reports[0])?.groupValues?.get(1))
    }

    @Test
    fun `TA-014 差分の合計に V11 を足した値が空きと同値なら始める`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.freeBytes = 300 + SyncValues.STORAGE_MARGIN_BYTES

        assertEquals(SyncRun.RESULT_SUCCESS, assertNotNull(h.run()).result)
    }

    @Test
    fun `TA-014 空きより 1 バイト多ければ始めず not_started で不足分を渡す`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.freeBytes = 300 + SyncValues.STORAGE_MARGIN_BYTES - 1

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_NOT_STARTED, row.result)
        assertEquals("E3", row.errorKind)
        assertEquals(1L, h.progress.value?.shortageBytes)
        assertTrue(h.lanApi.trackRequests.isEmpty())
        assertTrue(h.store.placed.isEmpty())
        assertEquals(1, h.finished.size)
    }

    @Test
    fun `TA-015 書き込み中の容量不足は 2 曲目の pending を消して止め 1 曲目は残る`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.media.diskFullAtTotalBytes = 150

        val row = assertNotNull(h.run())

        assertEquals("E3", row.errorKind)
        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertEquals(listOf(id(1)), h.store.placed.keys.toList())
        assertTrue(h.store.pendingRows.isEmpty())
        assertEquals(1, h.media.entries.size)
        assertEquals(2, h.lanApi.trackRequests.size)
    }

    @Test
    fun `TA-016 曲単位の E5 は 2 曲目を飛ばして 3 曲目まで続け T6 の failed に残る`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = { if (it.persistentId == id(2)) TrackScript.Fail(ApiFailure.FileMissing) else serve(it) }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals(setOf(id(1), id(3)), h.store.placed.keys)
        val issue = h.store.issueRows.single { it.kind == RunIssue.KIND_FAILED }
        assertEquals(id(2), issue.persistentId)
        assertEquals("E5", issue.errorKind)
        assertEquals("Song 2", issue.title)
    }

    @Test
    fun `TA-016 曲単位の E9 は行を残さず次の曲へ進む`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = {
            h.media.readBackSizeDelta = if (it.persistentId == id(2)) -1 else 0
            serve(it)
        }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals(setOf(id(1), id(3)), h.store.placed.keys)
        assertEquals("E9", h.store.issueRows.single { it.kind == RunIssue.KIND_FAILED }.errorKind)
        assertEquals(2, h.media.entries.size)
    }

    @Test
    fun `m3u8 の書き込みに失敗したプレイリストは E9 にして続け、T3 は partial で T6 の failed に残る`() = runTest {
        val h = Harness()
        h.seedPlaced(tracks)
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
        h.media.failInsert = true

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals("E9", row.errorKind)
        assertEquals(SyncError.WriteFailed(id(50), "Mix", WriteStep.MediaStoreRegistration), row.result().errors.single())
        assertTrue(h.store.playlistRows.isEmpty())
        val issue = h.store.issueRows.single { it.kind == RunIssue.KIND_FAILED }
        assertEquals(id(50), issue.persistentId)
        assertEquals("Mix", issue.title)
        assertEquals("E9", issue.errorKind)
    }

    @Test
    fun `TA-016 TA-058 応答が合わない E8 は終わりに 1 回だけ取り直し再び E8 なら retried つきで結果に載る`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = { if (it.persistentId == id(2)) TrackScript.Body(Harness.contentOf(tracks[1]), modifiedHeader = 99) else serve(it) }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals(2, h.lanApi.manifestCalls)
        val stale = row.result().errors.single()
        assertEquals(SyncError.ManifestStale(id(2), "Song 2", retried = true), stale)
        assertEquals(setOf(id(1), id(3)), h.store.placed.keys)
        assertEquals(2, h.lanApi.trackRequests.count { it.persistentId == id(2) })
    }

    @Test
    fun `TA-058 取り直しの後に成功すればその曲は取得済みで結果にエラーが残らない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        var first = true
        h.lanApi.trackScript = {
            if (it.persistentId == id(2) && first) {
                first = false
                TrackScript.Fail(ApiFailure.Stale)
            } else {
                serve(it)
            }
        }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals(3, h.store.placed.size)
        assertTrue(row.result().errors.isEmpty())
    }

    @Test
    fun `TA-058 E8 の取り直しでは曲を数え直さず、進行状態と P8 の done は total を超えない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        var first = true
        h.lanApi.trackScript = {
            if (it.persistentId == id(2) && first) {
                first = false
                TrackScript.Fail(ApiFailure.Stale)
            } else {
                serve(it)
            }
        }

        h.run()

        val finalProgress = h.finished.single().second
        assertEquals(3, finalProgress.totalCount)
        assertEquals(3, finalProgress.doneCount)
        val sent = h.lanApi.progressBodies.map { assertNotNull(readProgressReport(it)) }
        assertTrue(sent.isNotEmpty() && sent.all { it.doneTracks <= it.totalTracks })
    }

    @Test
    fun `TA-072 取り直した差分の容量が空きと同値なら取得し 1 バイト足りなければ容量不足で止める`() = runTest {
        for ((shortBy, expectedRequests) in listOf(0L to 2, 1L to 1)) {
            val h = Harness()
            val one = listOf(track(1))
            h.sync(one)
            h.freeBytes = 1_000_000_000_000
            val serve = h.lanApi.trackScript
            var first = true
            h.lanApi.trackScript = {
                if (first) {
                    first = false
                    h.freeBytes = 100 + SyncValues.STORAGE_MARGIN_BYTES - shortBy
                    TrackScript.Body(Harness.contentOf(one[0]), modifiedHeader = 99)
                } else {
                    serve(it)
                }
            }

            val row = assertNotNull(h.run())

            assertEquals(expectedRequests, h.lanApi.trackRequests.size, "shortBy=$shortBy")
            if (shortBy > 0) assertEquals("E3", row.errorKind)
        }
    }

    @Test
    fun `TA-073 取り直しの P1 が失敗したら同期全体の失敗で digest と library_id は NULL で消さない`() = runTest {
        for (failure in listOf(ApiFailure.XmlUnavailable, ApiFailure.Disconnected)) {
            val h = Harness()
            h.seedPlaced(listOf(track(7)))
            val three = tracks
            h.setManifest(manifestOf(three))
            h.lanApi.manifestResults.addLast(ApiResult.Fail(failure))
            h.serveTracks(three)
            val serve = h.lanApi.trackScript
            h.lanApi.trackScript = { if (it.persistentId == id(2)) TrackScript.Fail(ApiFailure.Stale) else serve(it) }

            val row = assertNotNull(h.run(deleteApproved = true, approved = 9))

            assertNull(row.contentDigest, failure.toString())
            assertNull(row.libraryId)
            assertTrue(id(7) in h.store.placed, "消す処理は行わない")
        }
    }

    @Test
    fun `TA-017 曲の途中の切断は V10 回まで同じ接続先で再開し超えたら止めて行と T5 を残す`() = runTest {
        val h = Harness()
        val one = listOf(track(1))
        h.sync(one)
        h.lanApi.trackScript = { TrackScript.Body(Harness.contentOf(one[0]), startAt = it.rangeFrom, disconnectAfter = 10) }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 10L, 20L, 30L), h.lanApi.trackRequests.map { it.rangeFrom })
        assertEquals(listOf(2000L, 4000L, 8000L), h.waits)
        assertEquals(SyncError.TransferInterrupted(id(1), 4), row.result().errors.single())
        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertEquals("E6", row.errorKind)
        assertEquals(40, h.store.pendingRows.getValue(id(1)).receivedBytes)
        assertEquals(1, h.media.entries.size)
        assertEquals(1, h.lanApi.digestCalls, "到達確認をやり直さない")
        assertEquals(0, h.tailApi.digestCalls)
    }

    @Test
    fun `TA-017 P3 の後の接続の確立の失敗も E6 として数え再試行の上限で止まる`() = runTest {
        val h = Harness()
        h.sync(listOf(track(1)))
        h.lanApi.trackScript = { TrackScript.Fail(ApiFailure.Disconnected) }

        val row = assertNotNull(h.run())

        assertEquals(4, h.lanApi.trackRequests.size)
        assertEquals("E6", row.errorKind)
        assertEquals(0, h.tailApi.digestCalls)
    }

    @Test
    fun `TA-066 P1 の受信中の切断はマニフェストの取得を V10 回まで再試行し超えたら E6 で止める`() = runTest {
        val h = Harness()
        h.lanApi.manifestResults.clear()
        h.lanApi.manifestResults += ApiResult.Fail(ApiFailure.Disconnected)

        val row = assertNotNull(h.run())

        assertEquals(4, h.lanApi.manifestCalls)
        assertEquals(listOf(2000L, 4000L, 8000L), h.waits)
        assertEquals(SyncError.TransferInterrupted(null, 4), row.result().errors.single())
        assertEquals(SyncRun.RESULT_FAILED, row.result)
    }

    @Test
    fun `TA-067 P1 の 503 は再試行せず取得も m3u8 も消す処理も行わず手動も自動も次の自動は続行する`() = runTest {
        for (trigger in listOf(SyncTrigger.Manual, SyncTrigger.Auto)) {
            val h = Harness(trigger)
            h.lanApi.manifestResults.clear()
            h.lanApi.manifestResults += ApiResult.Fail(ApiFailure.XmlUnavailable)
            h.seedPlaced(listOf(track(7)))

            val row = assertNotNull(h.run())

            assertEquals(1, h.lanApi.manifestCalls)
            assertEquals(SyncRun.RESULT_FAILED, row.result)
            assertEquals("E4", row.errorKind)
            assertNull(row.contentDigest)
            assertNull(row.libraryId)
            assertTrue(h.store.playlistRows.isEmpty())
            assertTrue(id(7) in h.store.placed)
            assertNull(autoSuppression(h.store.runRows, pairedAt = 0, now = h.clock))
        }
    }

    @Test
    fun `TA-068 P2 の 503 は曲単位にせず同期全体を止めて中断になり消す処理を行わない`() = runTest {
        val h = Harness()
        h.seedPlaced(listOf(track(7)))
        h.setManifest(manifestOf(tracks))
        h.lanApi.trackScript = { TrackScript.Fail(ApiFailure.XmlUnavailable) }

        val row = assertNotNull(h.run(deleteApproved = true, approved = 9))

        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertEquals("E4", row.errorKind)
        assertEquals(1, h.lanApi.trackRequests.size)
        assertTrue(id(7) in h.store.placed)
    }

    @Test
    fun `TA-051 P1 が成功した行は digest と library_id を持ち取れなかった行は NULL`() = runTest {
        val ok = Harness()
        ok.sync(tracks)
        val okRow = assertNotNull(ok.run())
        assertEquals("digest-1", okRow.contentDigest)
        assertEquals("LIB1", okRow.libraryId)

        val failed = Harness()
        failed.lanApi.manifestResults.clear()
        failed.lanApi.manifestResults += ApiResult.Fail(ApiFailure.XmlUnavailable)
        val failedRow = assertNotNull(failed.run())
        assertNull(failedRow.contentDigest)
        assertNull(failedRow.libraryId)
    }

    @Test
    fun `TA-018 時間切れ E7 で止まると T5 を残して interrupted と E7 で閉じ m3u8 は時間があれば書く`() = runTest {
        val h = Harness()
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
        h.stopCause = StopCause.Timeout

        val row = cancelDuringWrite(h)

        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertEquals("E7", row.errorKind)
        assertTrue(h.store.pendingRows.isNotEmpty())
        assertTrue(h.store.pendingRows.values.single().receivedBytes > 0)
        assertEquals(1, h.finished.size)
        assertEquals(id(50), h.store.playlistRows.keys.single())
    }

    @Test
    fun `TA-018 利用者の中断は error_kind が空の interrupted で T5 を残す`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.stopCause = StopCause.UserCancel

        val row = cancelDuringWrite(h)

        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertNull(row.errorKind)
        assertTrue(h.store.pendingRows.isNotEmpty())
        assertTrue(row.result().hasMore)
    }

    @Test
    fun `TA-018 T6 を書いた直後に取り消されても、T6 は重ならず T3 は閉じた結果のまま残る`() = runTest {
        val h = Harness()
        h.syncWithMissingSecondTrack()

        runCancelledBy(h) { job -> h.store.onIssuesAdded = { job.cancel() } }

        val row = h.runs.single()
        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals(1, h.store.issueRows.count { it.kind == RunIssue.KIND_FAILED })
        assertEquals(1, h.lanApi.reports.size)
    }

    @Test
    fun `TA-018 P4 の後の T3 の更新で取り消されても、二度目の記録と報告をせず結果は変わらない`() = runTest {
        val h = Harness()
        h.syncWithMissingSecondTrack()

        runCancelledBy(h) { job -> h.store.onRunFinished = { if (it.reported == 1) job.cancel() } }

        val row = h.runs.single()
        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals(1, h.store.issueRows.count { it.kind == RunIssue.KIND_FAILED })
        assertEquals(1, h.lanApi.reports.size)
    }

    @Test
    fun `TA-018 P4 の途中の取り消しは送信の失敗として飲まず呼び出し側へ渡し、T3 は閉じた結果のまま残る`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.lanApi.onReport = { throw CancellationException("P4 の途中で取り消し") }

        assertFailsWith<CancellationException> { h.run() }

        val row = h.runs.single()
        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals(0, row.reported)
        assertEquals(1, h.lanApi.reports.size)
    }

    /** 3 曲の同期で 2 曲目だけ PC にファイルが無い（E5）。T6 の `failed` に 1 行残る。 */
    private fun Harness.syncWithMissingSecondTrack() {
        sync(tracks)
        val serve = lanApi.trackScript
        lanApi.trackScript = { if (it.persistentId == id(2)) TrackScript.Fail(ApiFailure.FileMissing) else serve(it) }
    }

    /** 同期を走らせ、[install] が受けた [kotlinx.coroutines.Job] を試験側の合図で取り消す。 */
    private suspend fun kotlinx.coroutines.test.TestScope.runCancelledBy(h: Harness, install: (kotlinx.coroutines.Job) -> Unit) {
        val job = launch(start = CoroutineStart.LAZY) { h.run() }
        install(job)
        job.start()
        job.join()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.cancelDuringWrite(h: Harness): SyncRun {
        lateinit var job: kotlinx.coroutines.Job
        job = launch(start = CoroutineStart.LAZY) { h.run() }
        var writes = 0
        h.media.onWrite = { if (++writes == 2) job.cancel() }
        job.start()
        job.join()
        return h.runs.single()
    }

    @Test
    fun `TA-021 手動の消すは承認した件数以内なら実行し超えたら飛ばして removal_skipped が真になる`() = runTest {
        // 3 曲全部が消える（NeedsConfirmation）
        val within = Harness()
        within.seedPlaced((11..13).map { track(it) })
        within.sync(listOf(track(1)))
        within.run(deleteApproved = true, approved = 3)
        assertEquals(setOf(id(1)), within.store.placed.keys)

        val over = Harness()
        over.seedPlaced((11..13).map { track(it) })
        over.sync(listOf(track(1)))
        val row = assertNotNull(over.run(deleteApproved = true, approved = 2))
        assertEquals(4, over.store.placed.size)
        assertTrue(row.result().removalSkipped)
    }

    @Test
    fun `TA-042 確認を取り消すと足すは進み消す操作だけ飛ばして removal_skipped を真にして完了する`() = runTest {
        val h = Harness()
        h.seedPlaced((11..13).map { track(it) })
        h.sync(listOf(track(1)))

        val row = assertNotNull(h.run(deleteApproved = false))

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertEquals(4, h.store.placed.size)
        assertTrue(row.result().removalSkipped)
    }

    @Test
    fun `TA-059 消す操作が権限で拒まれた曲は E9 にして残りを続け、T1 の行は残る`() = runTest {
        val h = Harness()
        h.seedPlaced((11..13).map { track(it) })
        h.sync(listOf(track(1)))
        h.media.deniedDeletes += h.store.placed.getValue(id(12)).mediaUri

        val row = assertNotNull(h.run(deleteApproved = true, approved = 3))

        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals("E9", row.errorKind)
        assertEquals(2, row.removed)
        assertEquals(SyncError.WriteFailed(id(12), "Song 12.mp3", WriteStep.MediaStoreRegistration), row.result().errors.single())
        assertEquals(setOf(id(1), id(12)), h.store.placed.keys)
    }

    @Test
    fun `TA-022 承認は実行 1 回だけ有効で走り直しでは捨てて飛ばす`() = runTest {
        val h = Harness()
        h.seedPlaced((11..13).map { track(it) })
        h.sync(listOf(track(1)))

        val row = assertNotNull(h.run(deleteApproved = true, approved = 3, rerun = true))

        assertTrue(row.result().removalSkipped)
        assertEquals(4, h.store.placed.size)
    }

    @Test
    fun `TA-023 自動の消すは Allowed だけ実行しほかは飛ばして印を残す`() = runTest {
        // Allowed：3 曲中 1 曲だけ消える
        val allowed = Harness(SyncTrigger.Auto)
        allowed.seedPlaced((11..13).map { track(it) })
        allowed.sync((11..12).map { track(it) })
        allowed.run()
        assertEquals(setOf(id(11), id(12)), allowed.store.placed.keys)

        // NeedsConfirmation：全部が消える
        val confirm = Harness(SyncTrigger.Auto)
        confirm.seedPlaced((11..13).map { track(it) })
        confirm.sync(listOf(track(1)))
        val confirmRow = assertNotNull(confirm.run())
        assertTrue(confirmRow.result().removalSkipped)
        assertTrue((11..13).all { id(it) in confirm.store.placed })

        // LibraryChanged
        val changed = Harness(SyncTrigger.Auto)
        changed.seedPlaced((11..13).map { track(it) })
        changed.addRun(libraryId = "OLD", digest = "old")
        changed.sync((11..12).map { track(it) })
        val changedRow = assertNotNull(changed.run())
        assertTrue(changedRow.result().removalSkipped)
        assertEquals(3, changed.store.placed.size)
        assertEquals("OLD", changedRow.libraryId, "飛ばしたときは記録の library_id のまま")
    }

    @Test
    fun `TA-043 library_id が変わったとき手動の承認は消して新しい値を記録し 0 件なら確認なしで新しい値を記録する`() = runTest {
        val approved = Harness()
        approved.seedPlaced((11..13).map { track(it) })
        approved.addRun(libraryId = "OLD", digest = "old")
        approved.sync((11..12).map { track(it) })
        val approvedRow = assertNotNull(approved.run(deleteApproved = true, approved = 1))
        assertEquals("LIB1", approvedRow.libraryId)
        assertEquals(2, approved.store.placed.size)

        val nothing = Harness(SyncTrigger.Auto)
        nothing.seedPlaced((11..12).map { track(it) })
        nothing.addRun(libraryId = "OLD", digest = "old")
        nothing.sync((11..12).map { track(it) }, digest = "new")
        assertEquals("LIB1", assertNotNull(nothing.run()).libraryId)
    }

    @Test
    fun `TA-024 同期全体の失敗や中断や E なしの停止の後は消す操作を行わない`() = runTest {
        fun setup(): Harness = Harness().also {
            it.seedPlaced((11..13).map { n -> track(n) })
            it.sync((11..12).map { n -> track(n) } + track(1))
        }

        val disconnect = setup()
        disconnect.lanApi.trackScript = { TrackScript.Fail(ApiFailure.Disconnected) }
        disconnect.run(deleteApproved = true, approved = 9)
        assertTrue(id(13) in disconnect.store.placed, "E6")

        val fault = setup()
        fault.lanApi.trackScript = { TrackScript.Fail(ApiFailure.ServerFault) }
        val faultRow = assertNotNull(fault.run(deleteApproved = true, approved = 9))
        assertTrue(id(13) in fault.store.placed, "500")
        assertNull(faultRow.errorKind)
        assertEquals(SyncRun.RESULT_INTERRUPTED, faultRow.result)

        val cancel = setup()
        cancel.stopCause = StopCause.UserCancel
        cancelDuringWrite(cancel)
        assertTrue(id(13) in cancel.store.placed, "利用者の中断")
    }

    @Test
    fun `TA-055 差分が空で前回の完了と同じ digest なら m3u8 を書かず消さず T3 だけ記録する`() = runTest {
        val h = Harness()
        h.seedPlaced(tracks)
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
        h.addRun(digest = "digest-1")

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertTrue(h.store.playlistRows.isEmpty())
        assertEquals("digest-1", row.contentDigest)
    }

    @Test
    fun `TA-055 digest が違うか基準が partial か中断なら m3u8 を書く`() = runTest {
        for ((base, digest) in listOf(SyncRun.RESULT_SUCCESS to "other", SyncRun.RESULT_PARTIAL to "digest-1", SyncRun.RESULT_INTERRUPTED to "digest-1")) {
            val h = Harness()
            h.seedPlaced(tracks)
            h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
            h.addRun(result = base, digest = digest)

            h.run()

            assertEquals(1, h.store.playlistRows.size, "$base $digest")
        }
    }

    @Test
    fun `TA-075 間に digest が NULL の失敗の行があっても前の success と同じ digest なら省く`() = runTest {
        val h = Harness()
        h.seedPlaced(tracks)
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
        h.addRun(digest = "digest-1", startedAt = h.clock - 500 * MIN)
        h.addRun(result = SyncRun.RESULT_FAILED, digest = null, errorKind = "E4", startedAt = h.clock - 300 * MIN)

        h.run()

        assertTrue(h.store.playlistRows.isEmpty())
    }

    @Test
    fun `TA-076 基準より新しい強制終了の跡があれば digest が同じでも m3u8 を書く`() = runTest {
        val h = Harness()
        h.seedPlaced(tracks)
        h.sync(tracks, listOf(ManifestPlaylist(id(50), "Mix", tracks.map { it.persistentId })))
        h.addRun(digest = "digest-1", startedAt = h.clock - 500 * MIN)
        h.addRun(result = SyncRun.RESULT_INTERRUPTED, startedAt = h.clock - 300 * MIN, endedAt = h.clock - 300 * MIN, digest = null)

        h.run()

        assertEquals(1, h.store.playlistRows.size)
    }

    @Test
    fun `TA-057 MediaStore から消えた曲は足すに戻り置き換えの途中の曲は state を見ず size の違いで置き換える`() = runTest {
        val h = Harness()
        h.seedPlaced(tracks)
        h.media.entries.remove(h.store.placed.getValue(id(2)).mediaUri)
        // 3 曲目は置き換えの途中で止まった（T1 の size が古いまま）
        val old = h.store.placed.getValue(id(3))
        h.store.placed[id(3)] = old.copy(state = PlacedTrack.STATE_REPLACING, sourceSize = 50)
        h.sync(tracks)

        h.run()

        assertEquals(listOf(id(2), id(3)), h.lanApi.trackRequests.map { it.persistentId }.sorted())
        assertEquals(PlacedTrack.STATE_PLACED, h.store.placed.getValue(id(3)).state)
        assertTrue(h.store.placed.getValue(id(2)).mediaUri in h.media.entries)
    }

    @Test
    fun `TA-060 MediaStore に同じ相対パスの別ファイルがあれば接尾辞つきのパスで書く`() = runTest {
        val h = Harness()
        val one = listOf(track(1))
        h.sync(one)
        h.media.addExisting("${ROOT}Artist/Album/", "Song 1.mp3")

        h.run()

        val path = h.store.placed.getValue(id(1)).relativePath
        assertEquals("Artist/Album/Song 1 [${id(1).take(8)}].mp3", path)
    }

    @Test
    fun `TA-041 新規と再開と置き換えの要求に size と modified_at を付け応答の値が違えば E8`() = runTest {
        val h = Harness()
        val three = tracks
        h.sync(three)
        h.seedPlaced(listOf(three[2]))
        // 3 曲目は置き換え（size が違う）、2 曲目は再開
        h.store.placed[id(3)] = h.store.placed.getValue(id(3)).copy(sourceSize = 1)
        val uri = h.media.insertPendingTrack("Song 2.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(ByteArray(30))
        h.store.pendingRows[id(2)] = PendingTransfer(id(2), uri, 100, 30, 1, h.clock)

        h.run()

        val byId = h.lanApi.trackRequests.associateBy { it.persistentId }
        assertEquals(listOf(0L, 30L, 0L), listOf(id(1), id(2), id(3)).map { byId.getValue(it).rangeFrom })
        assertTrue(byId.values.all { it.size == 100L && it.modifiedAt == 1L })
    }

    @Test
    fun `TA-041 応答の modified_at が違えば新規も置き換えも再開も E8 になる`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.seedPlaced(listOf(tracks[2]))
        h.store.placed[id(3)] = h.store.placed.getValue(id(3)).copy(sourceSize = 1)
        val uri = h.media.insertPendingTrack("Song 2.mp3", "audio/mpeg", "${ROOT}Artist/Album/")!!
        h.media.entries.getValue(uri).data.write(ByteArray(30))
        h.store.pendingRows[id(2)] = PendingTransfer(id(2), uri, 100, 30, 1, h.clock)
        h.lanApi.trackScript = { TrackScript.Body(Harness.contentOf(tracks.first { t -> t.persistentId == it.persistentId }), startAt = it.rangeFrom, modifiedHeader = 2) }

        val row = assertNotNull(h.run())

        assertEquals(3, row.result().errors.count { it is SyncError.ManifestStale })
        assertNull(h.store.pendingRows[id(2)], "再開の曲が E8 になったら pending の行と T5 を消す")
    }

    @Test
    fun `TA-056 経過が V17 に達したら終わりの進行状態の near_limit が真で同期は止まらない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = {
            h.clock += SyncValues.NEAR_LIMIT_MS
            serve(it)
        }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
        assertTrue(h.finished.single().second.nearLimit)
    }

    @Test
    fun `除外と保護付きの曲は T6 に記録され転送されない`() = runTest {
        val h = Harness()
        val protectedTrack = track(9).copy(isProtected = true)
        h.sync(listOf(track(1), protectedTrack))

        val row = assertNotNull(h.run())

        assertEquals(listOf(id(1)), h.lanApi.trackRequests.map { it.persistentId })
        assertEquals(1, h.store.issueRows.count { it.kind == RunIssue.KIND_UNPLAYABLE && it.runId == row.runId })
    }

    @Test
    fun `ペアリングが無ければ何もせず終わる`() = runTest {
        val h = Harness()
        h.store.pairingRow = null

        assertNull(h.run())

        assertTrue(h.runs.isEmpty())
    }

    @Test
    fun `TA-232 曲ごとの行は waiting から transferring を経て done か failed になり止めた曲は waiting に戻り done_bytes は書いた分だけ進む`() = runTest {
        val h = Harness()
        val three = listOf(track(1), track(2), track(3))
        h.sync(three)
        // 要求の時点と、書き込みの 1 区切りごとの進行の状態を集める
        val seen = mutableListOf<SyncProgress>()
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = {
            seen += h.progress.value!!
            h.media.readBackSizeDelta = if (it.persistentId == id(2)) -1 else 0
            // 3 曲目は途中で切れ続け、V10 の回数で同期全体が止まる（E6）
            if (it.persistentId == id(3)) TrackScript.Body(Harness.contentOf(three[2]), startAt = it.rangeFrom, disconnectAfter = 10) else serve(it)
        }
        h.media.onWrite = { seen += h.progress.value!! }
        // 手順 7 の始めは、書き込みの前の最初の更新を流れから見る
        val flowSeen = mutableListOf<SyncProgress>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.progress.collect { it?.let(flowSeen::add) } }

        val row = assertNotNull(h.run())

        fun SyncProgress.states() = tracks.map { it.state }
        assertEquals(List(3) { TrackState.Waiting }, flowSeen.first { it.stage == SyncStage.Transfer }.states())
        val midway = seen.first { it.tracks[0].state == TrackState.Transferring && it.tracks[0].receivedBytes == 40L }
        assertEquals(40, midway.doneBytes)
        assertTrue(seen.any { it.states() == listOf(TrackState.Done, TrackState.Transferring, TrackState.Waiting) })
        val last = h.finished.single().second
        assertEquals(listOf(TrackState.Done, TrackState.Failed, TrackState.Waiting), last.states())
        assertEquals(9, last.tracks[1].errorNumber)
        assertEquals("E6", row.errorKind)
        assertEquals(100, row.bytesTransferred)
        assertEquals(100, last.doneBytes)
    }

    @Test
    fun `TA-233 P8 は同じ段なら V28 未満は送らず ちょうどと段の変化で送り P3 の前は送らない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = {
            // 1 曲目で V28 の 1 ミリ秒前まで、2 曲目でちょうどまで時計を進める
            if (it.persistentId == id(1)) h.clock += SyncValues.NOTIFICATION_INTERVAL_MS - 1
            if (it.persistentId == id(2)) h.clock += 1
            serve(it)
        }

        assertNotNull(h.run())

        val sent = h.lanApi.progressBodies.map { assertNotNull(readProgressReport(it)) }
        assertEquals(
            listOf(
                ProgressReport(ProgressStage.Manifest, 0, 0, 0, 0),
                ProgressReport(ProgressStage.Diff, 0, 0, 0, 0),
                ProgressReport(ProgressStage.Capacity, 0, 3, 0, 300),
                ProgressReport(ProgressStage.Transfer, 0, 3, 0, 300),
                // V28 ちょうどで送る更新。1 曲終え、2 曲目の 1 区切り目まで書いた分を含む
                ProgressReport(ProgressStage.Transfer, 1, 3, 140, 300),
                ProgressReport(ProgressStage.Playlist, 3, 3, 300, 300),
            ),
            sent,
        )
    }

    @Test
    fun `TA-233 P8 が切断と 500 を返しても同期の結果は変わらず再送もしない`() = runTest {
        val expected = Harness().also { it.sync(tracks) }.let { base -> assertNotNull(base.run()) to base.lanApi.progressBodies.size }
        for (failure in listOf(ApiFailure.Disconnected, ApiFailure.ServerFault)) {
            val h = Harness()
            h.sync(tracks)
            h.lanApi.progressResult = ApiResult.Fail(failure)

            val row = assertNotNull(h.run())

            assertEquals(expected.first.result, row.result)
            assertEquals(expected.first.added, row.added)
            assertEquals(expected.first.failed, row.failed)
            assertEquals(1, row.reported)
            assertEquals(expected.second, h.lanApi.progressBodies.size)
        }
    }

    @Test
    fun `TA-233 P3 が通らなければ P8 を送らない`() = runTest {
        val h = Harness()
        h.lanApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)
        h.tailApi.digestResult = ApiResult.Fail(ApiFailure.Disconnected)

        h.run()

        assertTrue(h.lanApi.progressBodies.isEmpty() && h.tailApi.progressBodies.isEmpty())
    }

    @Test
    fun `TA-239 何もせず終えるどの経路でも進行状態が消える`() = runTest {
        // 準備は TA-062（昇格の拒否）、TA-035（手動の実行中）、TA-025（内容が同じ）、TA-063（自動の抑止）と同じ
        val cases = mapOf<String, () -> Harness>(
            "前面への昇格の拒否" to { Harness().also { it.promoteResult = false } },
            "手動が実行中か待機中の自動" to { Harness(SyncTrigger.Auto).also { it.manualActive = true } },
            "内容が前回と同じ自動" to { Harness(SyncTrigger.Auto).also { it.addRun(digest = "digest-1") } },
            "自動の抑止" to {
                Harness(SyncTrigger.Auto).also {
                    it.addRun(trigger = SyncRun.TRIGGER_MANUAL, startedAt = it.clock - 10 * MIN, endedAt = it.clock - 10 * MIN)
                }
            },
        )

        for ((label, prepare) in cases) {
            val h = prepare()
            h.sync(tracks)

            assertNull(h.run(), label)

            assertNull(h.progress.value, label)
        }
    }

    @Test
    fun `TA-239 T3 の行を作る前に中断されたとき進行状態が消える`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.onDiscover = { throw CancellationException("中断") }

        assertFailsWith<CancellationException> { h.run() }

        assertNull(h.progress.value)
        assertTrue(h.runs.isEmpty())
    }

    @Test
    fun `TA-239 完了を出して終えた同期の進行状態は S4 のために残る`() = runTest {
        val h = Harness()
        h.sync(tracks)

        h.run()

        assertEquals(SyncStage.Finished, h.progress.value?.stage)
    }

    @Test
    fun `TA-238 想定外の例外は T3 の行を error_kind が空の failed で閉じ、ログに残し、進行状態を消して例外を出さない`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.lanApi.trackScript = { error("想定外") }

        val row = assertNotNull(h.run())

        assertEquals(SyncRun.RESULT_FAILED, row.result)
        assertNull(row.errorKind)
        assertEquals(1, row.reported)
        assertEquals(row, h.runs.single())
        assertTrue(h.logs.any { it.contains("想定外の例外") })
        assertNull(h.progress.value)
        assertTrue(h.finished.isEmpty())
    }

    @Test
    fun `TA-238 終わりの記録の後の例外は T3 の結果を変えずに呼び出し側へ渡す`() = runTest {
        val h = Harness()
        h.sync(tracks)
        h.failOnFinished = true

        assertFailsWith<IllegalStateException> { h.run() }

        assertEquals(SyncRun.RESULT_SUCCESS, h.runs.single().result)
    }

    @Test
    fun `TA-240 同じ曲で切断からの再開を挟んでも、その曲に受けた分を足し上げて bytes_transferred と done_bytes に数える`() = runTest {
        val h = Harness()
        val one = listOf(track(1))
        h.sync(one)
        var requests = 0
        // 1 回目は 40 バイトで切れ、2 回目で続きを受ける
        h.lanApi.trackScript = {
            TrackScript.Body(Harness.contentOf(one[0]), startAt = it.rangeFrom, disconnectAfter = if (requests++ == 0) 40 else null)
        }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 40L), h.lanApi.trackRequests.map { it.rangeFrom })
        assertEquals(100, row.bytesTransferred)
        assertEquals(100, h.finished.single().second.doneBytes)
    }

    @Test
    fun `TA-240 続きの位置を 416 で断られて最初から取り直しても、先に受けた分を落とさず足し上げる`() = runTest {
        val h = Harness()
        val one = listOf(track(1))
        h.sync(one)
        var requests = 0
        // 1 回目は 40 バイトで切れ、2 回目は続きの位置を断られ（416）、3 回目に最初から全部を受ける
        h.lanApi.trackScript = {
            when (requests++) {
                0 -> TrackScript.Body(Harness.contentOf(one[0]), disconnectAfter = 40)
                1 -> TrackScript.Fail(ApiFailure.RangeNotSatisfiable)
                else -> TrackScript.Body(Harness.contentOf(one[0]), startAt = it.rangeFrom)
            }
        }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 40L, 0L), h.lanApi.trackRequests.map { it.rangeFrom })
        assertEquals(140, row.bytesTransferred)
        assertEquals(140, h.finished.single().second.doneBytes)
    }

    @Test
    fun `TA-242 利用者の中断では、先に曲単位の失敗があっても error_kind が空で、失敗は件数と一覧に残る`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        h.lanApi.trackScript = { if (it.persistentId == id(1)) TrackScript.Fail(ApiFailure.FileMissing) else serve(it) }
        h.stopCause = StopCause.UserCancel

        val row = cancelDuringWrite(h)

        assertEquals(SyncRun.RESULT_INTERRUPTED, row.result)
        assertNull(row.errorKind)
        assertEquals(1, row.failed)
        assertEquals(listOf("E5"), h.store.issueRows.filter { it.kind == RunIssue.KIND_FAILED }.map { it.errorKind })
    }

    @Test
    fun `TA-245 正常に閉じた短い本文は 1 回目は E6 として続きから取り直し、増えれば完了する`() = runTest {
        val h = Harness()
        val one = listOf(track(1))
        h.sync(one)
        val content = Harness.contentOf(one[0])
        h.lanApi.trackScript = {
            if (it.rangeFrom == 0L) TrackScript.Body(content.copyOf(40)) else TrackScript.Body(content, startAt = it.rangeFrom)
        }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 40L), h.lanApi.trackRequests.map { it.rangeFrom })
        assertEquals(listOf(2000L), h.waits)
        assertEquals(SyncRun.RESULT_SUCCESS, row.result)
    }

    @Test
    fun `TA-245 続きから取り直しても 1 バイトも増えなければ、その曲を E9 にして pending を消し次の曲へ進む`() = runTest {
        val h = Harness()
        h.sync(tracks)
        val serve = h.lanApi.trackScript
        val short = Harness.contentOf(tracks[0]).copyOf(40)
        h.lanApi.trackScript = { if (it.persistentId == id(1)) TrackScript.Body(short, startAt = it.rangeFrom) else serve(it) }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 40L), h.lanApi.trackRequests.filter { it.persistentId == id(1) }.map { it.rangeFrom })
        assertEquals(listOf(2000L), h.waits)
        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals("E9", row.errorKind)
        assertEquals(setOf(id(2), id(3)), h.store.placed.keys)
        assertTrue(h.store.pendingRows.isEmpty())
        assertEquals(2, h.media.entries.size)
    }

    @Test
    fun `TA-245 置き換えの曲も、正常に閉じた短い本文が前の取得から増えなければ、その曲を E9 にして次の曲へ進む`() = runTest {
        val h = Harness()
        h.sync(tracks)
        // 1 曲目は置き換え（size が違う）。置き換えは毎回最初から書き直す
        h.seedPlaced(listOf(tracks[0]))
        h.store.placed[id(1)] = h.store.placed.getValue(id(1)).copy(sourceSize = 1)
        val serve = h.lanApi.trackScript
        val short = Harness.contentOf(tracks[0]).copyOf(40)
        h.lanApi.trackScript = { if (it.persistentId == id(1)) TrackScript.Body(short, startAt = it.rangeFrom) else serve(it) }

        val row = assertNotNull(h.run())

        assertEquals(listOf(0L, 0L), h.lanApi.trackRequests.filter { it.persistentId == id(1) }.map { it.rangeFrom })
        assertEquals(listOf(2000L), h.waits)
        assertEquals(SyncRun.RESULT_PARTIAL, row.result)
        assertEquals("E9", row.errorKind)
        assertEquals(PlacedTrack.STATE_PLACED, h.store.placed.getValue(id(2)).state)
        assertEquals(PlacedTrack.STATE_PLACED, h.store.placed.getValue(id(3)).state)
    }

    @Test
    fun `TA-246 マニフェストの版違いで止めたとき結果に印を残し、壊れた本文では印を付けない`() = runTest {
        val mismatch = Harness()
        mismatch.lanApi.manifestResults.clear()
        mismatch.lanApi.manifestResults += ApiResult.Ok("""{"format_version":99}""")
        val mismatchRow = assertNotNull(mismatch.run())
        assertEquals(SyncRun.RESULT_FAILED, mismatchRow.result)
        assertNull(mismatchRow.errorKind)
        assertTrue(mismatchRow.result().manifestVersionMismatch)

        val broken = Harness()
        broken.lanApi.manifestResults.clear()
        broken.lanApi.manifestResults += ApiResult.Ok("not json")
        val brokenRow = assertNotNull(broken.run())
        assertEquals(SyncRun.RESULT_FAILED, brokenRow.result)
        assertFalse(brokenRow.result().manifestVersionMismatch)
    }

    @Test
    fun `TA-244 端末が再生できない形式の曲は転送せず、T6 と結果の unplayable に unsupported_format で載せる`() = runTest {
        val h = Harness()
        val alac = track(2, extension = "m4a").copy(kind = "Apple Lossless audio file")
        val aac = track(3, extension = "m4a").copy(kind = "AAC audio file")
        h.sync(listOf(track(1), alac, aac, track(9).copy(isProtected = true)))
        // 端末にデコーダが無い
        val noDecoder = Playability { false }
        h.isPlayable = noDecoder::isPlayable

        val row = assertNotNull(h.run())

        assertEquals(setOf(id(1), id(3)), h.lanApi.trackRequests.map { it.persistentId }.toSet())
        assertNull(h.store.placed[id(2)])
        val reasons = h.store.issueRows.filter { it.kind == RunIssue.KIND_UNPLAYABLE }.associate { it.persistentId to it.reason }
        assertEquals(mapOf<String?, String?>(id(9) to "protected", id(2) to "unsupported_format"), reasons)
        assertEquals(listOf(UnplayableReason.Protected, UnplayableReason.UnsupportedFormat), row.result().unplayable.map { it.reason })
    }

    @Test
    fun `TA-244 端末にデコーダがあれば ALAC は unsupported_format に載せない`() = runTest {
        val h = Harness()
        h.sync(listOf(track(2, extension = "m4a").copy(kind = "Apple Lossless audio file")))
        val allDecoders = Playability { true }
        h.isPlayable = allDecoders::isPlayable

        val row = assertNotNull(h.run())

        assertEquals(listOf(id(2)), h.lanApi.trackRequests.map { it.persistentId })
        assertTrue(row.result().unplayable.isEmpty())
        assertTrue(h.store.issueRows.none { it.kind == RunIssue.KIND_UNPLAYABLE })
    }

    private fun sampleJson(startedAt: String): String {
        val result = playlistsyncfox.shared.result.SyncResult(
            startedAt = startedAt,
            finishedAt = startedAt,
            trigger = SyncTrigger.Manual,
            transport = Transport.Lan,
            outcome = SyncOutcome.Completed,
            hasMore = false,
            counts = playlistsyncfox.shared.result.Counts(0, 0, 0, 0, 0, 0, 0, 0),
            capacity = playlistsyncfox.shared.result.Capacity(0, 0, null, null),
            errors = emptyList(),
            excluded = emptyList(),
            unplayable = emptyList(),
            libraryId = null,
            contentDigest = null,
            unresolvedPlaylists = emptyList(),
            duplicatesDropped = 0,
            removalSkipped = false,
            keyMismatch = null,
            errorDetail = null,
        )
        return playlistsyncfox.android.db.encodeResult(result)
    }
}
