package playlistsyncfox.desktop

import playlistsyncfox.desktop.vault.SecretCipher
import playlistsyncfox.desktop.vault.SecretStore
import playlistsyncfox.desktop.vault.SecretStoreException
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.ReadDiagnostics
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 試験が進める時計。 */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?) = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now += duration
    }
}

val T0: Instant = Instant.parse("2026-01-01T00:00:00Z")

/** 記憶の中の `SecretStore`。 */
class MemorySecretStore : SecretStore {
    val values = HashMap<String, ByteArray>()

    override fun read(name: String): ByteArray? = values[name]

    override fun write(name: String, value: ByteArray) {
        values[name] = value
    }

    override fun delete(name: String) {
        values.remove(name)
    }
}

/** 読むたびに復号に失敗する `SecretStore`（別の利用者の暗号化、破損）。 */
class BrokenSecretStore : SecretStore {
    override fun read(name: String): ByteArray? = throw SecretStoreException("復号に失敗")

    override fun write(name: String, value: ByteArray) = throw SecretStoreException("復号に失敗")

    override fun delete(name: String) = throw SecretStoreException("復号に失敗")
}

/** 平文が見えないよう印を付けて変換する暗号器。先頭の印が無い入力は復号に失敗する。 */
class MarkingCipher : SecretCipher {
    private val mark = "ENC1".encodeToByteArray()

    override fun encrypt(plain: ByteArray): ByteArray = mark + ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        if (encrypted.size < mark.size || !encrypted.copyOf(mark.size).contentEquals(mark)) throw SecretStoreException("復号に失敗")
        return ByteArray(encrypted.size - mark.size) { (encrypted[it + mark.size].toInt() xor 0x5A).toByte() }
    }
}

fun emptyLibrary(): LibraryReadResult =
    LibraryReadResult(null, emptyList(), emptyList(), emptyList(), ReadDiagnostics(0, 0, 0, 0, 0, emptyMap(), null, null))

const val EMPTY_LIBRARY_XML =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?><plist version=\"1.0\"><dict><key>Tracks</key><dict></dict>" +
        "<key>Playlists</key><array></array></dict></plist>"
