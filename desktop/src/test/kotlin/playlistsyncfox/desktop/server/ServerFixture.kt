package playlistsyncfox.desktop.server

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.desktop.MemorySecretStore
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.emptyLibrary
import playlistsyncfox.desktop.vault.KeyRing
import playlistsyncfox.desktop.vault.SecretStore
import playlistsyncfox.desktop.settings.AppLog
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.watch.HeldLibrary
import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.LibraryTrack
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import playlistsyncfox.itunes.location.TrackLocation
import playlistsyncfox.shared.auth.NonceStore
import playlistsyncfox.shared.auth.SignatureHeaderNames
import playlistsyncfox.shared.auth.SignatureHeaders
import playlistsyncfox.shared.auth.SignedRequest
import playlistsyncfox.shared.auth.encodeBase64Url
import playlistsyncfox.shared.auth.signRequest
import playlistsyncfox.shared.crypto.SigningKeyRef
import java.nio.file.Path
import java.security.SecureRandom
import java.time.Duration

const val DEVICE = "00000000-0000-4000-8000-000000000001"
const val OTHER_DEVICE = "00000000-0000-4000-8000-000000000002"
const val PC_ID = "00000000-0000-4000-8000-0000000000a1"
const val PLAYLIST_ID = "2000000000000001"
const val TRACK_ID = "1000000000000001"
val DEVICE_KEY = ByteArray(32) { (it + 1).toByte() }
val SPKI_HASH = ByteArray(32) { it.toByte() }

/** サーバーのルートを試すための部品一式。実ネットワークは使わず、鍵の保管は記憶の中、時刻は固定。 */
class ServerFixture(val root: Path, val store: SecretStore = MemorySecretStore(), now: java.time.Instant = T0.plusSeconds(60)) {
    val clock = MutableClock(now)
    val crypto = PcCrypto()
    val settings = SettingsStore(root.resolve("settings.json")) { PC_ID }.also { it.load() }
    val keyRing = KeyRing(store).also { it.load(settings.current.devices.map { device -> device.deviceId }) }
    val registry = DeviceRegistry(keyRing, settings, clock)
    val pairing = PairingService(crypto, clock, PC_ID, SPKI_HASH, settings, registry)
    val tracks = TrackTable()
    var library: HeldLibrary? = null
    val manifests = ManifestService(crypto, clock, settings, tracks) { library }
    val activity = ActivityTracker(clock)
    val logDir: Path = root.resolve("logs")
    val log = AppLog(logDir, clock)
    val env = ServerEnv(
        crypto, clock, T0, NonceStore(), keyRing, SignatureFailureCounter(clock), pairing,
        PC_ID, settings, manifests, tracks, activity, log,
    )

    fun addDevice(id: String = DEVICE, key: ByteArray = DEVICE_KEY) = check(registry.register(id, "端末の名前", key))

    /** 1 つの曲を持つ XML を読めた状態にし、そのプレイリストを選ぶ。 */
    fun useLibrary(trackFile: Path) {
        val track = LibraryTrack(TRACK_ID, TrackLocation.Found(trackFile.toString()), "File", "曲", "歌手", "アルバム", null, 1, 1, 1000, "MPEG", false)
        val playlist = LibraryPlaylist(PLAYLIST_ID, "リスト", null, PlaylistKind.Normal, listOf(TrackRef.Identified(TRACK_ID)))
        library = HeldLibrary(LibraryReadResult("LIB", listOf(track), emptyList(), listOf(playlist), emptyLibrary().diagnostics), T0)
        settings.update { it.copy(selectedPlaylistIds = listOf(PLAYLIST_ID)) }
    }

    fun elapse(duration: Duration) = clock.advance(duration)
}

fun withServer(fixture: ServerFixture, block: suspend (HttpClient) -> Unit) = testApplication {
    application { psfoxModule(fixture.env) }
    block(client)
}

private val random = SecureRandom()

fun sign(
    fixture: ServerFixture,
    method: HttpMethod,
    uri: String,
    body: ByteArray = ByteArray(0),
    device: String = DEVICE,
    key: ByteArray = DEVICE_KEY,
    timestamp: Long = fixture.clock.instant().epochSecond,
): SignatureHeaders = signRequest(
    fixture.crypto,
    SigningKeyRef.KeyBytes(key),
    SignedRequest(method.value, uri, timestamp, ByteArray(16).also(random::nextBytes), device, body),
)

/** 署名つきの要求を送る。署名の対象は送る文字列のまま。 */
suspend fun HttpClient.signed(
    fixture: ServerFixture,
    method: HttpMethod,
    uri: String,
    body: ByteArray = ByteArray(0),
    device: String = DEVICE,
    key: ByteArray = DEVICE_KEY,
    timestamp: Long = fixture.clock.instant().epochSecond,
    extra: HttpRequestBuilder.() -> Unit = {},
): HttpResponse {
    val headers = sign(fixture, method, uri, body, device, key, timestamp)
    return request(uri) {
        this.method = method
        header(SignatureHeaderNames.DEVICE, headers.device)
        header(SignatureHeaderNames.TIMESTAMP, headers.timestamp)
        header(SignatureHeaderNames.NONCE, headers.nonce)
        header(SignatureHeaderNames.SIGNATURE, headers.signature)
        if (method == HttpMethod.Post) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        extra()
    }
}

suspend fun HttpClient.postJson(uri: String, json: String): HttpResponse = request(uri) {
    method = HttpMethod.Post
    contentType(ContentType.Application.Json)
    setBody(json)
}

suspend fun HttpResponse.json(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

suspend fun HttpResponse.detail(): String? = json()["detail"]?.jsonPrimitive?.content

fun base64Url(bytes: ByteArray): String = encodeBase64Url(bytes)
