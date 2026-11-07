package playlistsyncfox.android.ui

import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.storage.StoredFile
import playlistsyncfox.android.sync.SyncValues

// S5 設定（X7-1-6）。

/** 「以前の曲の整理」。権限が拒否されたら項目を閉じる。同期は妨げない。 */
sealed interface CleanupState {
    /** 読み取りの権限が無い。項目を開くと権限を求める。 */
    data object NeedsPermission : CleanupState

    data object Denied : CleanupState

    /** T1 に無い曲と T2 に無い m3u8（X6-2）。 */
    data class Found(val items: List<StoredFile>) : CleanupState
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
            add(StorageOption(volume, if (others.size == 1) "SD" else "SD ${index + 1}", available = true))
        }
        if (saved != SyncValues.VOLUME_PRIMARY && saved !in others) add(StorageOption(saved, "SD（見つからない）", available = false))
    }
    return StorageUi(saved, options)
}

data class SettingsUiState(
    val autoEnabled: Boolean,
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

fun settingsUi(autoSetting: String?, storage: StorageUi, pairing: Pairing?, notificationsAllowed: Boolean, logBytes: Long, cleanup: CleanupState) =
    SettingsUiState(
        autoEnabled = autoSetting == "true",
        storage = storage,
        saveFolder = SyncValues.MEDIA_ROOT,
        pcName = pairing?.pcName,
        fingerprint = pairing?.let { fingerprintOf(it.spkiHash) },
        notificationsOff = !notificationsAllowed,
        logBytes = logBytes,
        cleanup = cleanup,
    )
