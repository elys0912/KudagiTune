package playlistsyncfox.android.sync

import playlistsyncfox.android.db.Pairing
import playlistsyncfox.android.db.SyncStore

/** 署名用の共有の鍵（K3）を Android Keystore に置く入口。実体は vault/DeviceVault。 */
interface KeyVault {
    /** 別名の鍵が Keystore にあるか。 */
    fun exists(alias: String): Boolean

    /** 鍵を取り込む。取り込めたら真。 */
    fun import(alias: String, key: ByteArray): Boolean

    /** 別名を消す。消せた（元から無いのも含む）なら真。 */
    fun delete(alias: String): Boolean
}

/** 保存する接続先 1 件。[kind] は `lan`、`tailscale`、`usb`（X7-2-4）。 */
class SavedEndpoint(val kind: String, val value: String)

/** ペアリングの完了で T4 に書く内容。 */
class PairingDraft(
    val deviceId: String,
    val pcId: String,
    val pcName: String,
    val endpoint: SavedEndpoint,
    val spkiHash: String,
)

/** K3 の取り込みと T4 の作成・解除（X6-6）。順番と、失敗したとき何も残さないことを持つ。 */
class PairingKeeper(private val store: SyncStore, private val vault: KeyVault, private val now: () -> Long) {
    /** P6 の成功で呼ぶ。鍵の取り込み、T4 の作成の順。どちらかが失敗したら何も残さず偽。 */
    suspend fun save(draft: PairingDraft, key: ByteArray): Boolean {
        val alias = keyAliasOf(draft.deviceId)
        try {
            if (!vault.import(alias, key)) return false
        } finally {
            key.fill(0)
        }
        val endpoint = draft.endpoint
        val row = Pairing(
            deviceId = draft.deviceId,
            pcId = draft.pcId,
            pcName = draft.pcName,
            endpointLan = endpoint.value.takeIf { endpoint.kind == LAN },
            endpointTailscale = endpoint.value.takeIf { endpoint.kind == TAILSCALE },
            endpointUsb = endpoint.value.takeIf { endpoint.kind == USB },
            lastEndpoint = endpoint.kind,
            spkiHash = draft.spkiHash,
            keyAlias = alias,
            pairedAt = now(),
        )
        return try {
            store.savePairing(row)
            true
        } catch (_: Exception) {
            vault.delete(alias)
            false
        }
    }

    /** 別名を消してから T4 の行を消す。別名の削除に失敗したら T4 の行を残して偽。T1、T2 は触らない。 */
    suspend fun unpair(): Boolean {
        val pairing = store.pairing() ?: return true
        if (!vault.delete(pairing.keyAlias)) return false
        store.deletePairing(pairing.deviceId)
        return true
    }

    companion object {
        const val LAN = "lan"
        const val TAILSCALE = "tailscale"
        const val USB = "usb"
    }
}

/** 別名は `device_id` で決まる（X6-6）。 */
fun keyAliasOf(deviceId: String) = "psfox-k3-$deviceId"

/** S5 の自動起動の切り替え。T7 を更新してから [register] を呼び、失敗したら T7 を戻す。成功なら真。 */
suspend fun SyncStore.switchAutoSync(enabled: Boolean, register: () -> Unit): Boolean {
    val before = setting(SyncScheduler.AUTO_ENABLED_KEY)
    putSetting(SyncScheduler.AUTO_ENABLED_KEY, if (enabled) SyncValues.SETTING_ON else SyncValues.SETTING_OFF)
    return try {
        register()
        true
    } catch (_: Exception) {
        putSetting(SyncScheduler.AUTO_ENABLED_KEY, before ?: SyncValues.SETTING_OFF)
        false
    }
}
