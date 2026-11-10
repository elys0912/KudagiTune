package playlistsyncfox.desktop.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
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
import io.ktor.http.isSuccess
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.vault.KeyRing
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
import playlistsyncfox.shared.manifest.PlaylistIdsResult
import playlistsyncfox.shared.manifest.isPersistentId
import playlistsyncfox.shared.manifest.parsePlaylistIds
import playlistsyncfox.shared.manifest.writeManifest
import playlistsyncfox.shared.manifest.writePlaylistTree
import playlistsyncfox.shared.result.readProgressReport
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
    val progress: ProgressBoard,
    val log: AppLog,
    val clientAddress: (ApplicationCall) -> String = { it.request.origin.remoteAddress },
)

private val BodyKey = AttributeKey<ByteArray>("psfox.body")
private val DeviceKey = AttributeKey<String>("psfox.device")

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val uuidPattern = Regex("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")
private val timestampPattern = Regex("[0-9]{1,18}")
private val failureKeyPattern = Regex("E[1-9]")
private val isoMillisPattern = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")
private val rangeFromPattern = Regex("bytes=([0-9]+)-")

private const val PATH_MANIFEST = "/v1/manifest"
private const val PATH_DIGEST = "/v1/digest"
private const val PATH_PLAYLISTS = "/v1/playlists"
private const val PATH_REPORTS = "/v1/reports"
private const val PATH_PROGRESS = "/v1/progress"
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
        post(PATH_MANIFEST) {
            val ids = selectedIds(call) ?: return@post
            val manifest = env.manifests.forManifest(ids) ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, ERROR_XML_UNREADABLE)
            call.respondText(writeManifest(manifest), ContentType.Application.Json)
            env.log.info("server", "P1 を返した tracks=${manifest.tracks.size}")
        }
        post(PATH_DIGEST) {
            val ids = selectedIds(call) ?: return@post
            val digest = env.manifests.forDigest(ids) ?: return@post call.fail(HttpStatusCode.ServiceUnavailable, ERROR_XML_UNREADABLE)
            call.respondText(json.encodeToString(DigestResponse.serializer(), DigestResponse(digest)), ContentType.Application.Json)
        }
        get(PATH_PLAYLISTS) {
            val tree = env.manifests.forPlaylists() ?: return@get call.fail(HttpStatusCode.ServiceUnavailable, ERROR_XML_UNREADABLE)
            call.respondText(writePlaylistTree(tree), ContentType.Application.Json)
        }
        get("$PATH_TRACKS{id}") { sendTrack(env, call) }
        post(PATH_REPORTS) { saveReport(env, call) }
        post(PATH_PROGRESS) { saveProgress(env, call) }
        post(PATH_PAIRING_START) { pairingStart(env, call) }
        post(PATH_PAIRING_CONFIRM) { pairingConfirm(env, call) }
    }
}

/** 要求の宛先の種別。署名が要るかと、既知かどうかを決める。 */
private enum class RouteKind { Signed, Pairing, Unknown }

private fun routeKindOf(path: String): RouteKind = when {
    path == PATH_PAIRING_START || path == PATH_PAIRING_CONFIRM -> RouteKind.Pairing
    path == PATH_MANIFEST || path == PATH_DIGEST || path == PATH_PLAYLISTS || path == PATH_REPORTS || path == PATH_PROGRESS -> RouteKind.Signed
    path.startsWith(PATH_TRACKS) && path.length > PATH_TRACKS.length && '/' !in path.substring(PATH_TRACKS.length) -> RouteKind.Signed
    else -> RouteKind.Unknown
}

/** 検査 4 を通った、署名つき要求のヘッダ 4 種。[timestamp] は数値、[nonce] は復号済み、[signature] は送られた文字列のまま。 */
private class ParsedSignatureHeaders(val deviceId: String, val timestamp: Long, val nonce: ByteArray, val signature: String)

/** 検査 4：ヘッダ 4 種の書式。1 つでも欠けるか書式が違えば null。 */
private fun parseSignatureHeaders(call: ApplicationCall): ParsedSignatureHeaders? {
    val deviceId = call.request.header(SignatureHeaderNames.DEVICE)
    val timestamp = call.request.header(SignatureHeaderNames.TIMESTAMP)
    val nonce = call.request.header(SignatureHeaderNames.NONCE)?.let(::decodeBase64Url)
    val signature = call.request.header(SignatureHeaderNames.SIGNATURE)
    if (deviceId == null || !uuidPattern.matches(deviceId)) return null
    if (timestamp == null || !timestampPattern.matches(timestamp)) return null
    if (nonce == null || nonce.size != NONCE_BYTES) return null
    if (signature == null || decodeBase64Url(signature)?.size != SIGNATURE_BYTES) return null
    return ParsedSignatureHeaders(deviceId, timestamp.toLong(), nonce, signature)
}

