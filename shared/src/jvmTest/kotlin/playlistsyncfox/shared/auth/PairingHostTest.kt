package playlistsyncfox.shared.auth

import playlistsyncfox.shared.crypto.EcPoint
import playlistsyncfox.shared.crypto.TestCrypto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class PairingHostTest {
    private val clientCrypto = TestCrypto()
    private val openedAt = 1_000L

    /** 端末の MAC_d を作る。 */
    private fun macDFor(initiation: PairingInitiation, step: PairingStep<EcPoint>): ByteArray =
        assertNotNull(confirmPairing(clientCrypto, initiation, PC_ID, step.value().encoded, SPKI_HASH)).macD

    /** P5 を済ませた H2 と、正しい MAC_d。 */
    private fun started(vararg ys: ByteArray, now: Long = openedAt): Triple<TestCrypto, PairingStep<EcPoint>, ByteArray> {
        val (crypto, host) = openHost(now, *ys)
        val initiation = deviceStart()
        val step = host.sendStart(crypto, initiation, now)
        return Triple(crypto, step, macDFor(initiation, step))
    }

    private fun lockedState(): Pair<TestCrypto, PairingState> {
        val ys = Array(AuthValues.MAX_ATTEMPTS) { scalar(0x40 + it) }
        val (crypto, first) = openHost(openedAt, *ys)
        var state = first
        repeat(AuthValues.MAX_ATTEMPTS) {
            val started = state.sendStart(crypto, deviceStart(), openedAt)
            state = handlePairingConfirm(crypto, started.state, DEVICE_ID, ByteArray(32), openedAt) { _, _ -> true }.state
        }
        return crypto to state
    }

    @Test
    fun `TS-108 P5 の失敗を繰り返しても試行は増えない`() {
        val (crypto, host) = openHost(openedAt)
        val offCurve = ByteArray(65) { 1 }.also { it[0] = 4 }
        var state = host

        repeat(AuthValues.MAX_ATTEMPTS * 2) {
            state = handlePairingStart(crypto, state, DEVICE_ID, offCurve, 0, PC_ID, SPKI_HASH, openedAt).state
            state = handlePairingStart(crypto, state, DEVICE_ID, deviceStart().x.encoded, 1, PC_ID, SPKI_HASH, openedAt).state
        }

        val open = assertIs<PairingState.Open>(state)
        assertEquals(0, open.failedAttempts)
    }

    @Test
    fun `TS-109 P6 の不一致は 1 回ずつ数え V2 回目でコードが無効になる`() {
        val ys = Array(AuthValues.MAX_ATTEMPTS) { scalar(0x40 + it) }
        val (crypto, first) = openHost(openedAt, *ys)
        var state = first

        repeat(AuthValues.MAX_ATTEMPTS - 1) { count ->
            val started = state.sendStart(crypto, deviceStart(), openedAt)
            val step = handlePairingConfirm(crypto, started.state, DEVICE_ID, ByteArray(32), openedAt) { _, _ -> true }
            state = step.state
            assertEquals(PairingFailure.MacMismatch, step.failure())
            assertEquals(count + 1, assertIs<PairingState.Open>(state).failedAttempts)
        }

        val started = state.sendStart(crypto, deviceStart(), openedAt)
        val last = handlePairingConfirm(crypto, started.state, DEVICE_ID, ByteArray(32), openedAt) { _, _ -> true }
        assertEquals(PairingFailure.MacMismatch, last.failure())
        assertIs<PairingState.Closed>(last.state)
    }

    @Test
    fun `TS-110 コードの有効時間の 1 秒前の P5 は通る`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40))

        val step = host.sendStart(crypto, deviceStart(), openedAt + AuthValues.CODE_VALID_SECONDS - 1)

        assertIs<PairingOutcome.Ok<*>>(step.outcome)
    }

    @Test
    fun `TS-111 コードの有効時間ちょうどの P5 は pairing_closed で拒否される`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40))

        val failure = host.sendStart(crypto, deviceStart(), openedAt + AuthValues.CODE_VALID_SECONDS).failure()

        assertEquals(PairingFailure.Closed, failure)
        assertEquals(403, failure.status)
        assertEquals("pairing_closed", failure.detail)
    }

    @Test
    fun `TS-111 コードの有効時間を 1 秒過ぎた P5 は pairing_closed で拒否される`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40))

        val failure = host.sendStart(crypto, deviceStart(), openedAt + AuthValues.CODE_VALID_SECONDS + 1).failure()

        assertEquals(PairingFailure.Closed, failure)
    }

    @Test
    fun `TS-137 V2 回の失敗の後は V4 の直前までコードを作れない`() {
        val (_, locked) = lockedState()
        val crypto = TestCrypto(CODE_DRAW)

        val step = openPairing(crypto, locked, openedAt + AuthValues.LOCK_SECONDS - 1)

        assertEquals(PairingFailure.Locked, step.failure())
    }

    @Test
    fun `TS-137 V4 の後はコードを作り直せる`() {
        val (_, locked) = lockedState()
        val crypto = TestCrypto(CODE_DRAW)

        val step = openPairing(crypto, locked, openedAt + AuthValues.LOCK_SECONDS)

        assertEquals(CODE, step.value())
    }

    @Test
    fun `TS-152 V4 の間は P5 も P6 も pairing_locked で拒否される`() {
        val (crypto, locked) = lockedState()
        val now = openedAt + AuthValues.LOCK_SECONDS - 1

        val p5 = locked.sendStart(crypto, deviceStart(), now).failure()
        val p6 = handlePairingConfirm(crypto, locked, DEVICE_ID, ByteArray(32), now) { _, _ -> true }.failure()

        assertEquals("pairing_locked", p5.detail)
        assertEquals("pairing_locked", p6.detail)
        assertEquals(403, p5.status)
    }

    @Test
    fun `TS-152 V4 の後の P5 も P6 も pairing_closed で拒否される`() {
        val (crypto, locked) = lockedState()
        val now = openedAt + AuthValues.LOCK_SECONDS

        val p5 = locked.sendStart(crypto, deviceStart(), now).failure()
        val p6 = handlePairingConfirm(crypto, locked, DEVICE_ID, ByteArray(32), now) { _, _ -> true }.failure()

        assertEquals("pairing_closed", p5.detail)
        assertEquals("pairing_closed", p6.detail)
    }

    @Test
    fun `TS-139 登録済みの端末が 1 件でもあれば別の device_id でも device_exists で拒否される`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40))

        val failure = host.sendStart(crypto, deviceStart(), openedAt, deviceCount = 1).failure()

        assertEquals(PairingFailure.DeviceExists, failure)
        assertEquals("device_exists", failure.detail)
    }

    @Test
    fun `TS-139 登録済みの端末が 0 件なら P5 は進む`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40))

        val step = host.sendStart(crypto, deviceStart(), openedAt, deviceCount = 0)

        assertIs<PairingOutcome.Ok<*>>(step.outcome)
    }

    @Test
    fun `TS-140 MAC_d が合っても鍵の保存に失敗すれば成功を返さず 500 を返す`() {
        val (crypto, step, macD) = started(scalar(0x40))

        val confirmed = handlePairingConfirm(crypto, step.state, DEVICE_ID, macD, openedAt) { _, _ -> false }

        assertEquals(PairingFailure.SaveFailed, confirmed.failure())
        assertEquals(500, confirmed.failure().status)
        assertIs<PairingState.Open>(confirmed.state)
    }

    @Test
    fun `TS-141 後の P5 を受けると先の P5 の MAC_d は通らない`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40), scalar(0x50))
        val first = deviceStart(scalar(1))
        val firstStep = host.sendStart(crypto, first, openedAt)
        val firstMacD = macDFor(first, firstStep)
        val secondStep = firstStep.state.sendStart(crypto, deviceStart(scalar(2)), openedAt)

        val confirmed = handlePairingConfirm(crypto, secondStep.state, DEVICE_ID, firstMacD, openedAt) { _, _ -> true }

        assertEquals(PairingFailure.MacMismatch, confirmed.failure())
    }

    @Test
    fun `TS-141 後の P5 を受けると後の P5 の MAC_d で進める`() {
        val (crypto, host) = openHost(openedAt, scalar(0x40), scalar(0x50))
        val firstStep = host.sendStart(crypto, deviceStart(scalar(1)), openedAt)
        val second = deviceStart(scalar(2))
        val secondStep = firstStep.state.sendStart(crypto, second, openedAt)

        val confirmed = handlePairingConfirm(crypto, secondStep.state, DEVICE_ID, macDFor(second, secondStep), openedAt) { _, _ -> true }

        assertIs<PairingOutcome.Ok<*>>(confirmed.outcome)
    }

    @Test
    fun `TS-154 H2 でコードの有効時間ちょうどに届いた P6 は MAC_d が合っていても拒否され登録されない`() {
        val (crypto, step, macD) = started(scalar(0x40))
        var saved = false

        val confirmed = handlePairingConfirm(crypto, step.state, DEVICE_ID, macD, openedAt + AuthValues.CODE_VALID_SECONDS) { _, _ ->
            saved = true
            true
        }

        assertEquals(PairingFailure.Closed, confirmed.failure())
        assertEquals("pairing_closed", confirmed.failure().detail)
        assertFalse(saved)
    }

    @Test
    fun `TS-156 P6 が成功した後の P5 は pairing_closed で拒否される`() {
        val (crypto, step, macD) = started(scalar(0x40))
        val done = handlePairingConfirm(crypto, step.state, DEVICE_ID, macD, openedAt) { _, _ -> true }

        val failure = done.state.sendStart(crypto, deviceStart(scalar(2)), openedAt + 1).failure()

        assertEquals("pairing_closed", failure.detail)
    }

    @Test
    fun `TS-156 P6 が成功した後の P6 は pairing_closed で拒否され 2 台目は登録されない`() {
        val (crypto, step, macD) = started(scalar(0x40))
        val done = handlePairingConfirm(crypto, step.state, DEVICE_ID, macD, openedAt) { _, _ -> true }
        var saveCount = 0

        val again = handlePairingConfirm(crypto, done.state, DEVICE_ID, macD, openedAt + 1) { _, _ ->
            saveCount++
            true
        }

        assertEquals("pairing_closed", again.failure().detail)
        assertEquals(0, saveCount)
    }

    @Test
    fun `TS-156 P5 を受けていない P6 は書式の誤りで試行に数えない`() {
        val (crypto, host) = openHost(openedAt)

        val step = handlePairingConfirm(crypto, host, DEVICE_ID, ByteArray(32), openedAt) { _, _ -> true }

        assertEquals(PairingFailure.BadRequest, step.failure())
        assertEquals(0, assertIs<PairingState.Open>(step.state).failedAttempts)
    }
}
