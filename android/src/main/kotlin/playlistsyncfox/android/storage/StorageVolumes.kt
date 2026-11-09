package playlistsyncfox.android.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import playlistsyncfox.android.sync.SyncValues

/** 保存先のボリューム（D16）。本体は `external_primary`、それ以外の名前を SD と呼ぶ。実機で確かめる項目は C-15。 */
class StorageVolumes(private val context: Context) {
    /** 今つながっているボリューム名（`MediaStore.getExternalVolumeNames`）。 */
    fun mounted(): Set<String> = MediaStore.getExternalVolumeNames(context)

    /** 選んだボリュームの空き。本体は必ず値を返す（X6-5）。SD は、ボリュームが見つからなければ null。worker スレッドで呼ぶ。 */
    fun freeBytes(volume: String): Long? {
        val storage = context.getSystemService(StorageManager::class.java)
        return if (volume == SyncValues.VOLUME_PRIMARY) {
            primaryFreeBytes(
                allocatable = { storage.getAllocatableBytes(StorageManager.UUID_DEFAULT) },
                statFs = ::sharedStorageFreeBytes,
            )
        } else {
            storage.storageVolumes.firstOrNull { it.mediaStoreVolumeName == volume }?.directory?.usableSpace
        }
    }
}

/** 本体の空き。[allocatable]（`getAllocatableBytes`）が使えないときは、[statFs] の空きで代える（容量の確認を省かない。X6-5）。 */
internal fun primaryFreeBytes(allocatable: () -> Long, statFs: () -> Long): Long =
    runCatching(allocatable).getOrElse { statFs() }

/** 共有ストレージのファイルシステムの空き（`StatFs.getAvailableBytes`）。 */
@Suppress("DEPRECATION")
private fun sharedStorageFreeBytes(): Long = StatFs(Environment.getExternalStorageDirectory().path).availableBytes
