package playlistsyncfox.android.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.android.storage.SourceDisconnectedException
import playlistsyncfox.android.storage.TrackSource
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.auth.SignatureHeaderNames
import playlistsyncfox.shared.auth.SignedRequest
import playlistsyncfox.shared.auth.signRequest
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.SigningKeyRef
import playlistsyncfox.shared.manifest.writePlaylistIds
import playlistsyncfox.shared.result.ErrorDetail
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

private const val NONCE_BYTES = 16
private val rangeStart = Regex("bytes ([0-9]+)-")
private val json = Json { ignoreUnknownKeys = true }

/**
 * 接続先 1 件に対する P1〜P4、P7、P8（X6-5）。要求ごとに署名ヘッダを付ける。再開のたびに新しい nonce と時刻で署名し直す。
 * 鍵、署名、nonce はログにも例外の文にも入れない。
 */
class PsfoxClient(
    private val http: HttpClient,
    private val endpoint: Endpoint,
    private val crypto: CryptoProvider,
    private val keyRef: SigningKeyRef,
    private val deviceId: String,
    private val nowSeconds: () -> Long,
    private val pinMismatched: () -> Boolean = { false },
) : SyncApi {
    override suspend fun digest(playlistIds: List<String>): ApiResult<String> {
        val body = writePlaylistIds(playlistIds).encodeToByteArray()
        return send(HttpMethod.Post, "/v1/digest", body, Timeouts.Digest, config = {
            contentType(ContentType.Application.Json)
            setBody(body)
        }) { response ->
            if (response.status.value == 200) parse(response) { it.jsonObject.getValue("content_digest").jsonPrimitive.content } else failure(response, false)
        }
    }

    override suspend fun manifest(playlistIds: List<String>): ApiResult<String> {
        val body = writePlaylistIds(playlistIds).encodeToByteArray()
        return send(HttpMethod.Post, "/v1/manifest", body, Timeouts.Body, config = {
            header(HttpHeaders.AcceptEncoding, "gzip")
            contentType(ContentType.Application.Json)
            setBody(body)
        }) { response ->
            if (response.status.value == 200) ApiResult.Ok(decodeBody(response)) else failure(response, false)
        }
    }

    override suspend fun playlists(): ApiResult<String> =
        send(HttpMethod.Get, "/v1/playlists", byteArrayOf(), Timeouts.Body) { response ->
            if (response.status.value == 200) ApiResult.Ok(decodeBody(response)) else failure(response, false)
        }

    override suspend fun <T> track(
        persistentId: String,
        size: Long,
        modifiedAt: Long,
        rangeFrom: Long,
        block: suspend (TrackResponse) -> T,
    ): ApiResult<T> =
        send(HttpMethod.Get, "/v1/tracks/$persistentId?size=$size&modified_at=$modifiedAt", byteArrayOf(), Timeouts.Body, config = {
            // 曲は圧縮済み。圧縮を求めると Range と Content-Length を保てない（X6 の決定 7）
            header(HttpHeaders.AcceptEncoding, "identity")
            if (rangeFrom > 0) header(HttpHeaders.Range, "bytes=$rangeFrom-")
        }) { response ->
            val startAt = when (response.status.value) {
                200 -> 0L
                206 -> {
                    val start = response.headers[HttpHeaders.ContentRange]?.let { rangeStart.find(it) }?.groupValues?.get(1)?.toLongOrNull()
                    // 送った位置と違えば書き込まずに取り直す
                    if (rangeFrom == 0L || start != rangeFrom) return@send ApiResult.Fail(ApiFailure.RangeMismatch)
                    start
                }
                else -> return@send failure(response, true)
            }
            val channel = response.bodyAsChannel()
            val source = object : TrackSource {
                override suspend fun read(buffer: ByteArray): Int = try {
                    channel.readAvailable(buffer, 0, buffer.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw SourceDisconnectedException(e)
                }
            }
            ApiResult.Ok(
                guarded { block(
                    TrackResponse(
                        startAt = startAt,
                        sizeHeader = response.headers["X-Psfox-Size"]?.toLongOrNull(),
                        modifiedAtHeader = response.headers["X-Psfox-Modified-At"]?.toLongOrNull(),
                        source = source,
                    ),
                ) },
            )
        }

    override suspend fun report(body: String): ApiResult<Unit> {
        val bytes = body.encodeToByteArray()
        return send(HttpMethod.Post, "/v1/reports", bytes, Timeouts.Body, config = {
            contentType(ContentType.Application.Json)
            setBody(bytes)
        }) { response ->
            if (response.status.value == 204 || response.status.value == 200) ApiResult.Ok(Unit) else failure(response, false)
        }
    }

    override suspend fun progress(body: String): ApiResult<Unit> {
        val bytes = body.encodeToByteArray()
        return send(HttpMethod.Post, "/v1/progress", bytes, Timeouts.Progress, config = {
            contentType(ContentType.Application.Json)
            setBody(bytes)
        }) { response ->
            if (response.status.value == 204 || response.status.value == 200) ApiResult.Ok(Unit) else failure(response, false)
        }
    }

    override fun close() = http.close()

    private enum class Timeouts { Digest, Body, Progress }

    private class BlockFailure(val original: Exception) : Exception()

    /** 呼び出し元の処理の例外を、通信の例外と取り違えないよう包む。 */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw BlockFailure(e)
    }

    private suspend fun <T> send(
        method: HttpMethod,
        pathAndQuery: String,
        body: ByteArray,
        timeouts: Timeouts,
        config: HttpRequestBuilder.() -> Unit = {},
        handle: suspend (HttpResponse) -> ApiResult<T>,
    ): ApiResult<T> {
        val signed = try {
            signRequest(
                crypto, keyRef,
                SignedRequest(method.value, pathAndQuery, nowSeconds(), crypto.randomBytes(NONCE_BYTES), deviceId, body),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Keystore の鍵が使えない（失効、端末の変更）。例外の中身は出さず、鍵の不一致として扱う（X6-6）
            return ApiResult.Fail(ApiFailure.Rejected(detail = ErrorDetail.NoDetail, keyMismatch = true))
        }

        return try {
            http.prepareRequest {
                this.method = method
                url("https://${endpoint.host}:${endpoint.port}$pathAndQuery")
                header(SignatureHeaderNames.DEVICE, signed.device)
                header(SignatureHeaderNames.TIMESTAMP, signed.timestamp)
                header(SignatureHeaderNames.NONCE, signed.nonce)
                header(SignatureHeaderNames.SIGNATURE, signed.signature)
                timeout {
                    connectTimeoutMillis = SyncValues.CONNECT_WAIT_MS
                    // P3 の応答の待ちは V53。接続の確立の待ち（V29）と合わせた全体の上限にする
                    requestTimeoutMillis = when (timeouts) {
                        Timeouts.Digest -> SyncValues.CONNECT_WAIT_MS + SyncValues.DIGEST_RESPONSE_WAIT_MS
                        // P8 の応答の待ちは V29（D22）
                        Timeouts.Progress -> SyncValues.CONNECT_WAIT_MS
                        Timeouts.Body -> null
                    }
                    socketTimeoutMillis = SyncValues.DIGEST_RESPONSE_WAIT_MS
                }
                config()
            }.execute { handle(it) }
        } catch (e: BlockFailure) {
            // 受け手（書き込み）の失敗は通信の切断ではない。そのまま呼び出し元へ返す
            throw e.original
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Fail(ApiFailure.Disconnected)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 不一致で切った接続は、フラグで他の切断と分ける
            ApiResult.Fail(if (pinMismatched()) ApiFailure.PinMismatch else ApiFailure.Disconnected)
        }
    }

    /** X6-5 の対応表。[isTrack] は 404 が E5（曲なし）か、ルート不明（E なし）かの区別。 */
    private suspend fun failure(response: HttpResponse, isTrack: Boolean): ApiResult.Fail = ApiResult.Fail(
        when (response.status.value) {
            401 -> {
                val detail = errorDetail(response)
                ApiFailure.Rejected(detail = detail ?: ErrorDetail.NoDetail, keyMismatch = detail == null)
            }
            403 -> ApiFailure.Rejected(detail = null, keyMismatch = false)
            429 -> ApiFailure.Rejected(detail = ErrorDetail.RateLimited, keyMismatch = false)
            503 -> ApiFailure.XmlUnavailable
            409 -> ApiFailure.Stale
            416 -> ApiFailure.RangeNotSatisfiable
            404 -> if (isTrack) ApiFailure.FileMissing else ApiFailure.ServerFault
            else -> ApiFailure.ServerFault
        },
    )

    /** `{"error":"E2","detail":"clock_skew"}` の detail。無い、知らない語は null。 */
    private suspend fun errorDetail(response: HttpResponse): ErrorDetail? = try {
        when (json.parseToJsonElement(response.bodyAsText()).jsonObject["detail"]?.jsonPrimitive?.content) {
            "clock_skew" -> ErrorDetail.ClockSkew
            "replay" -> ErrorDetail.Replay
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun decodeBody(response: HttpResponse): String {
        val bytes = response.bodyAsBytes()
        return if (response.headers[HttpHeaders.ContentEncoding]?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }.decodeToString()
        } else {
            bytes.decodeToString()
        }
    }

    private suspend fun <T> parse(response: HttpResponse, read: (kotlinx.serialization.json.JsonElement) -> T): ApiResult<T> = try {
        ApiResult.Ok(read(json.parseToJsonElement(response.bodyAsText())))
    } catch (_: Exception) {
        ApiResult.Fail(ApiFailure.ServerFault)
    }
}
