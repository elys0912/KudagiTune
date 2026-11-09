package playlistsyncfox.android

import kotlinx.coroutines.Dispatchers
import playlistsyncfox.android.db.PendingTransfer
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.RunIssue
import playlistsyncfox.android.db.SelectedPlaylist
import playlistsyncfox.android.db.SyncRun
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.net.ApiFailure
import playlistsyncfox.android.net.ApiProvider
import playlistsyncfox.android.net.ApiResult
import playlistsyncfox.android.net.Endpoint
import playlistsyncfox.android.net.SyncApi
import playlistsyncfox.android.net.TrackResponse
import playlistsyncfox.android.storage.MediaSink
import playlistsyncfox.android.storage.MediaStoreFiles
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.OpenMode
import playlistsyncfox.android.storage.OutOfSpaceException
import playlistsyncfox.android.storage.SourceDisconnectedException
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.storage.TrackSource
import playlistsyncfox.android.storage.pathUnderRoot
import playlistsyncfox.android.sync.LanResolver
import playlistsyncfox.android.sync.LanService
import playlistsyncfox.android.sync.RunInput
import playlistsyncfox.android.sync.StopCause
import playlistsyncfox.android.sync.SyncEnvironment
import playlistsyncfox.android.sync.SyncLogger
import playlistsyncfox.android.sync.SyncProgress
import playlistsyncfox.android.sync.SyncRunner
import playlistsyncfox.android.sync.SyncValues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import playlistsyncfox.shared.manifest.Manifest
import playlistsyncfox.shared.manifest.ManifestPlaylist
import playlistsyncfox.shared.manifest.ManifestTrack
import playlistsyncfox.shared.manifest.writeManifest
import playlistsyncfox.shared.result.SyncTrigger
import java.io.ByteArrayOutputStream

// 試料はすべて合成。IP は 192.0.2.0/24、名前は example.invalid。

const val DEVICE = "00000000-0000-4000-8000-000000000001"
const val PC_ID = "0123456789abcdef0123456789abcdef"
const val ROOT = SyncValues.MEDIA_ROOT

/** 試験の既定の選択（T8）。 */
const val SELECTED_ID = "00000000000000AA"

fun id(n: Int) = "%016X".format(n)

fun track(n: Int, size: Long = 100, modifiedAt: Long = 1, name: String = "Song $n", extension: String = "mp3") = ManifestTrack(
    persistentId = id(n),
    size = size,
    modifiedAt = modifiedAt,
    fileName = "$name.$extension",
    extension = extension,
    name = name,
    artist = "Artist",
    album = "Album",
    isProtected = false,
)

fun manifestOf(
    tracks: List<ManifestTrack>,
    playlists: List<ManifestPlaylist> = emptyList(),
    libraryId: String = "LIB1",
    digest: String = "digest-1",
) = Manifest(
    formatVersion = 1,
    generatedAt = "2026-11-07T00:00:00.000Z",
    libraryId = libraryId,
    xmlModifiedAt = "2026-11-07T00:00:00.000Z",
    contentDigest = digest,
    tracks = tracks,
    playlists = playlists,
    unresolvedPlaylists = emptyList(),
    excluded = emptyList(),
)

fun manifestJson(manifest: Manifest) = writeManifest(manifest)

fun pairing(
    lastEndpoint: String? = null,
    lan: String? = "psfox-01234567",
    tailscale: String? = "pc.example.invalid:47800",
    usb: String? = null,
    pairedAt: Long = 0,
) = Pairing(
    deviceId = DEVICE,
    pcId = PC_ID,
    pcName = "pc.example.invalid",
    endpointLan = lan,
    endpointTailscale = tailscale,
    endpointUsb = usb,
    lastEndpoint = lastEndpoint,
    spkiHash = "00".repeat(32),
    keyAlias = "alias-$DEVICE",
    pairedAt = pairedAt,
)

