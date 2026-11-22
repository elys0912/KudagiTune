package playlistsyncfox.android.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.sync.SyncScheduler
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.crypto.formatFingerprintOfHash

// S5 設定（X7-1-6）。

/** 「以前の曲の整理」。権限が拒否されたら項目を閉じる。同期は妨げない。 */
sealed interface CleanupState {
    /** 読み取りの権限が無い。項目を開くと権限を求める。 */
    data object NeedsPermission : CleanupState

    data object Denied : CleanupState

    /** T1 に無い曲と T2 に無い m3u8（X6-2）。 */
    data class Found(val items: List<StoredFile>) : CleanupState
}

/** 音楽ファイルの読み取りの権限の状態。 */
enum class ReadAccess {
    Granted,

    /** 無いが、求められる。 */
    Requestable,

    /** 拒否済みで、同じ起動の間は求めない（X7-4-2）。 */
    Denied,
}

/** 保存先の選択肢（S5。D16）。[available] が偽は、選んでいるが今は見つからない SD。 */
data class StorageOption(val volume: String, val label: String, val available: Boolean)

data class StorageUi(val selected: String, val options: List<StorageOption>)

/**
 * 本体は常に選べる。SD は `MediaStore.getExternalVolumeNames` が返す本体以外の名前（[mounted]）。
 * [saved] が本体以外で今つながっていなければ、「見つからない」の選択肢として残す（本体へ切り替えない）。
 */
fun storageUi(mounted: Set<String>, saved: String): StorageUi {
    val others = mounted.filter { it != SyncValues.VOLUME_PRIMARY }.sorted()
    val options = buildList {
        add(StorageOption(SyncValues.VOLUME_PRIMARY, "本体", available = true))
        others.forEachIndexed { index, volume ->
            add(StorageOption(volume, if (others.size == 1) "SD カード" else "SD カード ${index + 1}", available = true))
        }
        if (saved != SyncValues.VOLUME_PRIMARY && saved !in others) add(StorageOption(saved, "SD カード（見つかりません）", available = false))
    }
    return StorageUi(saved, options)
}

/**
 * 保存先を変えた後の案内。前の場所（T9）に曲が残っていれば、次の同期の前に消す案内を足す。
 * 残したまま元の場所へ戻すと、旧ファイルとの衝突で全曲が接尾辞つきの重複になる（X6-19）。変更は止めない。
 */
fun storageChangedNotice(relocatedCount: Int): String =
    if (relocatedCount > 0) "$STORAGE_CHANGED_NOTICE。$STORAGE_LEFTOVER_NOTICE" else STORAGE_CHANGED_NOTICE

/** 「以前の場所の曲を消す」を終えた後の案内。 */
fun relocatedDeletedNotice(deleted: Int): String = "前の保存先から $deleted 件を削除しました"

data class SettingsUiState(
    val autoEnabled: Boolean,
    /** 「PC に無い曲を端末から消す」。既定は OFF。 */
    val deleteMissing: Boolean,
    /** 切り替え前の場所に残る、このアプリが書いたファイルの数（T9）。 */
    val relocatedCount: Int,
    val storage: StorageUi,
    /** `Music/<V22>/`。 */
    val saveFolder: String,
    val pcName: String?,
    val fingerprint: String?,
    /** 通知の許可が無いときだけ「通知が出ない」を 1 行出す。 */
    val notificationsOff: Boolean,
    val logBytes: Long,
    val cleanup: CleanupState,
)

fun settingsUi(autoSetting: String?, deleteMissingSetting: String?, relocatedCount: Int, storage: StorageUi, pairing: Pairing?, notificationsAllowed: Boolean, logBytes: Long, cleanup: CleanupState) =
    SettingsUiState(
        autoEnabled = autoSetting == SyncValues.SETTING_ON,
        deleteMissing = deleteMissingSetting == SyncValues.SETTING_ON,
        relocatedCount = relocatedCount,
        storage = storage,
        saveFolder = SyncValues.MEDIA_ROOT,
        pcName = pairing?.pcName,
        // T4 の `spki_hash` は小文字の 16 進 64 桁。M1 の共通の関数で確認コードにする（PC と同じ表示。X4-5）
        fingerprint = pairing?.let { formatFingerprintOfHash(it.spkiHash.hexToByteArray()) },
        notificationsOff = !notificationsAllowed,
        logBytes = logBytes,
        cleanup = cleanup,
    )

