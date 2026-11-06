package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.TestCrypto

const val DEVICE_ID = "00000000-0000-4000-8000-000000000001"
const val PC_ID = "00000000-0000-4000-8000-0000000000a1"
const val CODE = "123456"
val SPKI_HASH = ByteArray(32) { it.toByte() }

/** 先頭が小さい値で、位数より小さいスカラー。 */
fun scalar(seed: Int): ByteArray = ByteArray(32) { (seed + it).toByte() }

/** 4 バイトのビッグエンディアンの乱数。 */
fun draw(value: Long): ByteArray = ByteArray(4) { (value shr (8 * (3 - it))).toByte() }

/** コードが [CODE] になる乱数。 */
val CODE_DRAW = draw(CODE.toLong())

fun PairingStep<*>.failure(): PairingFailure = (outcome as PairingOutcome.Failed).failure

fun <T> PairingStep<T>.value(): T {
    @Suppress("UNCHECKED_CAST")
    return (outcome as PairingOutcome.Ok<T>).value
}

/** PC 側の暗号の実体（コード、y の順に乱数を引く）と、[now] に H1 に入った状態。 */
fun openHost(now: Long = 0, vararg ys: ByteArray): Pair<TestCrypto, PairingState> {
    val crypto = TestCrypto(CODE_DRAW, *ys)
    return crypto to openPairing(crypto, PairingState.Closed(), now).state
}

/** 端末の P5（X）を作る。 */
fun deviceStart(x: ByteArray = scalar(1), code: String = CODE): PairingInitiation =
    startPairing(TestCrypto(x), DEVICE_ID, code)

/** PC に P5 を送る。 */
fun PairingState.sendStart(crypto: TestCrypto, initiation: PairingInitiation, now: Long = 0, deviceCount: Int = 0) =
    handlePairingStart(crypto, this, DEVICE_ID, initiation.x.encoded, deviceCount, PC_ID, SPKI_HASH, now)
