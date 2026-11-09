package playlistsyncfox.android.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.encodeResult
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.net.SyncApi
import playlistsyncfox.android.storage.PlaylistWriteResult
import playlistsyncfox.android.storage.TrackBody
import playlistsyncfox.android.storage.TrackSource
import playlistsyncfox.android.storage.TrackWriteResult
import playlistsyncfox.android.storage.WritePlan
import playlistsyncfox.shared.diff.Diff
import playlistsyncfox.shared.diff.RemovalGate
import playlistsyncfox.shared.diff.SyncRecord
import playlistsyncfox.shared.diff.computeDiff
import playlistsyncfox.shared.diff.requiredBytes
import playlistsyncfox.shared.m3u8.buildM3u8
import playlistsyncfox.shared.m3u8.buildPlaylistFileName
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestReadResult
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.manifest.readManifest
import playlistsyncfox.shared.path.resolveTrackPaths
import playlistsyncfox.shared.result.ConnectionFailure
import playlistsyncfox.shared.result.ErrorDetail
import playlistsyncfox.shared.result.ErrorScope
import playlistsyncfox.shared.result.FileMissingReason
import playlistsyncfox.shared.result.KeyMismatchCause
import playlistsyncfox.shared.result.StoragePhase
import playlistsyncfox.shared.result.SyncError
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import playlistsyncfox.shared.result.classifyOutcome
import playlistsyncfox.shared.result.mayRemove
import playlistsyncfox.shared.result.WriteStep

/**
 * 同期の実行（X6-3 の手順）。判断は shared の関数、OS の呼び出しは [MediaWriter]・[SyncApi] に任せ、
 * ここは呼ぶ順と T3 の記録だけを持つ。結果は T3 に残し、残した行を返す（抑止などで残さなかったときは null）。
 */
class SyncRunner(private val env: SyncEnvironment) {
    suspend fun run(input: RunInput): SyncRun? {
        if (!env.gate.tryLock()) {
            env.log.line("別の同期が動いているため、何もせず終える")
            return null
        }
        val opened = mutableListOf<SyncApi>()
        // P8 は同期の手順とは別の流れで送る（X6-3）。実行の終わりで必ず片付ける
        val reporter = ProgressReporter(CoroutineScope(currentCoroutineContext() + SupervisorJob()), env.now)
        try {
            return Execution(input, opened, reporter).start()
        } finally {
            reporter.close()
            opened.forEach { runCatching { it.close() } }
            // 完了（Finished）を出さずに終えたときは、どの経路でも進行状態を消す（X6-1）。出した完了は S4 が読むので残す
            if (env.progress.value?.stage != SyncStage.Finished) env.progress.value = null
            env.gate.unlock()
        }
    }

    private sealed interface TrackStep {
        data object Done : TrackStep

        data class Failed(val error: SyncError) : TrackStep

        /** 同期全体を止める。[error] が null なら E なしの停止。 */
        data class Stop(val error: SyncError?, val keyMismatch: Boolean? = null, val detail: ErrorDetail? = null) : TrackStep
    }

    private sealed interface Fetch {
        class Loaded(val read: ManifestReadResult.Loaded) : Fetch

        class Stop(val step: TrackStep.Stop) : Fetch
    }

    private sealed interface Attempt {
        data object Stale : Attempt

        class Wrote(val result: TrackWriteResult) : Attempt
    }

    /** 1 回の取得の本文の受け取り。[bytes] は受けた量、[endedNormally] は取得元が切れずに閉じたか（X6-11）。 */
    private class BodyTally {
        var bytes = 0L
        var endedNormally = false
    }

