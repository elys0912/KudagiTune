package playlistsyncfox.android.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.auth.encodeBase64Url
import java.util.Base64

private val json = Json { ignoreUnknownKeys = true }

/** P5・P6 の 1 回の呼び出しの結果。署名の無い通信なので、E の番号には写さず画面の段へ写す。 */
sealed interface PairingCall<out T> {
    data class Ok<T>(val value: T) : PairingCall<T>

    /** PC が断った。[detail] は応答の本文の `detail`（`pairing_closed` など）。無ければ null。 */
    data class Refused(val status: Int, val detail: String?) : PairingCall<Nothing>

    /** 接続できない、切れた、時間切れ。 */
    data object Disconnected : PairingCall<Nothing>

    /** 応答の形が違う。 */
    data object BadReply : PairingCall<Nothing>
}

class StartReply(val pcId: String, val y: ByteArray)

/**
 * P5・P6（X6-5）。証明書を検証せず、提示された SPKI のハッシュを記録する固定前の専用クライアントを使う。
 * 固定後の P1〜P4 のクライアントとは共用しない（X6 の決定 6）。[spkiHash] は直近の接続で見た PC の SPKI のハッシュ。
 * コードと鍵、印は本文以外に出さない。
 */
class PairingClient(
    private val http: HttpClient,
    private val host: String,
    private val port: Int,
    private val seenSpkiHash: () -> ByteArray?,
) : AutoCloseable {
    val spkiHash: ByteArray? get() = seenSpkiHash()

    suspend fun start(deviceId: String, deviceName: String, x: ByteArray): PairingCall<StartReply> =
        call(
            "/v1/pairing/start",
            buildJsonObject {
                put("device_id", deviceId)
                put("device_name", deviceName)
                put("x", encodeBase64Url(x))
            },
        ) { body ->
            val y = body.string("y")?.let(::decodeBase64Url)
            val pcId = body.string("pc_id")
            if (y == null || pcId == null) null else StartReply(pcId, y)
        }

    suspend fun confirm(deviceId: String, macD: ByteArray): PairingCall<ByteArray> =
        call(
            "/v1/pairing/confirm",
            buildJsonObject {
                put("device_id", deviceId)
                put("mac_d", encodeBase64Url(macD))
            },
        ) { body -> body.string("mac_p")?.let(::decodeBase64Url) }

    override fun close() = http.close()

    private suspend fun <T> call(path: String, body: JsonObject, read: (JsonObject) -> T?): PairingCall<T> {
        val response = try {
            http.post {
                url("https://$host:$port$path")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
                timeout {
                    connectTimeoutMillis = SyncValues.CONNECT_WAIT_MS
                    requestTimeoutMillis = SyncValues.CONNECT_WAIT_MS + SyncValues.DIGEST_RESPONSE_WAIT_MS
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return PairingCall.Disconnected
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return PairingCall.Disconnected
        }
        val parsed = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            null
        }
        if (response.status.value != 200) {
            return PairingCall.Refused(response.status.value, parsed?.string("detail"))
        }
        return parsed?.let(read)?.let { PairingCall.Ok(it) } ?: PairingCall.BadReply
    }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun decodeBase64Url(text: String): ByteArray? = try {
        Base64.getUrlDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        null
    }

    companion object {
        fun open(host: String, port: Int): PairingClient {
            val trust = RecordingTrustManager()
            return PairingClient(createTrustedHttpClient(trust), host, port) { trust.spkiHash }
        }
    }
}
