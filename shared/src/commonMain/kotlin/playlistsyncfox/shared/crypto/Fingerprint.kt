package playlistsyncfox.shared.crypto

/** 指紋の元にするバイト数（B4-2 の 3）。 */
private const val FINGERPRINT_BYTES = 12

/** SPKI の DER から、画面に出す指紋を作る。例：`0a1b 2c3d 4e5f 6071 8293 a4b5`。 */
fun formatFingerprint(crypto: CryptoProvider, spkiDer: ByteArray): String =
    formatFingerprintOfHash(crypto.sha256(spkiDer))

/** SPKI の DER の SHA-256 の値から、画面に出す指紋を作る。値の先頭 12 バイトだけを使う。 */
fun formatFingerprintOfHash(spkiSha256: ByteArray): String =
    spkiSha256.copyOf(FINGERPRINT_BYTES).toHexString().chunked(4).joinToString(" ")
