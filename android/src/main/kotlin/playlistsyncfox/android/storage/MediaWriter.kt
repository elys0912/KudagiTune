package playlistsyncfox.android.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import playlistsyncfox.android.db.PendingTransfer
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.path.mimeTypeOf
import playlistsyncfox.shared.result.WriteStep
import kotlin.coroutines.CoroutineContext

/** 取得元の入力。切断は [SourceDisconnectedException] で知らせる。 */
interface TrackSource {
    /** 0 バイト以上を [buffer] に読む。終わりで -1。 */
    suspend fun read(buffer: ByteArray): Int
}

class SourceDisconnectedException(cause: Throwable? = null) : Exception("取得元が切れた", cause)

/** 取得した本文。[startAt] はこの本文がファイルのどの位置から始まるか（全体なら 0、206 なら要求した位置）。 */
class TrackBody(val startAt: Long, val source: TrackSource)

/** 書き込みの前に決める、新規・再開・完了済み・置き換えのどれか（X6-2）。 */
sealed interface WritePlan {
    val track: ManifestTrack

    /** 取得を要求する位置（`Range` の開始）。0 なら `Range` を付けない。 */
    val resumeFrom: Long get() = 0

    class Fresh(override val track: ManifestTrack, val relativePath: String) : WritePlan

    class Resume(override val track: ManifestTrack, val pending: PendingTransfer, val position: Long) : WritePlan {
        override val resumeFrom: Long get() = position
    }

    /** pending のファイルが `expected_size` に達している。取得せずに読み戻しへ進む。 */
    class Complete(override val track: ManifestTrack, val pending: PendingTransfer) : WritePlan

    class Replace(override val track: ManifestTrack, val placed: PlacedTrack) : WritePlan
}

sealed interface TrackWriteResult {
    data class Placed(val row: PlacedTrack) : TrackWriteResult

    /** E9。新規と再開は pending の行と T5 を消してある。 */
    data class Failed(val step: WriteStep) : TrackWriteResult

    /** E3（書き込み中）。pending の行と T5 を消してある。 */
    data class OutOfSpace(val missingBytes: Long) : TrackWriteResult

    /** E6。pending の行と T5 を残してある。 */
    data object Disconnected : TrackWriteResult

    /** 追記で開けない、位置が合わない。pending の行と T5 を消して最初から取り直す。 */
    data object NeedRestart : TrackWriteResult
}

sealed interface PlaylistWriteResult {
    data class Written(val row: WrittenPlaylist) : PlaylistWriteResult

    /** E9。そのプレイリストだけ失敗として続ける。 */
    data object Failed : PlaylistWriteResult
}

/**
 * MediaStore の書き込み（X6-2）。手順と T1・T2・T5 の更新を持ち、OS の呼び出しは [files] に任せる。
 * 試験では [files] を差し替える（設計は MediaWriter 自体を interface とするが、状態の確認に必要な境界は OS 側のため）。
 */
