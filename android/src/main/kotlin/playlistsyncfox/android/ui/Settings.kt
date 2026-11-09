package playlistsyncfox.android.ui

import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.storage.MediaWriter
import playlistsyncfox.android.storage.StoredFile
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
