package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.SigningKeyRef
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.math.abs

/** 検証の失敗。すべて 401（E2）。[detail] が null のものは `detail` を出さない。 */
enum class AuthFailure(val detail: String?) {
    ClockSkew("clock_skew"),
    Unauthorized(null),
    Replay("replay"),
}

sealed interface AuthResult {
    data object Success : AuthResult
    class Failure(val reason: AuthFailure) : AuthResult
}

/**
 * 再送の記録（X4-3-3）。PC のメモリだけで持ち、再起動で消える。
 * 記録は追加の順に並び、期間を過ぎたものと、[limit] を超えた分の古いものから消える。
 * 複数のスレッドから同時に呼べる。
 */
@OptIn(ExperimentalAtomicApi::class)
class NonceStore(private val limit: Int = AuthValues.NONCE_RECORD_LIMIT) {
    private val records = LinkedHashMap<Pair<String, String>, Long>()

    // commonMain の依存（kotlinx-serialization）に排他が無いため、標準ライブラリの atomics で作る。依存は足さない。
    // 排他の区間はメモリ上の短い操作なので、待つ側は空回りで待つ
    private val isLocked = AtomicBoolean(false)

    /** 未記録なら追加して真、すでにあれば偽。検索・掃除・追加は 1 つの排他の中で行う。 */
    fun recordIfNew(deviceId: String, nonce: String, now: Long): Boolean {
        lock()
        try {
            val key = deviceId to nonce
            if (key in records) return false

            val iterator = records.iterator()
            while (iterator.hasNext() && now - iterator.next().value > AuthValues.CLOCK_TOLERANCE_SECONDS * 2) {
                iterator.remove()
            }
            records[key] = now
            while (records.size > limit) records.remove(records.keys.first())
            return true
        } finally {
            unlock()
        }
    }

    private fun lock() {
        while (!isLocked.compareAndSet(false, true)) {
            // 他のスレッドが unlock するまで繰り返す
        }
    }

    private fun unlock() = isLocked.store(false)
}

// 未登録の端末にも署名の計算をさせ、署名の誤りと時間の差が出ないようにする
private val DUMMY_KEY = ByteArray(32)

/**
 * 署名付きの要求を検証する（X4-3-2）。ヘッダの欠けと書式の誤りは、呼び出しの前に済んでいる前提。
 *
 * @param signature 要求の `X-Psfox-Signature` の値
 * @param serverStartedAt PC の起動時刻（エポック秒）。これより前のタイムスタンプは拒否する
 * @param findKey device_id から K1 の鍵を引く。未登録なら null
 */
fun verifyRequest(
    crypto: CryptoProvider,
    request: SignedRequest,
    signature: String,
    now: Long,
    serverStartedAt: Long,
    findKey: (deviceId: String) -> ByteArray?,
    nonces: NonceStore,
): AuthResult {
    val skewed = request.timestamp < serverStartedAt || abs(now - request.timestamp) > AuthValues.CLOCK_TOLERANCE_SECONDS
    if (skewed) {
        return AuthResult.Failure(AuthFailure.ClockSkew)
    }

    val key = findKey(request.deviceId)
    val expected = sign(crypto, SigningKeyRef.KeyBytes(key ?: DUMMY_KEY), buildStringToSign(crypto, request))
    val matches = crypto.constantTimeEquals(expected.encodeToByteArray(), signature.encodeToByteArray())
    if (key == null || !matches) return AuthResult.Failure(AuthFailure.Unauthorized)

    if (!nonces.recordIfNew(request.deviceId, encodeBase64Url(request.nonce), now)) {
        return AuthResult.Failure(AuthFailure.Replay)
    }
    return AuthResult.Success
}