/** 検査 1〜7（P1〜P4、P7、P8）と、P5・P6 の前段。通ればルートへ進めるため真を返す。 */
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
    val routeKind = routeKindOf(path)
    if (routeKind == RouteKind.Unknown) {
        call.respond(HttpStatusCode.NotFound)
        return false
    }

    // 3. 署名失敗の待ち
    val address = env.clientAddress(call)
    if (env.failures.isBlocked(address)) {
        call.fail(HttpStatusCode.TooManyRequests, ERROR_AUTH_REJECTED)
        return false
    }

    if (routeKind == RouteKind.Pairing) {
        // P5 の連続は署名の失敗と同じ数に入れる。P6 は数えず、P5 の連続を途切れさせる
        if (path == PATH_PAIRING_START) env.failures.recordPairingStart(address) else env.failures.recordPairingConfirm(address)
        return true
    }

    // 4. ヘッダ 4 種の書式
    val headers = parseSignatureHeaders(call)
    if (headers == null) {
        call.respond(HttpStatusCode.BadRequest)
        return false
    }

    // 5〜7. M1 の検証
    val request = SignedRequest(
        method = call.request.httpMethod.value,
        pathAndQuery = call.request.uri,
        timestamp = headers.timestamp,
        nonce = headers.nonce,
        deviceId = headers.deviceId,
        body = body,
    )
    val result = verifyRequest(
        env.crypto, request, headers.signature,
        now = env.clock.instant().epochSecond,
        serverStartedAt = env.serverStartedAt.epochSecond,
        findKey = env.keyRing::find,
        nonces = env.nonces,
    )
    if (result is AuthResult.Failure) {
        env.failures.recordFailure(address)
        env.log.warn("server", "署名の検証に失敗 device=${headers.deviceId} detail=${result.reason.detail ?: "none"}", ERROR_AUTH_REJECTED)
        call.fail(HttpStatusCode.Unauthorized, ERROR_AUTH_REJECTED, result.reason.detail)
        return false
    }
    env.failures.recordSuccess(address)
    env.activity.signedRequest()
    call.attributes.put(DeviceKey, headers.deviceId)
    return true
}

private const val NONCE_BYTES = 16
private const val SIGNATURE_BYTES = 32

// 応答の本文 `{"error":"E<n>"}` の番号。HTTP の状態との対応は B4-4 の 8

/** E2：署名・ペアリングの不一致（401）、ペアリングの拒否（403）、署名失敗の待ち（429）。 */
private const val ERROR_AUTH_REJECTED = 2

/** E4：XML を読めずマニフェストを作れない、または曲の表が空（503）。 */
private const val ERROR_XML_UNREADABLE = 4

/** E5：マニフェストに無い ID、またはファイルが無い（404）。 */
private const val ERROR_TRACK_NOT_FOUND = 5

/** E8：`size`・`modified_at` が P1 の値と合わない（409）。 */
private const val ERROR_TRACK_CHANGED = 8

private fun decodeBase64Url(text: String): ByteArray? = try {
    Base64.getUrlDecoder().decode(text)
} catch (_: IllegalArgumentException) {
    null
}

/** 検査 1 で読んだ本文を文字列にしたもの。 */
private fun ApplicationCall.bodyText(): String = attributes[BodyKey].decodeToString()

/**
 * 本文を JSON として読む。読めなければ 400 を返して null。本文の中身は応答にもログにも出さない。
 * `decodeFromString` は JSON の誤りで `SerializationException`、型の値にできないときに `IllegalArgumentException` を投げる。前者は後者の子なので、後者だけを捕まえる。
 */
private suspend fun <T> decodeBody(call: ApplicationCall, serializer: KSerializer<T>): T? = try {
    json.decodeFromString(serializer, call.bodyText())
} catch (_: IllegalArgumentException) {
    call.respond(HttpStatusCode.BadRequest)
    null
}

private suspend fun ApplicationCall.fail(status: HttpStatusCode, errorNumber: Int, detail: String? = null) =
    respondText(json.encodeToString(ErrorBody.serializer(), ErrorBody("E$errorNumber", detail)), ContentType.Application.Json, status)

