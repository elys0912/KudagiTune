package playlistsyncfox.android.ui

import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.pairing
import playlistsyncfox.android.sync.SyncValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun settingsOf(
    pairingRow: Pairing? = pairing(),
    notificationsAllowed: Boolean = true,
) = settingsUi(
    autoSetting = null,
    deleteMissingSetting = null,
    relocatedCount = 0,
    storage = storageUi(emptySet(), SyncValues.VOLUME_PRIMARY),
    pairing = pairingRow,
    notificationsAllowed = notificationsAllowed,
    logBytes = 0,
    cleanup = CleanupState.Denied,
)

class SettingsUiTest {
    @Test
    fun `TA-119 通知の許可が無いときだけ通知が出ない表示を出す`() {
        val off = settingsOf(notificationsAllowed = false)
        val on = settingsOf(notificationsAllowed = true)

        assertTrue(off.notificationsOff)
        assertFalse(on.notificationsOff)
    }

    @Test
    fun `TA-129 保存先は Music の V22 で、ペアリングは PC の名前と指紋を出す`() {
        val state = settingsOf()

        assertEquals("Music/playlist-sync-fox/", state.saveFolder)
        assertEquals("pc.example.invalid", state.pcName)
        assertEquals("0000 0000 0000 0000 0000 0000", state.fingerprint)
        assertFalse(state.autoEnabled)
    }

    @Test
    fun `TA-255 確認コードは T4 の spki_hash の先頭 12 バイトを 4 桁ごとに区切る`() {
        val hash = "0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9"

        val state = settingsOf(pairingRow = pairing().copy(spkiHash = hash))

        assertEquals("0a1b 2c3d 4e5f 6071 8293 a4b5", state.fingerprint)
    }

    @Test
    fun `TA-252 保存先を変えたとき、前の場所に曲が残っていれば先に消す案内を足し、無ければ足さない`() {
        assertEquals(STORAGE_CHANGED_NOTICE, storageChangedNotice(0))

        val withLeftover = storageChangedNotice(1)

        assertTrue(withLeftover.startsWith(STORAGE_CHANGED_NOTICE))
        assertTrue(withLeftover.contains("先に前の場所の曲を消してください"))
    }
}

class PermissionStepTest {
    @Test
    fun `X7-4-2 許可済みは要求せず、同じ起動で拒否したら再び求めず、二度拒否なら設定へ案内する`() {
        assertEquals(PermissionStep.Granted, permissionStep(granted = true, deniedThisLaunch = false, blockedByOs = false))
        assertEquals(PermissionStep.SkipDenied, permissionStep(granted = false, deniedThisLaunch = true, blockedByOs = false))
        assertEquals(PermissionStep.GuideToSettings, permissionStep(granted = false, deniedThisLaunch = false, blockedByOs = true))
        assertEquals(PermissionStep.Request, permissionStep(granted = false, deniedThisLaunch = false, blockedByOs = false))
    }
}

class StorageUiTest {
    private val primary = SyncValues.VOLUME_PRIMARY

    @Test
    fun `TA-223 本体は常に選べ、SD は本体以外の名前が返ったときだけ選べる`() {
        val without = storageUi(setOf(primary), primary)
        val with = storageUi(setOf(primary, "1234-ABCD"), primary)

        assertEquals(listOf("本体"), without.options.map { it.label })
        assertEquals(listOf("本体", "SD カード"), with.options.map { it.label })
        assertTrue(with.options.all { it.available })
    }

    @Test
    fun `TA-223 選んだ SD が抜かれていれば見つからないとして残し、本体へ替えない`() {
        val ui = storageUi(setOf(primary), "1234-ABCD")

        assertEquals("1234-ABCD", ui.selected)
        val missing = ui.options.single { it.volume == "1234-ABCD" }
        assertFalse(missing.available)
        assertEquals("SD カード（見つかりません）", missing.label)
    }
}
