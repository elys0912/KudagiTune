package playlistsyncfox.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.compression.Compression
import io.ktor.server.plugins.compression.condition
import io.ktor.server.plugins.compression.gzip
import io.ktor.server.plugins.compression.minimumSize
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.partialcontent.PartialContent
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.secrets.KeyRing
import playlistsyncfox.desktop.settings.AppLog
import playlistsyncfox.desktop.settings.LastSync
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.shared.auth.AuthResult
import playlistsyncfox.shared.auth.NonceStore
import playlistsyncfox.shared.auth.PairingFailure
import playlistsyncfox.shared.auth.PairingOutcome
import playlistsyncfox.shared.auth.SignatureHeaderNames
import playlistsyncfox.shared.auth.SignedRequest
import playlistsyncfox.shared.auth.encodeBase64Url
import playlistsyncfox.shared.auth.verifyRequest
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.manifest.writeManifest
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Base64

/** サーバーのルートが使う部品。偽物に差し替えられるよう、組み立ては呼び出し側が行う。 */
class ServerEnv(
    val crypto: CryptoProvider,
    val clock: Clock,
    val serverStartedAt: Instant,
    val nonces: NonceStore,
    val keyRing: KeyRing,
    val failures: SignatureFailureCounter,
    val pairing: PairingService,
    val pcId: String,
    val settings: SettingsStore,
    val manifests: ManifestService,
    val tracks: TrackTable,
    val activity: ActivityTracker,
    val log: AppLog,
    val clientAddress: (ApplicationCall) -> String = { it.request.origin.remoteAddress },
)

private val BodyKey = AttributeKey<ByteArray>("psfox.body")
private val DeviceKey = AttributeKey<String>("psfox.device")

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val persistentIdPattern = Regex("[0-9A-F]{16}")
private val uuidPattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val timestampPattern = Regex("[0-9]{1,18}")
private val failureKeyPattern = Regex("E[1-9]")
private val isoMillisPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
private val rangeFromPattern = Regex("bytes=([0-9]+)-")

private const val PATH_MANIFEST = "/v1/manifest"
private const val PATH_DIGEST = "/v1/digest"
private const val PATH_REPORTS = "/v1/reports"
private const val PATH_PAIRING_START = "/v1/pairing/start"
private const val PATH_PAIRING_CONFIRM = "/v1/pairing/confirm"
private const val PATH_TRACKS = "/v1/tracks/"

fun Application.psfoxModule(env: ServerEnv) {
    install(Compression) {
        gzip {
            condition { request.path() == PATH_MANIFEST }
            minimumSize(1)
        }
    }
    install(PartialContent)

    // 要求の検査の順（X5-1）と、想定外の例外の変換（500。本文に例外の内容を含めない）をここで 1 か所にまとめる
    intercept(ApplicationCallPipeline.Plugins) {
        try {
            if (checkRequest(env, call)) proceed()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            env.log.error("server", "想定外の例外：${e.javaClass.simpleName}")
            if (!call.response.isCommitted) call.respond(HttpStatusCode.InternalServerError)
        }
        finish()
    }

    routing {
        get(PATH_MANIFEST) {
            val manifest = env.manifests.forManifest() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, 4)
            call.respondText(writeManifest(manifest), ContentType.Application.Json)
        }
        get(PATH_DIGEST) {
            val digest = env.manifests.forDigest() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, 4)
            call.respondText(json.encodeToString(DigestResponse.serializer(), DigestResponse(digest)), ContentType.Application.Json)
        }
        get("$PATH_TRACKS{id}") { sendTrack(env, call) }
        post(PATH_REPORTS) { saveReport(env, call) }
        post(PATH_PAIRING_START) { pairingStart(env, call) }
        post(PATH_PAIRING_CONFIRM) { pairingConfirm(env, call) }
    }
}

