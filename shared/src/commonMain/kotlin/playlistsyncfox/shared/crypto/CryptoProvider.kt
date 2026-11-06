package playlistsyncfox.shared.crypto

/**
 * 暗号の部品（X4-1）。実体は PC 側と端末側が渡し、shared は実体を持たない。
 * 失敗は例外にせず、戻り値で返す。
 */
interface CryptoProvider {
    /** OS の乱数源から [count] バイトを得る。 */
    fun randomBytes(count: Int): ByteArray

    fun sha256(input: ByteArray): ByteArray

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray

    /** 鍵の持ち方（K1 の値、Keystore の別名）は実体が吸収する。 */
    fun hmacSha256(key: SigningKeyRef, message: ByteArray): ByteArray

    /** [salt] が空なら塩なし。[info] は文字列でなくバイト列（付加データを続けるため）。 */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray

    /** 秘密の比較に使う。長さが違えば偽。 */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean

    /**
     * 基点のスカラー倍。スカラーは 32 バイトで、群の位数で割った余りに直すのは実体の側。
     * 点演算（以下 4 つ）はいずれも P-256 の曲線上の点を扱う。
     */
    fun multiplyBase(scalar: ByteArray): EcPoint

    fun multiply(point: EcPoint, scalar: ByteArray): EcPoint

    fun add(a: EcPoint, b: EcPoint): EcPoint

    fun negate(point: EcPoint): EcPoint

    /**
     * 非圧縮形式（先頭 0x04 の 65 バイト）の点を復号する。
     * 形式が違う、曲線上にない、無限遠点のいずれも null。
     */
    fun decodePoint(encoded: ByteArray): EcPoint?
}

/** P-256 の点。[encoded] は演算の結果を含めて実体が決める符号化で、M1 は中身を解釈しない。 */
class EcPoint(val encoded: ByteArray)

/** 署名鍵の参照。値は秘密のため、文字列にしても中身を出さない。 */
sealed class SigningKeyRef {
    /** K1 から読んだ鍵そのもの（PC 側）。 */
    class KeyBytes(val key: ByteArray) : SigningKeyRef() {
        override fun toString() = "SigningKeyRef.KeyBytes"
    }

    /** Keystore の別名（端末側）。 */
    class KeystoreAlias(val alias: String) : SigningKeyRef() {
        override fun toString() = "SigningKeyRef.KeystoreAlias"
    }
}
