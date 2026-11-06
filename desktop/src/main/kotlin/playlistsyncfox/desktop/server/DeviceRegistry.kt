package playlistsyncfox.desktop.server

import playlistsyncfox.desktop.secrets.KeyRing
import playlistsyncfox.desktop.settings.PairedDevice
import playlistsyncfox.desktop.settings.SettingsStore
import java.time.Clock

/** 端末の登録と解除。K1（鍵）と settings.json の `devices` を同じ時点で更新する（X5-1 の P6、X5-5 の W3）。 */
class DeviceRegistry(private val keyRing: KeyRing, private val settings: SettingsStore, private val clock: Clock) {
    /** K1 の保存、設定の追加の順。どちらかが失敗したら、入れた分を戻して偽を返す。 */
    fun register(deviceId: String, displayName: String, key: ByteArray): Boolean = try {
        keyRing.add(deviceId, key)
        settings.update { current ->
            current.copy(
                devices = current.devices.filter { it.deviceId != deviceId } +
                    PairedDevice(deviceId, displayName, formatIsoMillis(clock.instant())),
            )
        }
        true
    } catch (_: Exception) {
        runCatching { keyRing.remove(deviceId) }
        false
    }

    /** 解除：K1 の項目を消し、`devices` から除く。以後の署名つき要求は 401。 */
    fun unpair(deviceId: String) {
        keyRing.remove(deviceId)
        settings.update { current -> current.copy(devices = current.devices.filter { it.deviceId != deviceId }) }
    }
}