class MediaWriter(
    private val store: SyncStore,
    private val files: MediaStoreFiles,
    private val deviceId: String,
    private val now: () -> Long,
    private val io: CoroutineContext = Dispatchers.IO,
) {
    private val root = SyncValues.MEDIA_ROOT

    /** 同期の始めの整理 1：T5 の期限切れ（V25）を回収する。pending の行を消し、T5 を消す。 */
    suspend fun collectExpired() = withContext(io) {
        for (row in store.expiredPending(now() - SyncValues.PENDING_TTL_MS)) {
            discardPending(row)
        }
    }

    /**
     * 保存先の切り替え（D16、X6-3 の手順 3b）。T1、T2 のあるボリュームが [volume] と違えば、行を T9 へ移して T5 を捨てる
     * （差分が全曲の「足す」になる）。戻り値は、取り直しが完了で終わっていないか（T7 の `relocating`）。
     */
    suspend fun relocateTo(volume: String): Boolean = withContext(io) {
        if (placedVolume() != volume) {
            // 取得の途中は切り替え前のボリュームに書きかけている。全部捨てる
            for (row in store.expiredPending(Long.MAX_VALUE)) discardPending(row)
            store.relocate(deviceId, volume)
        }
        isRelocating()
    }

    /** 保存先が変わった、または前の切り替えの取り直しが完了で終わっていない。真のとき、自動の「内容が同じ」の省略をしない。 */
    suspend fun relocationNeeded(volume: String): Boolean = withContext(io) {
        placedVolume() != volume || isRelocating()
    }

    private suspend fun isRelocating() = store.setting(SyncValues.RELOCATING_KEY) == SyncValues.SETTING_ON

    /** 取り直しが完了で終わった。 */
    suspend fun finishRelocation() = withContext(io) { store.putSetting(SyncValues.RELOCATING_KEY, "false") }

    private suspend fun placedVolume() = store.setting(SyncValues.PLACED_VOLUME_KEY) ?: SyncValues.VOLUME_PRIMARY

    /**
     * 切り替え前の場所に残る、この端末のアプリが書いたファイル（T9）を消す。S5 の操作で、確認の後に呼ぶ。
     * 持ち主が違うなどで消せない行（`SecurityException`）は T9 に残す。消した件数を返す。
     */
    suspend fun deleteRelocated(): Int = withContext(io) {
        var deleted = 0
        for (uri in store.relocatedUris()) {
            try {
                files.delete(uri)
            } catch (_: SecurityException) {
                continue
            }
            store.removeRelocated(uri)
            deleted++
        }
        deleted
    }

    /** 整理 3：T2 に無い、自分の pending の m3u8（新規の insert の途中で落ちた残り）を消す。 */
    suspend fun deleteOrphanPendingPlaylists() = withContext(io) {
        val known = store.playlists(deviceId).mapTo(HashSet()) { it.mediaUri }
        for (uri in files.ownedPendingPlaylistUris().filter { it !in known }) {
            runCatching { files.delete(uri) }
        }
    }

    /** 差分の `presentIds`：T1 の曲のうち MediaStore に行が今ある曲（pending も含む）。 */
    suspend fun presentIds(placed: List<PlacedTrack>): Set<String> = withContext(io) {
        placed.filter { files.exists(it.mediaUri) }.mapTo(HashSet()) { it.persistentId }
    }

    /** 衝突の確認に渡す、MediaStore に既にある自分のパス。 */
    suspend fun existingPaths(): Set<String> = withContext(io) { files.ownedTrackPaths() }

    /**
     * 1 曲の書き込みの前に、新規・再開・完了済みを決める（X6-2 の「再開と回収」）。
     * [placed] があり [replace] が真なら置き換え。[fromScratch] が真なら T5 があっても最初から。
     */
    suspend fun plan(
        track: ManifestTrack,
        relativePath: String,
        placed: PlacedTrack?,
        replace: Boolean,
        fromScratch: Boolean = false,
    ): WritePlan = withContext(io) {
        // 置き換えの途中は続きから再開せず、常に最初から書き直す。T5 には置き換えの行を作らない
        if (replace && placed != null) return@withContext WritePlan.Replace(track, placed)

        val pending = store.pending(track.persistentId) ?: return@withContext WritePlan.Fresh(track, relativePath)
        val usable = !fromScratch &&
            pending.sourceModified == track.modifiedAt &&
            pending.expectedSize == track.size &&
            files.exists(pending.mediaUri)
        if (!usable) {
            discardPending(pending)
            return@withContext WritePlan.Fresh(track, relativePath)
        }

        // 再開の位置は pending のファイルの大きさを正とする。T5 とずれていれば大きさに合わせる
        val size = files.size(pending.mediaUri) ?: 0L
        val synced = if (pending.receivedBytes == size) pending else pending.copy(receivedBytes = size).also { store.savePending(it) }
        if (size == track.size) WritePlan.Complete(track, synced) else WritePlan.Resume(track, synced, size)
    }

    /** 再開の曲が E8 になったときなど、pending の行と T5 を消す。 */
    suspend fun discardPending(persistentId: String) = withContext(io) {
        store.pending(persistentId)?.let { discardPending(it) }
    }

    /** 曲を書く（新規、再開、完了済み、置き換え）。[body] は完了済みでは使わない。 */
    suspend fun write(plan: WritePlan, body: TrackBody?): TrackWriteResult = withContext(io) {
        when (plan) {
            is WritePlan.Complete -> finish(plan.track, plan.pending.mediaUri, plan.track.size, discardOnFailure = true)
            is WritePlan.Replace -> replace(plan, checkNotNull(body))
            is WritePlan.Fresh -> fresh(plan, checkNotNull(body))
            is WritePlan.Resume -> resume(plan, checkNotNull(body))
        }
    }

    /** 曲を消す。成功と「既に無い」は T1 の行を消す。権限の例外は E9 でその曲だけ、T1 は残す。 */
    suspend fun delete(placed: PlacedTrack): Boolean = withContext(io) {
        try {
            files.delete(placed.mediaUri)
        } catch (_: SecurityException) {
            return@withContext false
        }
        store.removePlaced(placed.deviceId, placed.persistentId)
        true
    }

    /**
     * m3u8 を書く（X6-2 の「m3u8」）。[fileName] は `buildPlaylistFileName` の結果、[body] は `buildM3u8` の結果。
     */
    suspend fun writePlaylist(
        playlist: ManifestPlaylist,
        fileName: String,
        body: String,
        trackCount: Int,
    ): PlaylistWriteResult = withContext(io) {
        val bytes = body.encodeToByteArray()
        val existing = store.playlists(deviceId).firstOrNull { it.playlistId == playlist.persistentId }

        var uri: String? = null
        if (existing != null) {
            val existingName = existing.relativePath.substringAfterLast('/')
            if (isSameName(existingName, fileName)) {
                // 入れ直し後の別名（名前 (1).m3u8）は改名とみなさず、既存の URI を書き直す
                uri = existing.mediaUri.takeIf { files.exists(it) }
            } else {
                // プレイリストの改名。古い m3u8 の削除に失敗しても新しい書き込みは続ける
                runCatching { files.delete(existing.mediaUri) }
            }
        }

        val target = uri ?: files.insertPendingPlaylist(fileName, mimeTypeOf("m3u8")!!, root)
            ?: return@withContext PlaylistWriteResult.Failed
        val sink = files.open(target, OpenMode.Truncate)
        if (sink == null) {
            if (uri == null) runCatching { files.delete(target) }
            return@withContext PlaylistWriteResult.Failed
        }
        try {
            sink.use {
                it.write(bytes, bytes.size)
                it.flush()
            }
        } catch (_: java.io.IOException) {
            if (uri == null) runCatching { files.delete(target) }
            return@withContext PlaylistWriteResult.Failed
        }

        // 索引の更新を促す（IS_PENDING の切り替え。これで足りるかは実機で確かめる。C-2）
        val stored = files.publish(target)
        if (stored == null) {
            if (uri == null) runCatching { files.delete(target) }
            return@withContext PlaylistWriteResult.Failed
        }

        val row = WrittenPlaylist(
            deviceId = deviceId,
            playlistId = playlist.persistentId,
            name = playlist.name,
            relativePath = stored.pathUnderRoot(root),
            mediaUri = stored.uri,
            trackCount = trackCount,
            writtenAt = now(),
        )
        store.savePlaylist(row)
        PlaylistWriteResult.Written(row)
    }

    /** `playlists` にも `unresolved_playlists` にも無い T2 の行の m3u8 を消し、行を消す。未解決のものは残す。 */
    suspend fun removeStalePlaylists(keepIds: Set<String>) = withContext(io) {
        for (row in store.playlists(deviceId).filter { it.playlistId !in keepIds }) {
            try {
                files.delete(row.mediaUri)
            } catch (_: SecurityException) {
                continue
            }
            store.deletePlaylist(deviceId, row.playlistId)
        }
    }

    /**
     * 以前の曲を一覧する：T1 に無い曲と、T2 に無い m3u8（入れ直し後の古い m3u8 を含む。J32）。
     * 持ち主では絞らない（X6-2）。入れ直しで持ち主が外れた行も入る。
     */
    suspend fun leftovers(): List<StoredFile> = withContext(io) {
        // 列挙の URI は `Files` の形で、T1・T2 の URI（`Audio.Media`）と書き方が違う。相対パスで突き合わせる
        val known = store.placedTracks(deviceId).mapTo(HashSet()) { it.relativePath } +
            store.playlists(deviceId).map { it.relativePath }
        files.listUnderRoot().filter { it.pathUnderRoot(root) !in known }
    }

    private suspend fun fresh(plan: WritePlan.Fresh, body: TrackBody): TrackWriteResult {
        val track = plan.track
        val mime = mimeTypeOf(track.extension) ?: return TrackWriteResult.Failed(WriteStep.MediaStoreRegistration)
        val folder = plan.relativePath.substringBeforeLast('/', "")
        val displayName = plan.relativePath.substringAfterLast('/')
        val uri = files.insertPendingTrack(displayName, mime, if (folder.isEmpty()) root else "$root$folder/")
            ?: return TrackWriteResult.Failed(WriteStep.MediaStoreRegistration)

        val pending = PendingTransfer(track.persistentId, uri, track.size, 0, track.modifiedAt, now())
        store.savePending(pending)
        val sink = files.open(uri, OpenMode.Truncate)
        if (sink == null) {
            discardPending(pending)
            return TrackWriteResult.Failed(WriteStep.TempFileWrite)
        }
        return copy(track, uri, sink, body, startAt = 0, pending = pending)
    }

    private suspend fun resume(plan: WritePlan.Resume, body: TrackBody): TrackWriteResult {
        val pending = plan.pending
        val track = plan.track
        // 全体が来た（Range を付けたのに 200）ときは "wt" で最初から書き直す
        val (mode, startAt) = when (body.startAt) {
            0L -> OpenMode.Truncate to 0L
            plan.position -> OpenMode.Append to plan.position
            else -> return discardAndRestart(pending)
        }
        // 追記で開けなければ取り直し
        val sink = files.open(pending.mediaUri, mode) ?: return discardAndRestart(pending)
        return copy(track, pending.mediaUri, sink, body, startAt, pending.copy(receivedBytes = startAt))
    }

    private suspend fun replace(plan: WritePlan.Replace, body: TrackBody): TrackWriteResult {
        val placed = plan.placed
        store.savePlaced(placed.copy(state = PlacedTrack.STATE_REPLACING))
        val sink = files.open(placed.mediaUri, OpenMode.Truncate) ?: return TrackWriteResult.Failed(WriteStep.TempFileWrite)
        return copy(plan.track, placed.mediaUri, sink, body, startAt = 0, pending = null)
    }

    /** 入力を V27 の区切りで読んで書く。区切りごとにフラッシュし、T5 を更新して、取り消しを確かめる。 */
    private suspend fun copy(
        track: ManifestTrack,
        uri: String,
        sink: MediaSink,
        body: TrackBody,
        startAt: Long,
        pending: PendingTransfer?,
    ): TrackWriteResult {
        // 新規と再開は T5 と pending の行を持つ。置き換えは持たない
        val discardOnFailure = pending != null
        var written = startAt
        val buffer = ByteArray(SyncValues.WRITE_CHUNK_BYTES)
        try {
            sink.use {
                while (true) {
                    val count = body.source.read(buffer)
                    if (count < 0) break
                    sink.write(buffer, count)
                    sink.flush()
                    written += count
                    if (pending != null) {
                        withContext(NonCancellable) { store.savePending(pending.copy(receivedBytes = written)) }
                    }
                    // 受けたら、この区切りの書き込みと T5 の更新を終えてから止める
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: SourceDisconnectedException) {
            return TrackWriteResult.Disconnected
        } catch (_: OutOfSpaceException) {
            if (discardOnFailure) withContext(NonCancellable) { discardPending(track.persistentId, uri) }
            return TrackWriteResult.OutOfSpace(maxOf(0L, track.size - written))
        } catch (_: java.io.IOException) {
            return failed(track, uri, discardOnFailure, WriteStep.TempFileWrite)
        }

        // 取得元が正常に閉じたのに足りないときは切断として扱い、再開に回す。多いときは不一致で E9
        if (written < track.size) return TrackWriteResult.Disconnected
        if (written > track.size) return failed(track, uri, discardOnFailure, WriteStep.TempFileWrite)
        return finish(track, uri, written, discardOnFailure)
    }

    /** `IS_PENDING`＝0 にして読み戻し、T1 を書いて T5 を消す（手順 8、9）。 */
    private suspend fun finish(track: ManifestTrack, uri: String, written: Long, discardOnFailure: Boolean): TrackWriteResult {
        val stored = files.publish(uri) ?: return failed(track, uri, discardOnFailure, WriteStep.MediaStoreRegistration)
        // RELATIVE_PATH はヒントにすぎない。場所が変わると m3u8 の基準が崩れるため E9
        if (!stored.relativePath.startsWith(root) || stored.size != written) {
            return failed(track, uri, discardOnFailure, WriteStep.MediaStoreRegistration)
        }
        val row = PlacedTrack(
            deviceId = deviceId,
            persistentId = track.persistentId,
            relativePath = stored.pathUnderRoot(root),
            mediaUri = stored.uri,
            sourceSize = track.size,
            sourceModified = track.modifiedAt,
            localSize = stored.size,
            state = PlacedTrack.STATE_PLACED,
            placedAt = now(),
        )
        store.recordCompleted(row)
        return TrackWriteResult.Placed(row)
    }

    /** 新規と再開は行を削除して T5 を消す。置き換えは元のファイルなので消さず、T1 は `replacing` のまま残す。 */
    private suspend fun failed(track: ManifestTrack, uri: String, discardOnFailure: Boolean, step: WriteStep): TrackWriteResult {
        if (discardOnFailure) discardPending(track.persistentId, uri)
        return TrackWriteResult.Failed(step)
    }

    private suspend fun discardAndRestart(pending: PendingTransfer): TrackWriteResult {
        discardPending(pending)
        return TrackWriteResult.NeedRestart
    }

    private suspend fun discardPending(row: PendingTransfer) = discardPending(row.persistentId, row.mediaUri)

    private suspend fun discardPending(persistentId: String, uri: String) {
        runCatching { files.delete(uri) }
        store.deletePending(persistentId)
    }

    /** 期待した名前そのもの、または期待した名前に「 (n)」を足しただけ。 */
    private fun isSameName(existing: String, expected: String): Boolean {
        if (existing == expected) return true
        val stem = expected.removeSuffix(M3U8_EXTENSION)
        return Regex("${Regex.escape(stem)} \\(\\d+\\)${Regex.escape(M3U8_EXTENSION)}").matches(existing)
    }

    private companion object {
        const val M3U8_EXTENSION = ".m3u8"
    }
}
