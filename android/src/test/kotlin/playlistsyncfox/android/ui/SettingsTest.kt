package playlistsyncfox.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playlistsyncfox.android.DEVICE
import playlistsyncfox.android.FakeMediaStore
import playlistsyncfox.android.InMemoryStore
import playlistsyncfox.android.ROOT
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.pairing
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.android.sync.switchAutoSync
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsPresenterTest {
    private class Rig(scope: CoroutineScope) {
        val store = InMemoryStore().also { it.pairingRow = pairing() }
        val media = FakeMediaStore()
        val messages = mutableListOf<String>()
        var autoRegisters = true
        var syncing = false
        var unpaired = true

        val presenter = SettingsPresenter(
            scope = scope,
            store = store,
            setAutoEnabled = { enabled -> store.switchAutoSync(enabled) { check(autoRegisters) } },
            mountedVolumes = { emptySet() },
            storageVolume = { SyncValues.VOLUME_PRIMARY },
            logBytes = { 0L },
            cleanupWriter = { MediaWriter(store, media, DEVICE, now = { 0L }, io = Dispatchers.Unconfined) },
            syncing = { syncing },
            unpairDevice = { unpaired },
            showMessage = { messages += it },
        )
    }

    private fun TestScope.openedRig(): Rig =
        Rig(backgroundScope).also {
            it.presenter.refresh(notificationsAllowed = true, ReadAccess.Denied)
            runCurrent()
        }

    @Test
    fun `TA-267 読み取りの権限の状態で、以前の曲の整理は一覧・許可の要求・拒否のどれかになる`() = runTest {
        val rig = Rig(backgroundScope)
        rig.media.addExisting("${ROOT}Artist/", "Old Song.mp3")

        rig.presenter.refresh(notificationsAllowed = true, ReadAccess.Granted)
        runCurrent()
        val found = assertIs<CleanupState.Found>(rig.presenter.state.value?.cleanup)
        rig.presenter.refresh(notificationsAllowed = true, ReadAccess.Requestable)
        runCurrent()
        val requestable = rig.presenter.state.value?.cleanup
        rig.presenter.refresh(notificationsAllowed = true, ReadAccess.Denied)
        runCurrent()
        val denied = rig.presenter.state.value?.cleanup

        assertEquals(listOf("Old Song.mp3"), found.items.map { it.displayName })
        assertEquals(CleanupState.NeedsPermission, requestable)
        assertEquals(CleanupState.Denied, denied)
    }

    @Test
    fun `TA-129 自動同期の切り替えが成功したら知らせを出さず、表示が変わる`() = runTest {
        val rig = openedRig()

        rig.presenter.toggleAuto(true)
        runCurrent()

        assertTrue(rig.messages.isEmpty())
        assertEquals(true, rig.presenter.state.value?.autoEnabled)
    }

    @Test
    fun `TA-129 自動同期の切り替えが失敗したら知らせを出し、表示は元の値のまま`() = runTest {
        val rig = openedRig().also { it.autoRegisters = false }

        rig.presenter.toggleAuto(true)
        runCurrent()

        assertEquals(listOf(AUTO_SYNC_FAILED_NOTICE), rig.messages)
        assertEquals(false, rig.presenter.state.value?.autoEnabled)
    }

    @Test
    fun `TA-219 以前の場所の曲を消す操作は、同期中は消さずに知らせ、済んだ後は消した件数を知らせて残りを数え直す`() = runTest {
        val rig = Rig(backgroundScope)
        rig.store.relocatedRows += listOf(rig.media.addExisting(ROOT, "A.mp3"), rig.media.addExisting(ROOT, "B.mp3"))
        rig.presenter.refresh(notificationsAllowed = true, ReadAccess.Denied)
        runCurrent()

        rig.syncing = true
        rig.presenter.deleteRelocated()
        runCurrent()
        assertEquals(listOf(SYNCING_NO_DELETE_NOTICE), rig.messages)
        assertEquals(2, rig.presenter.state.value?.relocatedCount)

        rig.syncing = false
        rig.presenter.deleteRelocated()
        runCurrent()
        assertEquals(listOf(SYNCING_NO_DELETE_NOTICE, relocatedDeletedNotice(2)), rig.messages)
        assertEquals(0, rig.presenter.state.value?.relocatedCount)
    }

    @Test
    fun `TA-129 解除に失敗したら知らせを出して偽を返し、成功なら知らせずに真を返す`() = runTest {
        val rig = Rig(backgroundScope)

        rig.unpaired = false
        assertFalse(rig.presenter.unpair())
        assertEquals(listOf(UNPAIR_FAILED_NOTICE), rig.messages)

        rig.unpaired = true
        assertTrue(rig.presenter.unpair())
        assertEquals(1, rig.messages.size)
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