    private inner class Execution(
        private val input: RunInput,
        private val opened: MutableList<SyncApi>,
        private val reporter: ProgressReporter,
    ) {
        private val trigger = input.trigger
        private val triggerText = if (trigger == SyncTrigger.Manual) SyncRun.TRIGGER_MANUAL else SyncRun.TRIGGER_AUTO
        private val startedAt = env.now()
        private var foregroundSince: Long? = null

        private lateinit var pairing: Pairing
        private lateinit var deviceId: String
        private var run: SyncRun? = null
        private var api: SyncApi? = null
        private var transport = Transport.Lan

        // 実行の途中の状態（終わりの記録に使う）
        private var manifest: Manifest? = null
        private var duplicatesDropped = 0
        private var diff: Diff? = null
        private var plannedAdd = 0
        private var plannedReplace = 0
        private var plannedBytes = 0L
        private val errors = mutableListOf<SyncError>()
        private var added = 0
        private var replaced = 0
        private var removed = 0
        private var bytesTransferred = 0L
        private var doneCount = 0
        private var transferStarted = false
        private var stoppedWithoutE = false
        private var keyMismatch: Boolean? = null
        private var errorDetail: ErrorDetail? = null
        private var storagePreCheck = false
        private var removalSkipped = false
        private var removalExecuted = false
        private var baseLibraryId: String? = null
        private var freeBefore: Long? = null
        private var skippedUnchanged = false
        private var recorded = false
        private var manifestVersionMismatch = false

        // 曲ごとの行と、転送中の曲のこの実行での転送量（done_bytes に足す。X6-3 の SyncProgress）
        private var rows: List<TrackRow> = emptyList()
        private var inFlightBytes = 0L

        // 端末が選んだプレイリストの ID。P3・P1 の本文で渡す（D15）。実行の始めに 1 回読み、実行の間は変えない
        private var selectedIds: List<String> = emptyList()

        // 保存先の切り替えの取り直しが済んでいない（T9 に切り替え前のファイルが残る）。D16
        private var relocating = false

        suspend fun start(): SyncRun? {
            pairing = env.store.pairing() ?: run {
                env.log.line("ペアリングが無いため、何もせず終える")
                return null
            }
            deviceId = pairing.deviceId
            // 0. 選択が空、または保存先が無いときは始めない（D15、D16）
            when (env.startBlock()) {
                StartBlock.NoSelection -> {
                    env.log.line("同期するプレイリストが選ばれていないため、何もせず終える")
                    return null
                }
                StartBlock.StorageMissing -> {
                    env.log.line("保存先が見つからないため、何もせず終える")
                    return null
                }
                null -> Unit
            }
            selectedIds = env.store.selectedPlaylists().map { it.playlistId }
            transport = transportOf(pairing.lastEndpoint) ?: Transport.Lan
            publish(SyncStage.Connecting)

            // 手動は開始直後（到達確認の前）に前面へ昇格する。自動は到達確認と抑止の後（J46）
            if (trigger == SyncTrigger.Manual) {
                if (!promote()) return null
            } else if (env.manualActive()) {
                env.log.line("手動の同期が動いているため、何もせず終える")
                return null
            }

            // 1. 始めの整理（端末内の処理だけ。抑止より前）
            env.writer.collectExpired()
            var runs = env.store.runs()
            for (open in runs.filter { it.endedAt == null }) {
                // 強制終了の跡。閉じた時刻は分からないので started_at と同じ値にし、再送しない
                env.store.finishRun(open.copy(endedAt = open.startedAt, result = SyncRun.RESULT_INTERRUPTED, errorKind = null, reported = 1))
            }
            env.writer.deleteOrphanPendingPlaylists()
            runs = env.store.runs()

            // 2. 到達確認と自動の抑止
            if (trigger == SyncTrigger.Auto) {
                val reason = autoSuppression(runs, pairing.pairedAt, env.now())
                if (reason != null) {
                    env.log.line("自動の同期を抑止した：$reason")
                    return null
                }
            }
            if (!env.aliasExists(pairing.keyAlias)) {
                // 署名の鍵が無い（端末の変更、OS の鍵の破棄）。P3 を送らず、再ペアリングへ誘導する
                return closeEarly(SyncError.KeyMismatch(KeyMismatchCause.SignatureMismatch), keyMismatch = true, detail = ErrorDetail.NoDetail)
            }

            val connected = when (val reach = reach()) {
                is Reach.Connected -> reach
                is Reach.Unreachable -> return when {
                    reach.mismatch -> closeEarly(SyncError.KeyMismatch(KeyMismatchCause.PinMismatch), keyMismatch = true, detail = ErrorDetail.PinMismatch)
                    trigger == SyncTrigger.Manual -> closeEarly(SyncError.ConnectionFailed(ConnectionFailure.Timeout))
                    else -> {
                        env.log.line("PC に届かないため、何もせず終える")
                        null
                    }
                }
                is Reach.Stopped -> return closeStopped(reach.failure)
            }
            api = connected.api
            transport = connected.endpoint.transport

            resendReports(connected.api, runs)

            if (trigger == SyncTrigger.Auto) {
                val base = digestBase(runs)
                if (base != null && base.contentDigest == connected.value && base.result == SyncRun.RESULT_SUCCESS &&
                    !env.writer.relocationNeeded(env.volume)
                ) {
                    env.log.line("内容が前回と同じため、何もせず終える")
                    return null
                }
                if (!promote()) return null
            }

            // 3. T3 に行を追加する
            run = SyncRun(deviceId = deviceId, trigger = triggerText, startedAt = startedAt).let { it.copy(runId = env.store.startRun(it)) }
            baseLibraryId = recordedLibraryId(runs)

            return try {
                execute(connected.api, runs)
            } catch (e: CancellationException) {
                // E7 と利用者の中断：T3 の更新は必須。m3u8 は時間があれば書く
                withContext(NonCancellable) {
                    val decision = decideStop(env.stopCause())
                    decision.error?.let { errors += it }
                    conclude(decision.userInterrupted, afterCancel = true)
                }
                throw e
            } catch (e: Exception) {
                // 終わりの記録（T3）の後の例外は、結果を変えずにワーカーへ渡す
                if (recorded) throw e
                closeAfterUnexpected(e)
            }
        }

        /**
         * 想定外の例外（M1、通信、保存の例外。X6-12）。T3 の行を「失敗」で閉じ、ログに残す。
         * `error_kind` は空。`result_json` が無く P4 の本文を作れないので、`reported`＝1 にして再送しない。
         */
        private suspend fun closeAfterUnexpected(cause: Exception): SyncRun {
            env.log.line("想定外の例外で同期を失敗として閉じた：${cause.javaClass.simpleName}")
            val closed = checkNotNull(run).copy(
                endedAt = env.now(),
                result = SyncRun.RESULT_FAILED,
                errorKind = null,
                added = added,
                replaced = replaced,
                removed = removed,
                failed = errors.count { it.scope == ErrorScope.Track },
                bytesTransferred = bytesTransferred,
                reported = 1,
            )
            env.store.finishRun(closed)
            return closed
        }

        private suspend fun execute(api: SyncApi, runs: List<SyncRun>): SyncRun {
            // 4. P1 でマニフェストを取り、差分を計算する
            publish(SyncStage.Manifest)
            val loaded = when (val fetched = fetchManifest(api)) {
                is Fetch.Stop -> {
                    applyStop(fetched.step)
                    return conclude(userInterrupted = false, afterCancel = false)
                }
                is Fetch.Loaded -> fetched.read
            }
            manifest = loaded.manifest
            duplicatesDropped = loaded.duplicatesDropped

            publish(SyncStage.Diff)
            // 保存先が変わっていれば、T1・T2 を T9 へ移して全曲を取り直す（D16）
            relocating = env.writer.relocateTo(env.volume)
            val placed = env.store.placedTracks(deviceId)
            val first = computeDiffFor(loaded.manifest, placed, runs)
            diff = first
            plannedAdd = first.toAdd.size
            plannedReplace = first.toReplace.size
            plannedBytes = requiredBytes(first)

            // 差分が空で、前回の完了と同じ内容なら、m3u8 の書き出しと「消す」を省く（J37）
            val base = digestBase(runs)
            if (!relocating && first.isEmpty() && base != null && base.contentDigest == loaded.manifest.contentDigest &&
                base.result == SyncRun.RESULT_SUCCESS && runs.none { isCrashTrace(it) && it.isAfterRun(base) }
            ) {
                skippedUnchanged = true
                return conclude(userInterrupted = false, afterCancel = false)
            }

            // 5. 容量の確認
            publish(SyncStage.Capacity)
            freeBefore = env.freeBytes()
            if (first.toAdd.isNotEmpty() || first.toReplace.isNotEmpty()) {
                val shortage = shortageOf(plannedBytes, freeBefore)
                if (shortage != null) {
                    errors += SyncError.InsufficientStorage(shortage, StoragePhase.PreCheck)
                    storagePreCheck = true
                    publish(SyncStage.Capacity, shortage = shortage)
                    return conclude(userInterrupted = false, afterCancel = false, skipPlaylists = true)
                }
            }

            // 7. 「足す」と「置き換える」を 1 曲ずつ直列に行う
            val stale = transferAll(api, first, placed, retried = false)
            if (!runStopped() && stale.isNotEmpty()) retryStale(api, runs)
            return conclude(userInterrupted = false, afterCancel = false)
        }

        private fun shortageOf(requiredBytes: Long, free: Long?): Long? {
            if (free == null) return null
            val needed = requiredBytes + SyncValues.STORAGE_MARGIN_BYTES
            return if (free < needed) needed - free else null
        }

        private suspend fun computeDiffFor(manifest: Manifest, placed: List<PlacedTrack>, runs: List<SyncRun>): Diff =
            computeDiff(
                tracks = manifest.tracks,
                records = placed.map { SyncRecord(it.persistentId, it.sourceSize, it.sourceModified, it.relativePath) },
                // T1 の曲のうち MediaStore に行が今ある曲（pending も含む）。差分の入力に state は渡さない
                presentIds = env.writer.presentIds(placed),
                manifestLibraryId = manifest.libraryId,
                recordedLibraryId = recordedLibraryId(runs),
                removalConfirmThreshold = SyncValues.REMOVAL_CONFIRM_THRESHOLD,
                unresolvedPlaylists = manifest.unresolvedPlaylists,
                isPlayable = env.isPlayable,
            )

        /** 全部の曲の後、E8 になった曲があれば、マニフェストを取り直して 1 回だけ行う。 */
        private suspend fun retryStale(api: SyncApi, runs: List<SyncRun>) {
            val loaded = when (val fetched = fetchManifest(api)) {
                is Fetch.Stop -> {
                    // 取り直しの P1 の失敗は同期全体の失敗。content_digest と library_id は NULL にする
                    applyStop(fetched.step)
                    manifest = null
                    return
                }
                is Fetch.Loaded -> fetched.read
            }
            manifest = loaded.manifest
            duplicatesDropped = loaded.duplicatesDropped
            val placed = env.store.placedTracks(deviceId)
            val again = computeDiffFor(loaded.manifest, placed, runs)
            diff = again

            // 取り直した対象の前回の記録（E8、同じ回の E5・E9）は、やり直しの結果で置き換える
            val targets = again.toAdd + again.toReplace
            val targetIds = targets.mapTo(HashSet()) { it.persistentId }
            errors.removeAll { it is SyncError.ManifestStale || (it.scope == ErrorScope.Track && trackIdOf(it) in targetIds) }

            if (targets.isEmpty()) return
            val shortage = shortageOf(requiredBytes(again), env.freeBytes())
            if (shortage != null) {
                // 転送の後なので not_started にはしない
                errors += SyncError.InsufficientStorage(shortage, StoragePhase.Writing)
                return
            }
            transferAll(api, again, placed, retried = true)
        }

        private fun runStopped() = errors.any { it.scope == ErrorScope.Run } || stoppedWithoutE

        private fun trackIdOf(error: SyncError): String? = when (error) {
            is SyncError.FileMissing -> error.persistentId
            is SyncError.ManifestStale -> error.persistentId
            is SyncError.WriteFailed -> error.persistentId
            else -> null
        }

        /** 差分の「足す」「置き換える」を 1 曲ずつ行う。E8 になった曲の ID を返す（取り直しでは空）。 */
        private suspend fun transferAll(api: SyncApi, target: Diff, placed: List<PlacedTrack>, retried: Boolean): List<String> {
            val targets = target.toAdd.map { it to false } + target.toReplace.map { it to true }
            if (targets.isEmpty()) return emptyList()

            val placedById = placed.associateBy { it.persistentId }
            val targetIds = targets.mapTo(HashSet()) { it.first.persistentId }
            // 保存先は M1 が決める。T1 と MediaStore の既存のパスを使用中の集合として渡す
            val usedPaths = placed.filter { it.persistentId !in targetIds }.mapTo(HashSet()) { it.relativePath } + env.writer.existingPaths()
            val paths = resolveTrackPaths(
                tracks = targets.map { it.first },
                records = placed.map { SyncRecord(it.persistentId, it.sourceSize, it.sourceModified, it.relativePath) },
                usedPaths = usedPaths,
                suffixLength = SyncValues.SUFFIX_LENGTH,
                maxBytes = SyncValues.MAX_ELEMENT_BYTES,
            )

            // 手順 7 の始めに全部を waiting で作る。取り直しでは、まだ無い行だけ足す
            val known = rows.mapTo(HashSet()) { it.persistentId }
            rows = rows + targets.map { it.first }.filter { it.persistentId !in known }
                .map { TrackRow(it.persistentId, it.name ?: it.fileName, it.size) }
            publish(SyncStage.Transfer)
            val stale = mutableListOf<String>()
            for ((track, replace) in targets) {
                val step = transferOne(api, track, replace, paths.getValue(track.persistentId), placedById[track.persistentId], retried)
                inFlightBytes = 0
                when (step) {
                    TrackStep.Done -> {
                        if (replace) replaced++ else added++
                        updateRow(track.persistentId) { it.copy(state = TrackState.Done, receivedBytes = it.size) }
                        env.log.line("保存した：${track.name ?: track.fileName}")
                    }
                    is TrackStep.Failed -> {
                        errors += step.error
                        if (step.error is SyncError.ManifestStale) stale += track.persistentId
                        updateRow(track.persistentId) { it.copy(state = TrackState.Failed, errorNumber = step.error.number) }
                        env.log.failure("保存に失敗した：${track.name ?: track.fileName}", step.error.number)
                    }
                    is TrackStep.Stop -> {
                        applyStop(step)
                        return stale
                    }
                }
                doneCount++
                publish(SyncStage.Transfer, title = null)
            }
            return stale
        }

        private suspend fun transferOne(
            api: SyncApi,
            track: ManifestTrack,
            replace: Boolean,
            path: String,
            placed: PlacedTrack?,
            retried: Boolean,
        ): TrackStep {
            val name = track.name ?: track.fileName
            var failures = 0
            var fromScratch = false
            // この曲にこの実行で受けた量のうち、終わった取得の分。切断からの再開や最初からの取り直しを挟んでも足し上げる（X6-6）
            var received = 0L
            // 直前の取得で受けた量。最初の取得では null（X6-11）
            var previousBytes: Long? = null
            updateRow(track.persistentId) { it.copy(state = TrackState.Transferring, receivedBytes = 0, errorNumber = null) }
            env.log.line("転送を始めた：$name")

            while (true) {
                val plan = env.writer.plan(track, path, placed, replace, fromScratch)
                fromScratch = false
                val tally = BodyTally()

                val outcome: Attempt? = if (plan is WritePlan.Complete) {
                    // 書き終えていた（IS_PENDING の切り替えの前後で落ちた）。取得せずに読み戻しへ進む
                    Attempt.Wrote(env.writer.write(plan, null))
                } else {
                    transferStarted = true
                    updateRow(track.persistentId) { it.copy(receivedBytes = plan.resumeFrom) }
                    publish(SyncStage.Transfer, title = name)
                    val expectedSize = (plan as? WritePlan.Resume)?.pending?.expectedSize ?: track.size
                    val expectedModified = (plan as? WritePlan.Resume)?.pending?.sourceModified ?: track.modifiedAt
                    val response = api.track(track.persistentId, track.size, track.modifiedAt, plan.resumeFrom) { body ->
                        if (body.sizeHeader != expectedSize || body.modifiedAtHeader != expectedModified) {
                            Attempt.Stale
                        } else {
                            val counted = counting(track.persistentId, name, body.startAt, body.source, tally, received)
                            Attempt.Wrote(env.writer.write(plan, TrackBody(body.startAt, counted)))
                        }
                    }
                    when (response) {
                        is ApiResult.Ok -> response.value
                        is ApiResult.Fail -> when (val failure = response.failure) {
                            ApiFailure.Disconnected -> null
                            ApiFailure.RangeNotSatisfiable, ApiFailure.RangeMismatch -> {
                                fromScratch = true
                                null
                            }
                            ApiFailure.FileMissing -> return TrackStep.Failed(SyncError.FileMissing(track.persistentId, name, FileMissingReason.NotFound))
                            ApiFailure.Stale -> Attempt.Stale
                            else -> return stopFor(failure)
                        }
                    }
                }

                when (outcome) {
                    Attempt.Stale -> {
                        // 再開の曲が E8 になったときは pending の行と T5 を消す
                        if (plan is WritePlan.Resume) env.writer.discardPending(track.persistentId)
                        return TrackStep.Failed(SyncError.ManifestStale(track.persistentId, name, retried))
                    }
                    is Attempt.Wrote -> when (val result = outcome.result) {
                        is TrackWriteResult.Placed -> {
                            bytesTransferred += received + tally.bytes
                            return TrackStep.Done
                        }
                        is TrackWriteResult.Failed -> return TrackStep.Failed(SyncError.WriteFailed(track.persistentId, name, result.step))
                        is TrackWriteResult.OutOfSpace -> return TrackStep.Stop(SyncError.InsufficientStorage(result.missingBytes, StoragePhase.Writing))
                        TrackWriteResult.NeedRestart -> fromScratch = true
                        TrackWriteResult.Disconnected -> if (isStalled(plan, tally, previousBytes)) {
                            // 正常に閉じた短い本文を取り直しても、前の取得から増えない。この曲を E9 にして次へ進む（X6-11）
                            env.writer.discardPending(track.persistentId)
                            return TrackStep.Failed(SyncError.WriteFailed(track.persistentId, name, WriteStep.TempFileWrite))
                        }
                    }
                    null -> Unit
                }

                received += tally.bytes
                previousBytes = tally.bytes

                // E6（切断）と取り直し：同じ接続先に、V10 の回数まで再開する。超えたら同期全体を止める（行と T5 は残す）
                failures++
                if (failures > SyncValues.MAX_RETRIES) {
                    return TrackStep.Stop(SyncError.TransferInterrupted(track.persistentId, failures))
                }
                env.wait(SyncValues.RETRY_WAITS_MS[failures - 1])
            }
        }

        /**
         * 正常に閉じた短い本文が、前の取得から増えていないか（X6-11）。
         * 続きから受ける取得（再開）は、1 バイトも受けていないとき。
         * 毎回最初から書き直す取得（置き換え、新規）は、前の取得の受け取り量以下のとき。最初の取得（[previousBytes] が null）は増えていないとは言えない。
         */
        private fun isStalled(plan: WritePlan, tally: BodyTally, previousBytes: Long?): Boolean = when {
            !tally.endedNormally -> false
            plan is WritePlan.Resume -> tally.bytes == 0L
            else -> previousBytes != null && tally.bytes <= previousBytes
        }

        /** P1。切断は V10 の回数まで再試行する（曲の指定が無いので persistentId なし）。 */
        private suspend fun fetchManifest(api: SyncApi): Fetch {
            var failures = 0
            while (true) {
                when (val response = api.manifest(selectedIds)) {
                    is ApiResult.Ok -> return when (val read = readManifest(response.value)) {
                        is ManifestReadResult.Loaded -> Fetch.Loaded(read)
                        // 版が合わない：端末の更新の案内が要る。印を残し、E なしで止める（X6-9）
                        is ManifestReadResult.VersionMismatch -> {
                            manifestVersionMismatch = true
                            Fetch.Stop(TrackStep.Stop(null))
                        }
                        // 壊れている：印は付けず、E なしで止める
                        is ManifestReadResult.Broken -> Fetch.Stop(TrackStep.Stop(null))
                    }
                    is ApiResult.Fail -> {
                        if (response.failure != ApiFailure.Disconnected) return Fetch.Stop(stopFor(response.failure))
                        failures++
                        if (failures > SyncValues.MAX_RETRIES) return Fetch.Stop(TrackStep.Stop(SyncError.TransferInterrupted(null, failures)))
                        env.wait(SyncValues.RETRY_WAITS_MS[failures - 1])
                    }
                }
            }
        }

        private fun updateRow(id: String, change: (TrackRow) -> TrackRow) {
            rows = rows.map { if (it.persistentId == id) change(it) else it }
        }

        /**
         * 受け取った量を数える（MediaWriter は変えない）。書き込みの 1 区切りごとに行を更新する。
         * [receivedBefore] はこの曲に、これより前の取得で受けた量。
         */
        private fun counting(
            id: String,
            name: String,
            startAt: Long,
            inner: TrackSource,
            tally: BodyTally,
            receivedBefore: Long,
        ) = object : TrackSource {
            private var position = startAt

            override suspend fun read(buffer: ByteArray): Int {
                val count = inner.read(buffer)
                if (count < 0) tally.endedNormally = true
                if (count > 0) {
                    tally.bytes += count
                    position += count
                    inFlightBytes = receivedBefore + tally.bytes
                    updateRow(id) { it.copy(receivedBytes = position) }
                    publish(SyncStage.Transfer, title = name)
                }
                return count
            }
        }

        private fun stopFor(failure: ApiFailure): TrackStep.Stop = when (failure) {
            is ApiFailure.Rejected -> TrackStep.Stop(SyncError.KeyMismatch(KeyMismatchCause.SignatureMismatch), failure.keyMismatch, failure.detail)
            ApiFailure.PinMismatch -> TrackStep.Stop(SyncError.KeyMismatch(KeyMismatchCause.PinMismatch), true, ErrorDetail.PinMismatch)
            ApiFailure.XmlUnavailable -> TrackStep.Stop(SyncError.XmlUnavailable)
            ApiFailure.Disconnected -> TrackStep.Stop(SyncError.TransferInterrupted(null, 1))
            else -> TrackStep.Stop(null)
        }

        private fun applyStop(step: TrackStep.Stop) {
            if (step.error == null) stoppedWithoutE = true else errors += step.error
            if (step.error is SyncError.KeyMismatch) {
                keyMismatch = step.keyMismatch
                errorDetail = step.detail
            }
        }

        /** 到達確認（X6-3 の 2 の 2）。P3 が通った接続先を使う。選び方は画面側と共有する（[reachPc]）。 */
        private suspend fun reach(): Reach<String> {
            // 画面側が同じ試行を済ませているときは、繰り返さない
            if (input.reachFailed) return Reach.Unreachable(mismatch = false)

            return env.reachPc(pairing, opened) { it.digest(selectedIds) }
        }

        /** P3 が通った後に、`reported`＝0 の行を古い順に全部、1 回の同期で 1 度ずつ再送する。 */
        private suspend fun resendReports(api: SyncApi, runs: List<SyncRun>) {
            for (row in runs.filter { it.reported == 0 && it.endedAt != null }.sortedBy { it.startedAt }) {
                val body = reportBody(row) ?: continue
                if (api.report(body) is ApiResult.Ok) env.store.finishRun(row.copy(reported = 1))
            }
        }

        private suspend fun promote(): Boolean {
            if (env.promote()) {
                foregroundSince = env.now()
                return true
            }
            // 起動の制限で前面へ上がれない。T3 には残さず、利用者は S1 から押し直す（自動は次の周期）
            env.log.line("前面への昇格が拒否されたため、何もせず終える")
            return false
        }

        private suspend fun closeStopped(failure: ApiFailure): SyncRun {
            val step = stopFor(failure)
            // 500 は E なし。T3 の result は M1 の区分から写す
            return closeEarly(step.error, step.keyMismatch, step.detail, withoutE = step.error == null)
        }

        /**
         * 抑止ではなく、記録して終える早い終わり（手順 2 の 1b、3、4、4b）。到達確認の段で閉じる行は
         * 差分に入る前なので、件数は空。`reported`＝0 のまま次回の再送の対象にする。
         */
        private suspend fun closeEarly(
            error: SyncError?,
            keyMismatch: Boolean? = null,
            detail: ErrorDetail? = null,
            withoutE: Boolean = false,
        ): SyncRun {
            error?.let { errors += it }
            stoppedWithoutE = withoutE
            if (error is SyncError.KeyMismatch) {
                this.keyMismatch = keyMismatch
                this.errorDetail = detail
            }
            run = env.store.startRun(SyncRun(deviceId = deviceId, trigger = triggerText, startedAt = startedAt)).let {
                SyncRun(runId = it, deviceId = deviceId, trigger = triggerText, startedAt = startedAt)
            }
            return conclude(userInterrupted = false, afterCancel = false, skipPlaylists = true)
        }

        /** 手順 8〜12。T3 の更新は必須。 */
        private suspend fun conclude(userInterrupted: Boolean, afterCancel: Boolean, skipPlaylists: Boolean = false): SyncRun {
            val loaded = manifest
            val finalDiff = diff

            // 止めたとき転送中だった行は waiting に戻す（次の同期で再開する曲のため）
            rows = rows.map { if (it.state == TrackState.Transferring) it.copy(state = TrackState.Waiting, receivedBytes = 0) else it }
            inFlightBytes = 0

            // 8. m3u8。止まった後は時間があれば書く（数秒以内に戻る必要がある）
            if (loaded != null && !skipPlaylists && !skippedUnchanged) {
                if (afterCancel) {
                    withTimeoutOrNullQuiet(SyncValues.PLAYLIST_ON_STOP_WAIT_MS) { writePlaylists(loaded) }
                } else {
                    writePlaylists(loaded)
                }
            }

            // 9. 「消す」：同期全体の失敗、利用者の中断、E なしの停止では行わない
            var outcome = classify(userInterrupted)
            // 設定が OFF のときは、toRemove があっても消さない（件数は counts に残る）
            if (env.deleteMissing && loaded != null && finalDiff != null && !skippedUnchanged && !afterCancel && mayRemove(outcome)) {
                val allowed = mayExecuteRemoval(
                    trigger = triggerText,
                    gateAllowed = finalDiff.removalGate == RemovalGate.Allowed,
                    removeCount = finalDiff.toRemove.size,
                    deleteApproved = input.deleteApproved && !input.rerun,
                    approvedDeleteCount = input.approvedDeleteCount,
                )
                if (allowed) {
                    val placed = env.store.placedTracks(deviceId).associateBy { it.persistentId }
                    for (record in finalDiff.toRemove) {
                        val row = placed[record.persistentId] ?: continue
                        if (env.writer.delete(row)) removed++ else errors += SyncError.WriteFailed(record.persistentId, record.placedPath.substringAfterLast('/'), WriteStep.MediaStoreRegistration)
                    }
                    removalExecuted = finalDiff.toRemove.isNotEmpty()
                } else {
                    removalSkipped = true
                }
                outcome = classify(userInterrupted)
            }

            // 9b. 取り直しがすべて済んだ（完了）。古い場所のファイルは消さない（S5 の操作で消す。D16）
            if (relocating && !afterCancel && outcome == SyncOutcome.Completed) env.writer.finishRelocation()

            return record(outcome, loaded, finalDiff, afterCancel, userInterrupted)
        }

        private fun classify(userInterrupted: Boolean) = classifyOutcome(errors, transferStarted, userInterrupted, stoppedWithoutE)

        private suspend fun writePlaylists(loaded: Manifest) {
            publish(SyncStage.Playlist)
            val names = buildPlaylistFileName(loaded.playlists, SyncValues.SUFFIX_LENGTH, SyncValues.MAX_ELEMENT_BYTES)
            val placedPaths = env.store.placedTracks(deviceId).associate { it.persistentId to it.relativePath }
            for (playlist in loaded.playlists) {
                val body = buildM3u8(playlist, placedPaths)
                val count = playlist.trackIds.count { it in placedPaths }
                if (env.writer.writePlaylist(playlist, names.getValue(playlist.persistentId), body, count) is PlaylistWriteResult.Failed) {
                    // そのプレイリストを E9 にして続ける
                    errors += SyncError.WriteFailed(playlist.persistentId, playlist.name, WriteStep.MediaStoreRegistration)
                }
            }
            // 未解決のプレイリストの m3u8 と T2 の行は消さない（J24）
            env.writer.removeStalePlaylists(loaded.playlists.mapTo(HashSet()) { it.persistentId } + loaded.unresolvedPlaylists.map { it.persistentId })
        }

        /** 10〜12. T3 の更新、P4 の報告、終了の通知。 */
        private suspend fun record(outcome: SyncOutcome, loaded: Manifest?, finalDiff: Diff?, afterCancel: Boolean, userInterrupted: Boolean): SyncRun {
            val current = checkNotNull(run)
            val endedAt = env.now()
            val trackErrors = errors.filter { it.scope == ErrorScope.Track }
            val libraryChangedKept = finalDiff != null && finalDiff.removalGate == RemovalGate.LibraryChanged &&
                finalDiff.toRemove.isNotEmpty() && !removalExecuted
            val libraryId = when {
                loaded == null -> null
                libraryChangedKept -> baseLibraryId
                else -> loaded.libraryId
            }
            val totals = RunTotals(
                plannedAdd = plannedAdd,
                plannedReplace = plannedReplace,
                plannedBytes = plannedBytes,
                added = added,
                replaced = replaced,
                removed = removed,
                failed = trackErrors.size,
                bytesTransferred = bytesTransferred,
                freeBefore = freeBefore,
                freeAfter = if (transferStarted && !afterCancel) env.freeBytes() else freeBefore,
            )
            val marks = RunMarks(duplicatesDropped, removalSkipped, keyMismatch, errorDetail, manifestVersionMismatch)
            val result = buildSyncResult(trigger, transport, startedAt, endedAt, outcome, totals, errors, loaded, finalDiff, libraryId, marks)
            val resultText = resultTextOf(outcome, storagePreCheck)
            val finished = current.copy(
                endedAt = endedAt,
                result = resultText,
                errorKind = errorKindOf(errors, userInterrupted),
                added = totals.added,
                replaced = totals.replaced,
                removed = totals.removed,
                failed = totals.failed,
                bytesTransferred = totals.bytesTransferred,
                libraryId = libraryId,
                contentDigest = loaded?.contentDigest,
                resultJson = encodeResult(result),
            )
            env.store.addIssues(issuesOf(current.runId, loaded, finalDiff, trackErrors))
            env.store.finishRun(finished)
            recorded = true

            // 11. P4 で報告する。失敗しても結果に影響しない。報告できなかった行は次回の同期で再送する
            var saved = finished
            // P8 は P4 の前まで。送っている途中のものも片付ける
            reporter.close()
            val client = api
            if (client != null) {
                val body = reportBody(finished)
                val sent = body != null && runCatching { reportWithin(client, body, afterCancel) }.getOrDefault(false)
                if (sent) {
                    saved = finished.copy(reported = 1)
                    env.store.finishRun(saved)
                }
            }

            // 12. 終了の通知
            val shortage = (errors.firstOrNull { it is SyncError.InsufficientStorage } as? SyncError.InsufficientStorage)?.shortfallBytes
            val finalProgress = progressNow(SyncStage.Finished).copy(
                outcome = resultText,
                errorDetail = errorDetail.takeIf { errors.any { it is SyncError.KeyMismatch } },
                shortageBytes = shortage,
            )
            env.progress.value = finalProgress
            env.onFinished(saved, finalProgress, trigger)
            return saved
        }

        private fun progressNow(stage: SyncStage, title: String? = null, shortage: Long? = null) = SyncProgress(
            stage = stage,
            doneCount = doneCount,
            totalCount = plannedAdd + plannedReplace,
            doneBytes = bytesTransferred + inFlightBytes,
            totalBytes = plannedBytes,
            currentTitle = title,
            shortageBytes = shortage,
            nearLimit = foregroundSince?.let { isNearLimit(it, env.now()) } ?: false,
            tracks = rows,
        )

        private fun publish(stage: SyncStage, title: String? = null, shortage: Long? = null) {
            val progress = progressNow(stage, title, shortage)
            env.progress.value = progress
            reporter.offer(api, progress)
        }

        private fun transportOf(kind: String?): Transport? = when (kind) {
            LAN -> Transport.Lan
            TAILSCALE -> Transport.Tailscale
            USB -> Transport.UsbTethering
            else -> null
        }

        private suspend fun reportWithin(client: SyncApi, body: String, afterCancel: Boolean): Boolean {
            if (!afterCancel) return client.report(body) is ApiResult.Ok
            return withTimeoutOrNull(SyncValues.PLAYLIST_ON_STOP_WAIT_MS) { client.report(body) is ApiResult.Ok } ?: false
        }

        private suspend fun withTimeoutOrNullQuiet(ms: Long, block: suspend () -> Unit) {
            try {
                withTimeoutOrNull(ms) { block() }
            } catch (_: TimeoutCancellationException) {
                // 時間切れは書けなかっただけ。次の同期で書き直す
            }
        }
    }

}

private fun SyncRun.isAfterRun(other: SyncRun) =
    startedAt > other.startedAt || (startedAt == other.startedAt && runId > other.runId)
