package playlistsyncfox.android.vault

import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import playlistsyncfox.android.sync.KeyVault
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

private const val PROVIDER = "AndroidKeyStore"
private const val HMAC = "HmacSHA256"

/**
 * K3（署名用の共有の鍵）を Android Keystore に置く実体（X6-6）。取り込んだ鍵は取り出せず、署名だけに使える。
 * 実際の動作は JVM で試せないので、実機で確かめる（C-12）。鍵、別名の中身は例外の文にもログにも入れない。
 */
class DeviceVault : KeyVault {
    private fun store(): KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    override fun exists(alias: String): Boolean = try {
        store().containsAlias(alias)
    } catch (_: Exception) {
        false
    }

    /** 取り込んだ後に署名を 1 回試し、使えなければ消して偽を返す。 */
    override fun import(alias: String, key: ByteArray): Boolean = try {
        val protection = KeyProtection.Builder(KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        store().setEntry(alias, KeyStore.SecretKeyEntry(SecretKeySpec(key, HMAC)), protection)
        hmac(alias, ByteArray(0))
        true
    } catch (_: Exception) {
        delete(alias)
        false
    }

    override fun delete(alias: String): Boolean = try {
        val keystore = store()
        keystore.deleteEntry(alias)
        !keystore.containsAlias(alias)
    } catch (_: Exception) {
        false
    }

    /** 別名の鍵で HMAC-SHA256 を得る。別名が無い、鍵が使えないときは例外（呼び出し側が鍵の不一致として扱う）。 */
    fun hmac(alias: String, message: ByteArray): ByteArray {
        val key = store().getKey(alias, null) as? SecretKey ?: throw GeneralSecurityException("鍵が無い")
        return Mac.getInstance(HMAC).apply { init(key) }.doFinal(message)
    }
}