/**
 * 「以前の場所の曲を消す」（D16）。確認の後に呼ぶ。同期が走っている間は何もせず null を返す。
 * 消すのは T9 の行（このアプリが書いたもの）だけで、持ち主が違って消せない行は残る。
 */
suspend fun cleanRelocated(writer: MediaWriter, syncing: Boolean): Int? = if (syncing) null else writer.deleteRelocated()

/**
 * S5 の操作（X7-1-6）。画面の状態 [state] を持ち、知らせは [showMessage] に出す。
 * 解除の後の遷移は呼び出し側が行う（[unpair] の戻り値）。
 */
class SettingsPresenter(
    private val scope: CoroutineScope,
    private val store: SyncStore,
    /** 自動同期の切り替え（T7 の更新と登録）。成功なら真。 */
    private val setAutoEnabled: suspend (Boolean) -> Boolean,
    private val mountedVolumes: () -> Set<String>,
    private val storageVolume: suspend () -> String,
    private val logBytes: () -> Long,
    /** 以前の曲の整理に使う書き込み役。 */
    private val cleanupWriter: suspend () -> MediaWriter,
    private val syncing: () -> Boolean,
    /** 鍵の削除と T4 の解除。成功なら真。 */
    private val unpairDevice: suspend () -> Boolean,
    private val showMessage: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow<SettingsUiState?>(null)
    val state: StateFlow<SettingsUiState?> = mutableState

    fun refresh(notificationsAllowed: Boolean, readAccess: ReadAccess) {
        scope.launch {
            val cleanup = when (readAccess) {
                ReadAccess.Denied -> CleanupState.Denied
                ReadAccess.Requestable -> CleanupState.NeedsPermission
                ReadAccess.Granted -> CleanupState.Found(cleanupWriter().leftovers())
            }
            mutableState.value = settingsUi(
                autoSetting = store.setting(SyncScheduler.AUTO_ENABLED_KEY),
                deleteMissingSetting = store.setting(SyncValues.DELETE_MISSING_KEY),
                relocatedCount = store.relocatedUris().size,
                storage = storageUi(mountedVolumes(), storageVolume()),
                pairing = store.pairing(),
                notificationsAllowed = notificationsAllowed,
                logBytes = logBytes(),
                cleanup = cleanup,
            )
        }
    }

    fun toggleAuto(enabled: Boolean) {
        scope.launch {
            if (!setAutoEnabled(enabled)) showMessage(AUTO_SYNC_FAILED_NOTICE)
            mutableState.update { it?.copy(autoEnabled = store.setting(SyncScheduler.AUTO_ENABLED_KEY) == SyncValues.SETTING_ON) }
        }
    }

    /** 「PC に無い曲を端末から消す」の切り替え。 */
    fun setDeleteMissing(enabled: Boolean) {
        scope.launch {
            store.putSetting(SyncValues.DELETE_MISSING_KEY, if (enabled) SyncValues.SETTING_ON else SyncValues.SETTING_OFF)
            mutableState.update { it?.copy(deleteMissing = enabled) }
        }
    }

    /** 「以前の場所の曲を消す」。確認は画面が済ませてある。同期が走っている間は消さない。 */
    fun deleteRelocated() {
        scope.launch {
            val deleted = cleanRelocated(cleanupWriter(), syncing())
            showMessage(if (deleted == null) SYNCING_NO_DELETE_NOTICE else relocatedDeletedNotice(deleted))
            mutableState.update { it?.copy(relocatedCount = store.relocatedUris().size) }
        }
    }

    /** 保存先の切り替え（D16）。T1、T2 は動かさず、次の同期が新しい場所へ取り直す。 */
    fun setStorage(volume: String) {
        scope.launch {
            store.putSetting(SyncValues.STORAGE_VOLUME_KEY, volume)
            mutableState.update { it?.copy(storage = storageUi(mountedVolumes(), volume)) }
            showMessage(storageChangedNotice(store.relocatedUris().size))
        }
    }

    /** ペアリングの解除。解除できなければ知らせを出して偽を返す。 */
    suspend fun unpair(): Boolean {
        val unpaired = unpairDevice()
        if (!unpaired) showMessage(UNPAIR_FAILED_NOTICE)
        return unpaired
    }
}
