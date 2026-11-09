package playlistsyncfox.android.sync

import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.db.PlacedTrack
import playlistsyncfox.android.db.WrittenPlaylist
import playlistsyncfox.android.pairing
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeVault : KeyVault {
    val aliases = mutableMapOf<String, ByteArray>()
    var failImport = false
    var failDelete = false

    override fun exists(alias: String) = alias in aliases

    override fun import(alias: String, key: ByteArray): Boolean {
        if (failImport) return false
        aliases[alias] = key.copyOf()
        return true
    }

    override fun delete(alias: String): Boolean {
        if (failDelete) return false
        aliases.remove(alias)
        return true
    }
}

class AutoSwitchTest {
    @Test
    fun `TA-129 自動起動を切り替えると T7 が更新され、登録が行われる`() = runTest {
        val store = InMemoryStore()
        var registered = 0

        val on = store.switchAutoSync(true) { registered++ }
        val off = store.switchAutoSync(false) { registered++ }

        assertTrue(on && off)
        assertEquals(2, registered)
        assertEquals("false", store.settings[SyncScheduler.AUTO_ENABLED_KEY])
    }

    @Test
    fun `TA-129 入れるときの登録が失敗すると T7 を元に戻して偽を返す`() = runTest {
        val store = InMemoryStore().also { it.settings[SyncScheduler.AUTO_ENABLED_KEY] = "false" }

        val ok = store.switchAutoSync(true) { error("登録できない") }

        assertFalse(ok)
        assertEquals("false", store.settings[SyncScheduler.AUTO_ENABLED_KEY])
    }
}

class PairingKeeperTest {
    private val draft = PairingDraft(DEVICE, "0123456789abcdef", "pc.example.invalid", SavedEndpoint("tailscale", "pc.example.invalid:47800"), "ab".repeat(32))

    @Test
    fun `TA-132 再ペアリングで T4 を作り直すと paired_at は P6 の成功の時刻になり、鍵は取り込み後に捨てる`() = runTest {
        val store = InMemoryStore().also { it.pairingRow = pairing(pairedAt = 100) }
        val vault = FakeVault()
        val key = ByteArray(32) { 7 }
        var now = 5_000L
        val keeper = PairingKeeper(store, vault, now = { now })

        store.deletePairing(DEVICE)
        now = 9_000L
        assertTrue(keeper.save(draft, key))

        val row = assertNotNull(store.pairingRow)
        assertEquals(9_000L, row.pairedAt)
        assertEquals(keyAliasOf(DEVICE), row.keyAlias)
        assertEquals("pc.example.invalid:47800", row.endpointTailscale)
        assertEquals("tailscale", row.lastEndpoint)
        assertNull(row.endpointLan)
        assertContentEquals(ByteArray(32) { 7 }, vault.aliases.getValue(row.keyAlias))
        assertContentEquals(ByteArray(32), key)
    }

    @Test
    fun `TA-132 鍵の取り込みが失敗したら T4 を作らない`() = runTest {
        val store = InMemoryStore()
        val keeper = PairingKeeper(store, FakeVault().also { it.failImport = true }, now = { 1 })

        assertFalse(keeper.save(draft, ByteArray(32)))
        assertNull(store.pairingRow)
    }

    @Test
    fun `TA-129 解除は別名を消してから T4 の行を消し、T1 と T2 は残す`() = runTest {
        val store = InMemoryStore().also {
            it.pairingRow = pairing()
            it.placed["A"] = PlacedTrack(DEVICE, "A", "a/b.mp3", "content://x", 1, 1, 1, PlacedTrack.STATE_PLACED, 0)
            it.playlistRows["P"] = WrittenPlaylist(DEVICE, "P", "Mix", "Mix.m3u8", "content://y", 1, 0)
        }
        val vault = FakeVault().also { it.aliases[store.pairingRow!!.keyAlias] = ByteArray(32) }

        assertTrue(PairingKeeper(store, vault, now = { 1 }).unpair())

        assertTrue(vault.aliases.isEmpty())
        assertNull(store.pairingRow)
        assertEquals(1, store.placed.size)
        assertEquals(1, store.playlistRows.size)
    }

    @Test
    fun `TA-129 別名の削除に失敗すると T4 の行が残り、解除は済んでいない`() = runTest {
        val store = InMemoryStore().also { it.pairingRow = pairing() }
        val vault = FakeVault().also { it.failDelete = true }

        assertFalse(PairingKeeper(store, vault, now = { 1 }).unpair())

        assertNotNull(store.pairingRow)
    }
}
