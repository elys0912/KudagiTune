package playlistsyncfox.desktop.vault

import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import playlistsyncfox.desktop.settings.writeAtomically
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/** 復号に失敗した（別の利用者の暗号化、破損）。空として扱わず、呼び出し側がサーバーを起動しない。 */
class SecretStoreException(message: String) : Exception(message)

/** K1・K2 の窓口（X5-2）。名前は `device/<device_id>` または `tls-key`。 */
interface SecretStore {
    fun read(name: String): ByteArray?

    fun write(name: String, value: ByteArray)

    /** 無くても失敗にしない。 */
    fun delete(name: String)
}

/** secrets.bin の中身を暗号化・復号する部品。実体は DPAPI。試験では印を付ける実体に差し替える。 */
interface SecretCipher {
    fun encrypt(plain: ByteArray): ByteArray

    /** 復号できなければ [SecretStoreException]。 */
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** DPAPI（CurrentUser）。追加のエントロピーは固定の文字列。実環境の確認は C-13。 */
class DpapiCipher : SecretCipher {
    override fun encrypt(plain: ByteArray): ByteArray = Crypt32Util.cryptProtectData(plain, ENTROPY, 0, null, null)

    override fun decrypt(encrypted: ByteArray): ByteArray = try {
        Crypt32Util.cryptUnprotectData(encrypted, ENTROPY, 0, null)
    } catch (_: RuntimeException) {
        throw SecretStoreException("DPAPI の復号に失敗した")
    }

    private companion object {
        val ENTROPY = "playlist-sync-fox/secrets/v1".encodeToByteArray()
    }
}

@Serializable
private class SecretsFile(
    val devices: Map<String, String> = emptyMap(),
    @SerialName("tls_key") val tlsKey: String? = null,
)

private const val DEVICE_PREFIX = "device/"

/** K2（サーバーの秘密鍵）の名前。TlsIdentity も同じ名前で読み書きする。 */
internal const val TLS_KEY = "tls-key"

/**
 * `secrets.bin`：K1 の表と K2 の秘密鍵を 1 つの JSON にして暗号化した 1 つのバイト列（X5-2）。
 * 読み書きは全体を復号して行い、書く・消すは直列にする。ファイルが無ければ空。
 */
class FileSecretStore(private val file: Path, private val cipher: SecretCipher) : SecretStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64 = Base64.getEncoder()

    @Synchronized
    override fun read(name: String): ByteArray? = load().let { content ->
        when {
            name == TLS_KEY -> content.tlsKey
            name.startsWith(DEVICE_PREFIX) -> content.devices[name.removePrefix(DEVICE_PREFIX)]
            else -> null
        }?.let { Base64.getDecoder().decode(it) }
    }

    @Synchronized
    override fun write(name: String, value: ByteArray) {
        save(load().replacing(name, base64.encodeToString(value)))
    }

    @Synchronized
    override fun delete(name: String) {
        val content = load()
        val next = content.replacing(name, null)
        if (next.devices != content.devices || next.tlsKey != content.tlsKey) save(next)
    }

    /** [name] の項目を [encoded] に置き換えた内容。[encoded] が null なら項目を除く。`tls-key` 以外は端末の鍵として扱う。 */
    private fun SecretsFile.replacing(name: String, encoded: String?): SecretsFile {
        if (name == TLS_KEY) return SecretsFile(devices, encoded)
        val deviceId = name.removePrefix(DEVICE_PREFIX)
        return SecretsFile(if (encoded == null) devices - deviceId else devices + (deviceId to encoded), tlsKey)
    }

    private fun load(): SecretsFile {
        if (!Files.exists(file)) return SecretsFile()
        val plain = cipher.decrypt(Files.readAllBytes(file))
        return try {
            json.decodeFromString(SecretsFile.serializer(), plain.decodeToString())
        } catch (_: SerializationException) {
            throw SecretStoreException("secrets.bin の中身を読めない")
        }
    }

    private fun save(content: SecretsFile) {
        val encrypted = cipher.encrypt(json.encodeToString(SecretsFile.serializer(), content).encodeToByteArray())
        writeAtomically(file) { it.write(encrypted) }
    }
}

/** K1：device_id から署名鍵 K を引く。起動時に端末の表から読み込む（X5-2）。 */
class KeyRing(private val store: SecretStore) {
    private val keys = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /** 鍵が読めなかった device_id。W3 に警告を出す。 */
    @Volatile
    var missing: Set<String> = emptySet()
        private set

    /** 復号の失敗は [SecretStoreException] のまま呼び出し側へ返す。 */
    fun load(deviceIds: List<String>) {
        keys.clear()
        val notFound = mutableSetOf<String>()
        for (id in deviceIds) {
            val key = store.read("$DEVICE_PREFIX$id")
            if (key == null) notFound += id else keys[id] = key
        }
        missing = notFound
    }

    fun find(deviceId: String): ByteArray? = keys[deviceId]

    /** K1 へ保存して引けるようにする。保存に失敗したら例外を投げ、引けるようにしない。 */
    fun add(deviceId: String, key: ByteArray) {
        store.write("$DEVICE_PREFIX$deviceId", key)
        keys[deviceId] = key
        missing = missing - deviceId
    }

    fun remove(deviceId: String) {
        store.delete("$DEVICE_PREFIX$deviceId")
        keys.remove(deviceId)
        missing = missing - deviceId
    }
}