/** 検査 1〜7（P1〜P4）と、P5・P6 の前段。通ればルートへ進めるため真を返す。 */
private suspend fun checkRequest(env: ServerEnv, call: ApplicationCall): Boolean {
    // 1. 本文の大きさ
    val body = call.receiveChannel().readRemaining(DesktopValues.MAX_BODY_BYTES + 1L).readByteArray()
    if (body.size > DesktopValues.MAX_BODY_BYTES) {
        call.respond(HttpStatusCode.PayloadTooLarge)
        return false
    }
    call.attributes.put(BodyKey, body)

    // 2. 既知のルート
    val path = call.request.path()
    val needsSignature = when {
        path == PATH_PAIRING_START || path == PATH_PAIRING_CONFIRM -> false
        path == PATH_MANIFEST || path == PATH_DIGEST || path == PATH_REPORTS -> true
        path.startsWith(PATH_TRACKS) && path.length > PATH_TRACKS.length && '/' !in path.substring(PATH_TRACKS.length) -> true
        else -> {
            call.respond(HttpStatusCode.NotFound)
            return false
        }
    }

    // 3. 署名失敗の待ち
    val address = env.clientAddress(call)
    if (env.failures.isBlocked(address)) {
        call.fail(HttpStatusCode.TooManyRequests, 2)
        return false
    }

    if (!needsSignature) {
        // P5 の連続は署名の失敗と同じ数に入れる。P6 は数えず、P5 の連続を途切れさせる
        if (path == PATH_PAIRING_START) env.failures.recordPairingStart(address) else env.failures.recordPairingConfirm(address)
        return true
    }

    // 4. ヘッダ 4 種の書式
    val deviceId = call.request.header(SignatureHeaderNames.DEVICE)
    val timestamp = call.request.header(SignatureHeaderNames.TIMESTAMP)
    val nonce = call.request.header(SignatureHeaderNames.NONCE)?.let(::decodeBase64Url)
    val signature = call.request.header(SignatureHeaderNames.SIGNATURE)
    val malformed = deviceId == null || !uuidPattern.matches(deviceId) ||
        timestamp == null || !timestampPattern.matches(timestamp) ||
        nonce == null || nonce.size != NONCE_BYTES ||
        signature == null || decodeBase64Url(signature)?.size != SIGNATURE_BYTES
    if (malformed) {
        call.respond(HttpStatusCode.BadRequest)
        return false
    }

    // 5〜7. M1 の検証
    val request = SignedRequest(
        method = call.request.httpMethod.value,
        pathAndQuery = call.request.uri,
        timestamp = timestamp!!.toLong(),
        nonce = nonce!!,
        deviceId = deviceId!!,
        body = body,
    )
    val result = verifyRequest(
        env.crypto, request, signature!!,
        now = env.clock.instant().epochSecond,
        serverStartedAt = env.serverStartedAt.epochSecond,
        findKey = env.keyRing::find,
        nonces = env.nonces,
    )
    if (result is AuthResult.Failure) {
        env.failures.recordFailure(address)
        env.log.warn("server", "署名の検証に失敗 device=$deviceId detail=${result.reason.detail ?: "none"}", 2)
        call.fail(HttpStatusCode.Unauthorized, 2, result.reason.detail)
        return false
    }
    env.failures.recordSuccess(address)
    env.activity.signedRequest()
    call.attributes.put(DeviceKey, deviceId)
    return true
}

private const val NONCE_BYTES = 16
private const val SIGNATURE_BYTES = 32

private fun decodeBase64Url(text: String): ByteArray? = try {
    Base64.getUrlDecoder().decode(text)
} catch (_: IllegalArgumentException) {
    null
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, errorNumber: Int, detail: String? = null) =
    respondText(json.encodeToString(ErrorBody.serializer(), ErrorBody("E$errorNumber", detail)), ContentType.Application.Json, status)

/** P2（X5-1 の「P2 の検査」）。 */
private suspend fun sendTrack(env: ServerEnv, call: ApplicationCall) {
    val id = call.request.path().removePrefix(PATH_TRACKS)
    if (!persistentIdPattern.matches(id)) return call.respond(HttpStatusCode.BadRequest)

    env.activity.trackRequestStarted()
    try {
        val table = env.tracks.snapshot() ?: return call.fail(HttpStatusCode.ServiceUnavailable, 4)
        val entry = table[id] ?: return call.fail(HttpStatusCode.NotFound, 5)

        val query = call.request.queryParameters
        if (query["size"]?.toLongOrNull() != entry.size || query["modified_at"]?.toLongOrNull() != entry.modifiedAt) {
            return call.fail(HttpStatusCode.Conflict, 8)
        }
        if (!Files.isRegularFile(entry.path)) return call.fail(HttpStatusCode.NotFound, 5)
        if (Files.size(entry.path) != entry.size) return call.fail(HttpStatusCode.Conflict, 8)

        val start = call.request.header(HttpHeaders.Range)?.let { rangeFromPattern.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (start != null && start >= entry.size) return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)

        call.response.header("X-Psfox-Size", entry.size.toString())
        call.response.header("X-Psfox-Modified-At", entry.modifiedAt.toString())
        call.respond(LocalFileContent(entry.path.toFile(), ContentType.Application.OctetStream))
    } finally {
        env.activity.trackRequestFinished()
    }
}

