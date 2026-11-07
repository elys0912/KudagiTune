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

data class SettingsUiState(
    val autoEnabled: Boolean,
    /** `Music/<V22>/`。 */
    val saveFolder: String,
    val pcName: String?,
    val fingerprint: String?,
    /** 通知の許可が無いときだけ「通知が出ない」を 1 行出す。 */
    val notificationsOff: Boolean,
    val logBytes: Long,
    val cleanup: CleanupState,
)

fun settingsUi(autoSetting: String?, pairing: Pairing?, notificationsAllowed: Boolean, logBytes: Long, cleanup: CleanupState) =
    SettingsUiState(
        autoEnabled = autoSetting == "true",
        saveFolder = SyncValues.MEDIA_ROOT,
        pcName = pairing?.pcName,
        fingerprint = pairing?.let { fingerprintOf(it.spkiHash) },
        notificationsOff = !notificationsAllowed,
        logBytes = logBytes,
        cleanup = cleanup,
    )
