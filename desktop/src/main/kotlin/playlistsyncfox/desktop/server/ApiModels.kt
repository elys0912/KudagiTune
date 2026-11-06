package playlistsyncfox.desktop.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 失敗の応答 `{"error":"E<番号>","detail":"…"}`（B4-4 の 8）。 */
@Serializable
class ErrorBody(val error: String, val detail: String? = null)

@Serializable
class PairingStartRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
    val x: String,
)

@Serializable
class PairingStartResponse(@SerialName("pc_id") val pcId: String, val y: String)

@Serializable
class PairingConfirmRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("mac_d") val macD: String,
)

@Serializable
class PairingConfirmResponse(@SerialName("mac_p") val macP: String)

@Serializable
class DigestResponse(@SerialName("content_digest") val contentDigest: String)