class InMemoryStore : SyncStore {
    val placed = LinkedHashMap<String, PlacedTrack>()
    val pendingRows = LinkedHashMap<String, PendingTransfer>()
    val playlistRows = LinkedHashMap<String, WrittenPlaylist>()
    val runRows = mutableListOf<SyncRun>()
    val issueRows = mutableListOf<RunIssue>()
    var pairingRow: Pairing? = null
    val settings = LinkedHashMap<String, String>()
    var selected = listOf<SelectedPlaylist>()
    val relocatedRows = mutableListOf<String>()
    private var nextRunId = 1L

    override suspend fun placedTracks(deviceId: String) = placed.values.filter { it.deviceId == deviceId }

    override suspend fun savePlaced(row: PlacedTrack) {
        placed[row.persistentId] = row
    }

    override suspend fun recordCompleted(row: PlacedTrack) {
        placed[row.persistentId] = row
        pendingRows.remove(row.persistentId)
    }

    override suspend fun removePlaced(deviceId: String, persistentId: String) {
        placed.remove(persistentId)
    }

    override suspend fun pending(persistentId: String) = pendingRows[persistentId]

    override suspend fun savePending(row: PendingTransfer) {
        pendingRows[row.persistentId] = row
    }

    override suspend fun deletePending(persistentId: String) {
        pendingRows.remove(persistentId)
    }

    override suspend fun expiredPending(startedBefore: Long) = pendingRows.values.filter { it.startedAt < startedBefore }

    override suspend fun playlists(deviceId: String) = playlistRows.values.filter { it.deviceId == deviceId }

    override suspend fun savePlaylist(row: WrittenPlaylist) {
        playlistRows[row.playlistId] = row
    }

    override suspend fun deletePlaylist(deviceId: String, playlistId: String) {
        playlistRows.remove(playlistId)
    }

    /** 新しい順。 */
    override suspend fun runs() = runRows.sortedWith(compareByDescending<SyncRun> { it.startedAt }.thenByDescending { it.runId })

    override suspend fun startRun(row: SyncRun): Long {
        val saved = row.copy(runId = nextRunId++)
        runRows += saved
        return saved.runId
    }

    override suspend fun finishRun(row: SyncRun) {
        runRows[runRows.indexOfFirst { it.runId == row.runId }] = row
    }

    override suspend fun addIssues(rows: List<RunIssue>) {
        issueRows += rows
    }

    override suspend fun issues(runId: Long) = issueRows.filter { it.runId == runId }

    override suspend fun pairing() = pairingRow

    override suspend fun updatePairing(row: Pairing) {
        pairingRow = row
    }

    override suspend fun savePairing(row: Pairing) {
        pairingRow = row
    }

    override suspend fun deletePairing(deviceId: String) {
        pairingRow = null
    }

    // 画面の試験は、読み込んだ時点の中身を 1 回流す
    override fun observePairing() = kotlinx.coroutines.flow.flow { emit(pairingRow) }

    override fun observeRuns() = kotlinx.coroutines.flow.flow { emit(runs()) }

    override fun observePlaylists() = kotlinx.coroutines.flow.flow { emit(playlistRows.values.toList()) }

    override suspend fun selectedPlaylists() = selected

    override fun observeSelectedPlaylists() = kotlinx.coroutines.flow.flow { emit(selected) }

    override suspend fun replaceSelection(rows: List<SelectedPlaylist>) {
        selected = rows
    }

    override suspend fun relocate(deviceId: String, volume: String) {
        relocatedRows += placed.values.map { it.mediaUri } + playlistRows.values.map { it.mediaUri }
        placed.clear()
        playlistRows.clear()
        pendingRows.clear()
        settings[SyncValues.PLACED_VOLUME_KEY] = volume
        settings[SyncValues.RELOCATING_KEY] = "true"
    }

    override suspend fun relocatedUris() = relocatedRows.toList()

    override suspend fun removeRelocated(mediaUri: String) {
        relocatedRows.remove(mediaUri)
    }

    override suspend fun setting(key: String) = settings[key]

    override suspend fun putSetting(key: String, value: String) {
        settings[key] = value
    }