/** P1・P3 の本文（選んだ ID の配列）の検査。署名の検証の後に、境界で行う。誤りは 400 で、本文の中身は応答にもログにも出さない。 */
private suspend fun selectedIds(call: ApplicationCall): List<String>? =
    when (val parsed = parsePlaylistIds(call.bodyText(), DesktopValues.MAX_PLAYLIST_IDS)) {
        is PlaylistIdsResult.Ids -> parsed.ids
        PlaylistIdsResult.Invalid -> {
            call.respond(HttpStatusCode.BadRequest)
            null
        }
    }

/** P2（X5-1 の「P2 の検査」）。 */
private suspend fun sendTrack(env: ServerEnv, call: ApplicationCall) {
    val id = call.request.path().removePrefix(PATH_TRACKS)
    if (!isPersistentId(id)) return call.respond(HttpStatusCode.BadRequest)

    env.activity.trackRequestStarted()
    try {
        val table = env.tracks.snapshot() ?: return call.fail(HttpStatusCode.ServiceUnavailable, ERROR_XML_UNREADABLE)
        val entry = table[id] ?: return call.fail(HttpStatusCode.NotFound, ERROR_TRACK_NOT_FOUND)

        val query = call.request.queryParameters
        if (query["size"]?.toLongOrNull() != entry.size || query["modified_at"]?.toLongOrNull() != entry.modifiedAt) {
            return call.fail(HttpStatusCode.Conflict, ERROR_TRACK_CHANGED)
        }
        if (!Files.isRegularFile(entry.path)) return call.fail(HttpStatusCode.NotFound, ERROR_TRACK_NOT_FOUND)
        if (Files.size(entry.path) != entry.size) return call.fail(HttpStatusCode.Conflict, ERROR_TRACK_CHANGED)

        val start = call.request.header(HttpHeaders.Range)?.let { rangeFromPattern.matchEntire(it)?.groupValues?.get(1)?.toLongOrNull() }
        if (start != null && start >= entry.size) return call.respond(HttpStatusCode.RequestedRangeNotSatisfiable)

        call.response.header("X-Psfox-Size", entry.size.toString())
        call.response.header("X-Psfox-Modified-At", entry.modifiedAt.toString())

        // 表示名は実ファイル名から拡張子を除いたもの。送ったバイト数は ActivityTracker が数える（D22）
        val file = entry.path.toFile()
        val name = file.nameWithoutExtension
        val content = CountingFileContent(file, ContentType.Application.OctetStream, env.activity.trackSendStarted(name, entry.size))
        try {
            call.respond(content)
        } catch (e: Throwable) {
            env.log.info("server", "P2 を途中で切られた name=$name")
            throw e
        }
        if (call.response.status()?.isSuccess() == true) env.log.info("server", "P2 を送り終えた name=$name size=${entry.size}")
    } finally {
        env.activity.trackRequestFinished()
    }
}

/** P4。必須項目の欠けと型の違いは 400（E なし）で保存しない。保存済みより古い報告は上書きせず 204。 */
private suspend fun saveReport(env: ServerEnv, call: ApplicationCall) {
    val report = decodeBody(call, LastSync.serializer()) ?: return
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
    env.progress.clear()
    env.log.info("server", "P4 を受けた outcome=${report.outcome.name.lowercase()}")
    call.respond(HttpStatusCode.NoContent)
}

/** P8（B4-4 の 5b）。境界で検査し、メモリの [ProgressBoard] に置く。保存しない。 */
private suspend fun saveProgress(env: ServerEnv, call: ApplicationCall) {
    val report = readProgressReport(call.bodyText()) ?: return call.respond(HttpStatusCode.BadRequest)
    env.progress.update(report)
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
    val request = decodeBody(call, PairingStartRequest.serializer()) ?: return
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
    val request = decodeBody(call, PairingConfirmRequest.serializer()) ?: return
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
    // E2 の応答になる失敗（403 の 3 種と P6 の MAC の不一致）だけ、ログにも E の番号を付ける
    val isLoggedWithErrorNumber = failure.detail != null || failure == PairingFailure.MacMismatch
    env.log.warn("pairing", "ペアリングの要求を断った reason=${failure.name}", ERROR_AUTH_REJECTED.takeIf { isLoggedWithErrorNumber })

    // 400 と 500 は E の本文を持たない
    val status = HttpStatusCode.fromValue(failure.status)
    val hasNoErrorBody = failure.status == HttpStatusCode.BadRequest.value || failure.status == HttpStatusCode.InternalServerError.value
    if (hasNoErrorBody) call.respond(status) else call.fail(status, ERROR_AUTH_REJECTED, failure.detail)
}
