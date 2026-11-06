package playlistsyncfox.shared.crypto

/**
 * 暗号の部品。実体は PC 側と端末側が渡し、shared は実体を持たない。
 * ここでは同期の部分（content_digest）が使う SHA-256 だけを定義する。認証の部品は束 3 で足す。
 */
interface CryptoProvider {
    fun sha256(input: ByteArray): ByteArray
}