    fun addRun(row: SyncRun): SyncRun = row.copy(runId = nextRunId++).also { runRows += it }
}

/** MediaStore の偽物。同名の insert は「名前 (1).拡張子」の別名になる（確かめた事実の 3-5）。 */
class FakeMediaStore : MediaStoreFiles {
    class Entry(
        val uri: String,
        var displayName: String,
        var relativePath: String,
        var pending: Boolean,
        val playlist: Boolean,
        var data: ByteArrayOutputStream = ByteArrayOutputStream(),
    )

    val entries = LinkedHashMap<String, Entry>()
    private var nextId = 1

    var failInsert = false
    var failAppend = false
    var failTruncate = false
    var readBackSizeDelta = 0L
    var readBackRelativePath: String? = null
    var diskFullAtTotalBytes: Long? = null
    val deniedDeletes = mutableSetOf<String>()
    val deleted = mutableListOf<String>()
    var onWrite: (Entry) -> Unit = {}

    override fun insertPendingTrack(displayName: String, mimeType: String, relativePath: String) =
        insert(displayName, relativePath, playlist = false)

    override fun insertPendingPlaylist(displayName: String, mimeType: String, relativePath: String) =
        insert(displayName, relativePath, playlist = true)

    private fun insert(displayName: String, relativePath: String, playlist: Boolean): String? {
        if (failInsert) return null
        var name = displayName
        if (entries.values.any { it.relativePath == relativePath && it.displayName == name }) {
            val stem = displayName.substringBeforeLast('.')
            val ext = displayName.substringAfterLast('.', "")
            name = "$stem (1)" + if (ext.isEmpty()) "" else ".$ext"
        }
        val uri = "content://media/test/${nextId++}"
        entries[uri] = Entry(uri, name, relativePath, pending = true, playlist = playlist)
        return uri
    }

    fun addExisting(relativePath: String, displayName: String, bytes: Int = 10, playlist: Boolean = false): String {
        val uri = "content://media/test/${nextId++}"
        entries[uri] = Entry(uri, displayName, relativePath, pending = false, playlist = playlist).also { it.data.write(ByteArray(bytes)) }
        return uri
    }

    override fun exists(uri: String) = uri in entries

    override fun size(uri: String) = entries[uri]?.data?.size()?.toLong()