/** P4。必須項目の欠けと型の違いは 400（E なし）で保存しない。保存済みより古い報告は上書きせず 204。 */
private suspend fun saveReport(env: ServerEnv, call: ApplicationCall) {
    val report = try {
        json.decodeFromString(LastSync.serializer(), call.attributes[BodyKey].decodeToString())
    } catch (_: SerializationException) {
        return call.respond(HttpStatusCode.BadRequest)
    }
    val startedAt = parseReportTime(report.startedAt)
    if (startedAt == null || parseReportTime(report.finishedAt) == null ||
        report.failureCounts.keys.any { !failureKeyPattern.matches(it) }
    ) {
        return call.respond(HttpStatusCode.BadRequest)
    }

    val deviceId = call.attributes[DeviceKey]
    env.settings.update { settings ->
        settings.copy(
            devices = settings.devices.map { device ->
                val saved = device.lastSync?.let { parseReportTime(it.startedAt) }
                if (device.deviceId != deviceId || (saved != null && startedAt.isBefore(saved))) device else device.copy(lastSync = report)
            },
        )
    }
    call.respond(HttpStatusCode.NoContent)
}

private fun parseReportTime(text: String): Instant? =
    if (!isoMillisPattern.matches(text)) {
        null
    } else {
        try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            null
        }
    }

/** P5。失敗の順と本文は X4-2-2。 */
private suspend fun pairingStart(env: ServerEnv, call: ApplicationCall) {
    val request = try {
        json.decodeFromString(PairingStartRequest.serializer(), call.attributes[BodyKey].decodeToString())
    } catch (_: SerializationException) {
        return call.respond(HttpStatusCode.BadRequest)
    }
    val x = decodeBase64Url(request.x)
    if (x == null || !uuidPattern.matches(request.deviceId)) return call.respond(HttpStatusCode.BadRequest)

    when (val outcome = env.pairing.start(request.deviceId, request.deviceName, x)) {
        is PairingOutcome.Ok -> call.respondText(
            json.encodeToString(PairingStartResponse.serializer(), PairingStartResponse(env.pcId, encodeBase64Url(outcome.value.encoded))),
            ContentType.Application.Json,
        )
        is PairingOutcome.Failed -> respondPairingFailure(env, call, outcome.failure)
    }
}

/** P6。成功の応答の前に、K1 の保存と `devices` への追加を済ませる（shared が保存を呼んでから MAC_p を返す）。 */
private suspend fun pairingConfirm(env: ServerEnv, call: ApplicationCall) {
    val request = try {
        json.decodeFromString(PairingConfirmRequest.serializer(), call.attributes[BodyKey].decodeToString())
    } catch (_: SerializationException) {
        return call.respond(HttpStatusCode.BadRequest)
    }
    val macD = decodeBase64Url(request.macD)
    if (macD == null || !uuidPattern.matches(request.deviceId)) return call.respond(HttpStatusCode.BadRequest)

    when (val outcome = env.pairing.confirm(request.deviceId, macD)) {
        is PairingOutcome.Ok -> {
            env.log.info("pairing", "ペアリングが成功した device=${request.deviceId}")
            call.respondText(
                json.encodeToString(PairingConfirmResponse.serializer(), PairingConfirmResponse(encodeBase64Url(outcome.value))),
                ContentType.Application.Json,
            )
        }
        is PairingOutcome.Failed -> respondPairingFailure(env, call, outcome.failure)
    }
}

private suspend fun respondPairingFailure(env: ServerEnv, call: ApplicationCall, failure: PairingFailure) {
    env.log.warn("pairing", "ペアリングの要求を断った reason=${failure.name}", 2.takeIf { failure.detail != null || failure == PairingFailure.MacMismatch })
    val status = HttpStatusCode.fromValue(failure.status)
    if (failure.status == 400 || failure.status == 500) call.respond(status) else call.fail(status, 2, failure.detail)
}
