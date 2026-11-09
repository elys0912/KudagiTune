package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.MemorySecretStore
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.crypto.PcCrypto
import playlistsyncfox.desktop.settings.SettingsStore
import playlistsyncfox.desktop.vault.KeyRing
import playlistsyncfox.shared.auth.AuthValues
import playlistsyncfox.shared.auth.PairingOutcome
import playlistsyncfox.shared.auth.startPairing
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PairingService が共有の状態から作る画面の状態（W4 に写す [PairingView]）と、その変化の通知。 */
class PairingServiceTest {
    private val clock = MutableClock(T0)
    private val settings = SettingsStore(createTempDirectory("psfox-pairing-view").resolve("settings.json")) { PC_ID }.also { it.load() }
    private val registry = DeviceRegistry(KeyRing(MemorySecretStore()), settings, clock)
    private var notices = 0
    private val service = PairingService(PcCrypto(), clock, PC_ID, SPKI_HASH, settings, registry, onViewChanged = { notices++ })

    private fun openCode(): String = (service.view.value as PairingView.Open).code

    // 端末の手順で P5 を受けさせる。受けると共有の状態は途中（Started）になる
    private fun receiveStart(code: String) {
        val init = startPairing(PcCrypto(), DEVICE, code)
        check(service.start(DEVICE, "端末", init.x.encoded) is PairingOutcome.Ok)
    }

    // P5 の後に MAC_d の違う P6 を受けさせる。試行が 1 回数えられ、開に戻る（V2 回目は閉になって V4 の待ちに入る）
    private fun failOnce(code: String) {
        receiveStart(code)
        check(service.confirm(DEVICE, ByteArray(32)) is PairingOutcome.Failed)
    }

    @Test
    fun `コードは V3 の 1 秒前まで開のままで、V3 ちょうどに閉になって通知される`() {
        service.open()
        val open = service.view.value as PairingView.Open
        assertEquals(T0.plusSeconds(AuthValues.CODE_VALID_SECONDS), open.expiresAt)
        notices = 0

        clock.advance(Duration.ofSeconds(AuthValues.CODE_VALID_SECONDS - 1))
        service.refresh()
        assertEquals(open, service.view.value)
        assertEquals(0, notices)

        clock.advance(Duration.ofSeconds(1))
        service.refresh()
        assertEquals(PairingView.Closed, service.view.value)
        assertEquals(1, notices)
    }

    @Test
    fun `試行が V2 回失敗すると V4 の間は待ちの状態で、V4 ちょうどに閉に戻る`() {
        service.open()
        val code = openCode()

        repeat(AuthValues.MAX_ATTEMPTS) { failOnce(code) }
        assertEquals(PairingView.Locked(T0.plusSeconds(AuthValues.LOCK_SECONDS)), service.view.value)

        clock.advance(Duration.ofSeconds(AuthValues.LOCK_SECONDS - 1))
        service.refresh()
        assertTrue(service.view.value is PairingView.Locked)

        clock.advance(Duration.ofSeconds(1))
        service.refresh()
        assertEquals(PairingView.Closed, service.view.value)
    }

    @Test
    fun `P5 を受けて途中の間は、直前の開のコードと失敗回数を保ち、通知しない`() {
        service.open()
        val code = openCode()
        failOnce(code)
        val before = service.view.value
        assertEquals(1, (before as PairingView.Open).failedAttempts)
        notices = 0

        receiveStart(code)

        assertEquals(before, service.view.value)
        assertEquals(0, notices)
    }

    @Test
    fun `途中の間も、開いてから V3 が経てば閉になる`() {
        service.open()
        receiveStart(openCode())

        clock.advance(Duration.ofSeconds(AuthValues.CODE_VALID_SECONDS))
        service.refresh()

        assertEquals(PairingView.Closed, service.view.value)
    }
}
