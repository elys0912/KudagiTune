package playlistsyncfox.android.storage

import android.content.Context
import android.os.storage.StorageManager
import android.provider.MediaStore
import playlistsyncfox.android.sync.SyncValues

/** 保存先のボリューム（D16）。本体は `external_primary`、それ以外の名前を SD と呼ぶ。実機で確かめる項目は C-15。 */
class StorageVolumes(private val context: Context) {
    /** 今つながっているボリューム名（`MediaStore.getExternalVolumeNames`）。 */
    fun mounted(): Set<String> = MediaStore.getExternalVolumeNames(context)

    /** 選んだボリュームの空き。測れなければ null。worker スレッドで呼ぶ。 */
    fun freeBytes(volume: String): Long? {
        val storage = context.getSystemService(StorageManager::class.java)
        return if (volume == SyncValues.VOLUME_PRIMARY) {
            runCatching { storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) }.getOrNull()
        } else {
            storage.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volume }?.directory?.usableSpace
        }
    }
}
