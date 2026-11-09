package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.EcPoint

// PC 側（B）のペアリング。状態の値は M3 が持ち、ここは「状態と入力から、次の状態と結果」を返す純粋な関数（X4-2-1）。
// 時刻はエポック秒で引数から受ける。

private const val SCALAR_BYTES = 32

/** H0〜H2。H3（検証中）は 1 回の呼び出しの中で終わるため値を持たない。 */
sealed interface PairingState {
    /** H0 閉。[lockedUntil] より前は V4 の待ちで、コードを作れない。 */
    class Closed(val lockedUntil: Long? = null) : PairingState {
        /** [now] が [lockedUntil] より前、つまり V4 の待ちの間なら真。 */
        fun isLockedAt(now: Long): Boolean = lockedUntil != null && now < lockedUntil
    }

    /** H1 開（コード表示中）。[openedAt] は H1 に入った時刻、[failedAttempts] は MAC_d の不一致の回数。 */
    class Open(val code: String, val openedAt: Long, val failedAttempts: Int) : PairingState {
        override fun toString() = "PairingState.Open"
    }

    /** H2 P5 済。 */
    class Started internal constructor(
        internal val open: Open,
        internal val deviceId: String,
        internal val keys: PakeKeys,
    ) : PairingState {
        override fun toString() = "PairingState.Started"
    }
}

/** ペアリングの失敗。[status] と [detail] は B4-4 の 8 の表（detail は E2 の `detail`）。 */
enum class PairingFailure(val status: Int, val detail: String?) {
    Closed(403, "pairing_closed"),
    Locked(403, "pairing_locked"),
    DeviceExists(403, "device_exists"),
    BadRequest(400, null),
    MacMismatch(401, null),
    SaveFailed(500, null),
}

sealed interface PairingOutcome<out T> {
    class Ok<T>(val value: T) : PairingOutcome<T>
    class Failed(val failure: PairingFailure) : PairingOutcome<Nothing>
}

/** 次の状態と結果。失敗でも状態は変わりうる（期限切れ、試行の上限）。 */
class PairingStep<out T>(val state: PairingState, val outcome: PairingOutcome<T>)

/** コードを作って H1 に入る。V4 の待ちの間は [PairingFailure.Locked]。 */
fun openPairing(crypto: CryptoProvider, state: PairingState, now: Long): PairingStep<String> {
    if (state is PairingState.Closed && state.isLockedAt(now)) {
        return PairingStep(state, PairingOutcome.Failed(PairingFailure.Locked))
    }
    val code = generatePairingCode(crypto)
        ?: return PairingStep(state, PairingOutcome.Failed(PairingFailure.Closed))
    return PairingStep(PairingState.Open(code, now, 0), PairingOutcome.Ok(code))
}

/**
 * P5 を受ける。応答の Y を返す。
 * H2 のあいだに別の P5 を受けたら、先の途中状態を破棄して新しい P5 を処理する。P5 の失敗は試行に数えない。
 *
 * @param registeredDeviceCount K1 に登録済みの端末の数
 * @param spkiHash PC 自身の証明書の SPKI の SHA-256（32 バイト）
 */
fun handlePairingStart(
    crypto: CryptoProvider,
    state: PairingState,
    deviceId: String,
    x: ByteArray,
    registeredDeviceCount: Int,
    pcId: String,
    spkiHash: ByteArray,
    now: Long,
): PairingStep<EcPoint> {
    val current = expireIfDue(state, now)
    val open = openOf(current)
        ?: return PairingStep(current, PairingOutcome.Failed(closedFailure(current, now)))
    if (registeredDeviceCount >= 1) {
        return PairingStep(current, PairingOutcome.Failed(PairingFailure.DeviceExists))
    }
    val pA = crypto.decodePoint(x)
        ?: return PairingStep(current, PairingOutcome.Failed(PairingFailure.BadRequest))

    val w = passwordScalar(crypto, open.code)
    val y = crypto.randomBytes(SCALAR_BYTES)
    val pB = responderShare(crypto, y, w)
    val k = responderSharedPoint(crypto, y, w, pA)
    val tt = transcript(deviceId.encodeToByteArray(), pcId.encodeToByteArray(), pA, pB, k, w)
    val keys = deriveKeys(crypto, tt, associatedData(deviceId, pcId, spkiHash))
    return PairingStep(PairingState.Started(open, deviceId, keys), PairingOutcome.Ok(pB))
}

/**
 * P6 を受ける。MAC_d が合えば [saveKey]（K1 と settings.json への保存。成功なら真）を呼び、MAC_p を返す。
 * 保存に失敗したら成功を返さない。MAC_d の不一致だけが試行に数えられ、[AuthValues.MAX_ATTEMPTS] 回目で H0 に戻って V4 の待ちに入る。
 */
fun handlePairingConfirm(
    crypto: CryptoProvider,
    state: PairingState,
    deviceId: String,
    macD: ByteArray,
    now: Long,
    saveKey: (deviceId: String, key: ByteArray) -> Boolean,
): PairingStep<ByteArray> {
    val current = expireIfDue(state, now)
    if (current !is PairingState.Started || current.deviceId != deviceId) {
        val failure = if (current is PairingState.Closed) closedFailure(current, now) else PairingFailure.BadRequest
        return PairingStep(current, PairingOutcome.Failed(failure))
    }

    val open = current.open
    val keys = current.keys
    if (!crypto.constantTimeEquals(crypto.hmacSha256(keys.kcA, keys.transcript), macD)) {
        val attempts = open.failedAttempts + 1
        val next = if (attempts >= AuthValues.MAX_ATTEMPTS) {
            PairingState.Closed(lockedUntil = now + AuthValues.LOCK_SECONDS)
        } else {
            PairingState.Open(open.code, open.openedAt, attempts)
        }
        return PairingStep(next, PairingOutcome.Failed(PairingFailure.MacMismatch))
    }

    if (!saveKey(deviceId, signingKey(crypto, keys))) {
        return PairingStep(open, PairingOutcome.Failed(PairingFailure.SaveFailed))
    }
    return PairingStep(PairingState.Closed(), PairingOutcome.Ok(crypto.hmacSha256(keys.kcB, keys.transcript)))
}

/** V3 ちょうどは無効。 */
private fun expireIfDue(state: PairingState, now: Long): PairingState {
    val open = openOf(state) ?: return state
    return if (now - open.openedAt >= AuthValues.CODE_VALID_SECONDS) PairingState.Closed() else state
}

private fun openOf(state: PairingState): PairingState.Open? = when (state) {
    is PairingState.Open -> state
    is PairingState.Started -> state.open
    is PairingState.Closed -> null
}

private fun closedFailure(state: PairingState, now: Long): PairingFailure =
    if (state is PairingState.Closed && state.isLockedAt(now)) {
        PairingFailure.Locked
    } else {
        PairingFailure.Closed
    }
