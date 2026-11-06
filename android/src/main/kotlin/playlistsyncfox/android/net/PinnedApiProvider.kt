package playlistsyncfox.android.net

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.SigningKeyRef

/**
 * T4 の `spki_hash`（小文字の 16 進 64 桁）で固定したクライアントを、接続先ごとに作る。
 * [crypto] の署名は Keystore の別名（T4 の `key_alias`）で行う。
 */
class PinnedApiProvider(
    private val spkiHash: String,
    private val keyAlias: String,
    private val deviceId: String,
    private val crypto: CryptoProvider,
    private val nowSeconds: () -> Long,
) : ApiProvider {
    override fun open(endpoint: Endpoint): SyncApi {
        val trust = SpkiPinTrustManager(spkiHash.hexToByteArray())
        return PsfoxClient(
            http = createTrustedHttpClient(trust),
            endpoint = endpoint,
            crypto = crypto,
            keyRef = SigningKeyRef.KeystoreAlias(keyAlias),
            deviceId = deviceId,
            nowSeconds = nowSeconds,
            pinMismatched = { trust.mismatched },
        )
    }
}