    override fun open(uri: String, mode: OpenMode): MediaSink? {
        val entry = entries[uri] ?: return null
        if (mode == OpenMode.Append && failAppend) return null
        if (mode == OpenMode.Truncate && failTruncate) return null
        if (mode == OpenMode.Truncate) entry.data = ByteArrayOutputStream()
        return object : MediaSink {
            override fun write(buffer: ByteArray, length: Int) {
                val limit = diskFullAtTotalBytes
                if (limit != null && totalBytes() + length > limit) throw OutOfSpaceException()
                entry.data.write(buffer, 0, length)
                onWrite(entry)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
    }

    private fun totalBytes() = entries.values.sumOf { it.data.size().toLong() }

    override fun publish(uri: String): StoredFile? {
        val entry = entries[uri] ?: return null
        entry.pending = false
        return StoredFile(uri, entry.displayName, readBackRelativePath ?: entry.relativePath, entry.data.size() + readBackSizeDelta, 0)
    }

    override fun delete(uri: String) {
        if (uri in deniedDeletes) throw SecurityException("権限がない")
        deleted += uri
        entries.remove(uri)
    }

    override fun ownedTrackPaths() = entries.values.filter { !it.playlist }.mapTo(HashSet()) { stored(it).pathUnderRoot(ROOT) }

    override fun ownedPendingPlaylistUris() = entries.values.filter { it.playlist && it.pending }.map { it.uri }

    override fun listUnderRoot() = entries.values.filter { !it.pending }.map(::stored)

    private fun stored(entry: Entry) = StoredFile(entry.uri, entry.displayName, entry.relativePath, entry.data.size().toLong(), 0)
}

class ByteSource(private val bytes: ByteArray, private val disconnectAfter: Int? = null) : TrackSource {
    private var position = 0

    override suspend fun read(buffer: ByteArray): Int {
        val limit = disconnectAfter ?: bytes.size
        if (position >= limit) {
            if (disconnectAfter != null && disconnectAfter < bytes.size) throw SourceDisconnectedException()
            return -1
        }
        val count = minOf(buffer.size, limit - position, CHUNK)
        bytes.copyInto(buffer, 0, position, position + count)
        position += count
        return count
    }

    private companion object {
        const val CHUNK = 40
    }
}

/** P2 の台本。 */
sealed interface TrackScript {
    class Fail(val failure: ApiFailure) : TrackScript

    /** [startAt] 以降の本文を返す。ヘッダの値は既定で正しい値。 */
    class Body(
        val bytes: ByteArray,
        val startAt: Long = 0,
        val sizeHeader: Long? = null,
        val modifiedHeader: Long? = null,
        val disconnectAfter: Int? = null,
    ) : TrackScript
}

class TrackRequest(val persistentId: String, val size: Long, val modifiedAt: Long, val rangeFrom: Long)

class FakeApi(val name: String) : SyncApi {
    var digestResult: ApiResult<String> = ApiResult.Ok("digest-1")
    val manifestResults = ArrayDeque<ApiResult<String>>()
    var reportResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var progressResult: ApiResult<Unit> = ApiResult.Ok(Unit)
    var playlistsResult: ApiResult<String> = ApiResult.Ok("""{"playlists":[]}""")
    var trackScript: (TrackRequest) -> TrackScript = { TrackScript.Fail(ApiFailure.FileMissing) }

    var digestCalls = 0
    var manifestCalls = 0

    /** P3・P1 の本文として受け取った ID の配列（呼ぶたびに追加）。 */
    val digestIds = mutableListOf<List<String>>()
    val manifestIds = mutableListOf<List<String>>()
    val trackRequests = mutableListOf<TrackRequest>()
    val reports = mutableListOf<String>()

    /** P8 の本文（送った順）。 */
    val progressBodies = mutableListOf<String>()

    override suspend fun digest(playlistIds: List<String>): ApiResult<String> {
        digestCalls++
        digestIds += playlistIds
        return digestResult
    }

    override suspend fun playlists(): ApiResult<String> = playlistsResult

    override suspend fun manifest(playlistIds: List<String>): ApiResult<String> {
        manifestCalls++
        manifestIds += playlistIds
        return if (manifestResults.size > 1) manifestResults.removeFirst() else manifestResults.first()
    }

    override suspend fun <T> track(
        persistentId: String,
        size: Long,
        modifiedAt: Long,
        rangeFrom: Long,
        block: suspend (TrackResponse) -> T,
    ): ApiResult<T> {
        val request = TrackRequest(persistentId, size, modifiedAt, rangeFrom)
        trackRequests += request
        return when (val script = trackScript(request)) {
            is TrackScript.Fail -> ApiResult.Fail(script.failure)
            is TrackScript.Body -> {
                val body = script.bytes.copyOfRange(script.startAt.toInt(), script.bytes.size)
                ApiResult.Ok(
                    block(
                        TrackResponse(
                            startAt = script.startAt,
                            sizeHeader = script.sizeHeader ?: size,
                            modifiedAtHeader = script.modifiedHeader ?: modifiedAt,
                            source = ByteSource(body, script.disconnectAfter),
                        ),
                    ),
                )
            }
        }
    }

    override suspend fun report(body: String): ApiResult<Unit> {
        reports += body
        return reportResult
    }

    override suspend fun progress(body: String): ApiResult<Unit> {
        progressBodies += body
        return progressResult
    }

    override fun close() = Unit
}

class FakeProvider : ApiProvider {
    val byHost = mutableMapOf<String, FakeApi>()
    val opened = mutableListOf<Endpoint>()

    override fun open(endpoint: Endpoint): SyncApi {
        opened += endpoint
        return byHost.getValue(endpoint.host)
    }
}

class Harness(val trigger: SyncTrigger = SyncTrigger.Manual) {
    val store = InMemoryStore().also {
        it.pairingRow = pairing()
        it.selected = listOf(SelectedPlaylist(SELECTED_ID, "選んだ一覧", 0))
    }
    val media = FakeMediaStore()
    var clock = 1_000_000_000L
    val writer = MediaWriter(store, media, DEVICE, now = { clock }, io = Dispatchers.Unconfined)

    val provider = FakeProvider()
    val lanApi = FakeApi("lan").also { provider.byHost[LAN_HOST] = it }
    val tailApi = FakeApi("tailscale").also { provider.byHost[TAIL_HOST] = it }
    val usbApi = FakeApi("usb").also { provider.byHost[USB_HOST] = it }
    var lanServices = listOf(LanService("psfox-01234567", LAN_HOST, 47800, "01234567"))
    var lanDelayMs = 0L
    var onDiscover: () -> Unit = {}
    val gate = Mutex()

    val logs = mutableListOf<String>()
    val finished = mutableListOf<Triple<SyncRun, SyncProgress, SyncTrigger>>()
    val waits = mutableListOf<Long>()
    val stages = mutableListOf<SyncProgress>()
    val events = mutableListOf<String>()
    var promoteResult = true
    var aliasExists = true
    var deleteMissing = true
    var volume = SyncValues.VOLUME_PRIMARY
    var volumeAvailable = true
    var freeBytes: Long? = Long.MAX_VALUE / 4
    var manualActive = false
    var stopCause = StopCause.UserCancel
    var failOnFinished = false
    var isPlayable: (ManifestTrack) -> Boolean = { true }
    val progress = MutableStateFlow<SyncProgress?>(null)

    init {
        lanApi.manifestResults += ApiResult.Ok(manifestJson(manifestOf(emptyList())))
    }

    fun setManifest(manifest: Manifest, api: FakeApi = lanApi) {
        api.manifestResults.clear()
        api.manifestResults += ApiResult.Ok(manifestJson(manifest))
        api.digestResult = ApiResult.Ok(manifest.contentDigest)
    }

    /** 全曲に、その内容（ID から決まるバイト列）を返す台本。 */
    fun serveTracks(tracks: List<ManifestTrack>, api: FakeApi = lanApi) {
        api.trackScript = { request ->
            val track = tracks.first { it.persistentId == request.persistentId }
            TrackScript.Body(contentOf(track), startAt = request.rangeFrom)
        }
    }

    fun environment() = SyncEnvironment(
        store = store,
        writer = writer,
        apis = provider,
        lan = LanResolver {
            onDiscover()
            if (lanDelayMs > 0) kotlinx.coroutines.delay(lanDelayMs)
            lanServices
        },
        aliasExists = { aliasExists },
        deleteMissing = deleteMissing,
        volume = volume,
        volumeAvailable = volumeAvailable,
        freeBytes = { freeBytes },
        promote = {
            events += "promote"
            promoteResult
        },
        manualActive = { manualActive },
        onFinished = { run, progress, trigger ->
            finished += Triple(run, progress, trigger)
            events += "finished"
            check(!failOnFinished) { "終了の通知で例外" }
        },
        log = SyncLogger { logs += it },
        now = { clock },
        progress = progress,
        stopCause = { stopCause },
        gate = gate,
        wait = { waits += it },
        isPlayable = { isPlayable(it) },
    )

    suspend fun run(
        deleteApproved: Boolean = false,
        approved: Int = 0,
        reachFailed: Boolean = false,
        rerun: Boolean = false,
    ) = SyncRunner(environment()).run(RunInput(trigger, deleteApproved, approved, reachFailed, rerun))

    val runs get() = store.runRows

    companion object {
        const val LAN_HOST = "192.0.2.10"
        const val TAIL_HOST = "pc.example.invalid"
        const val USB_HOST = "192.0.2.20"

        fun contentOf(track: ManifestTrack) = ByteArray(track.size.toInt()) { (it + track.persistentId.last().code).toByte() }
    }
}
