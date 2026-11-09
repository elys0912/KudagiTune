package playlistsyncfox.android.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.net.PairingCall
import playlistsyncfox.android.net.PairingClient
import playlistsyncfox.android.sync.PairingDraft
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.sync.SavedEndpoint
import playlistsyncfox.android.sync.SyncValues
import playlistsyncfox.shared.auth.confirmPairing
import playlistsyncfox.shared.auth.finishPairing
import playlistsyncfox.shared.auth.startPairing
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.formatFingerprintOfHash
import java.util.UUID

/**
 * P5・P6 と鍵の導出、保存（X4-2、X6-6）を順に行う [PairingGateway] の実体。
 * 保存は MAC_p が合ってからだけ。購読を止めると接続を切り、一時の値は参照を手放す。
 * [openClient] は P5・P6 の専用クライアントを作る。
 */
class DevicePairingGateway(
    private val store: SyncStore,
    private val keeper: PairingKeeper,
    private val crypto: CryptoProvider,
    /** 機種名まで。人名を含めない（N1-6）。 */
    private val deviceName: String,
    private val openClient: (host: String, port: Int) -> PairingClient,
) : PairingGateway {
    override fun pair(target: PairingTarget, code: String): Flow<PairingEvent> = flow {
        openClient(target.host, target.port).use { client -> execute(client, target, code) { emit(it) } }
    }

    private suspend fun execute(client: PairingClient, target: PairingTarget, code: String, emit: suspend (PairingEvent) -> Unit) {
        val deviceId = store.setting(SyncValues.DEVICE_ID_KEY) ?: UUID.randomUUID().toString().also { store.putSetting(SyncValues.DEVICE_ID_KEY, it) }
        val initiation = startPairing(crypto, deviceId, code)

        val reply = when (val call = client.start(deviceId, deviceName, initiation.x.encoded)) {
            is PairingCall.Ok -> call.value
            else -> return emit(refusal(call))
        }
        val spkiHash = client.spkiHash ?: return emit(PairingEvent.Failed(ConnectFailedNotice))
        emit(PairingEvent.Fingerprint(formatFingerprintOfHash(spkiHash)))

        val confirmation = confirmPairing(crypto, initiation, reply.pcId, reply.y, spkiHash) ?: return emit(PairingEvent.Failed(BadReplyNotice))
        val macP = when (val call = client.confirm(deviceId, confirmation.macD)) {
            is PairingCall.Ok -> call.value
            else -> return emit(refusal(call))
        }
        // AAD の SPKI は P5 の接続で見たもの。P6 で別の証明書を見たら中間者の疑いがあるので保存しない
        if (!crypto.constantTimeEquals(spkiHash, client.spkiHash ?: spkiHash)) return emit(PairingEvent.Failed(CertificateChangedNotice))

        val key = finishPairing(crypto, confirmation, macP) ?: return emit(PairingEvent.Failed(BadProofNotice))
        val draft = PairingDraft(deviceId, reply.pcId, target.name, SavedEndpoint(target.kind, target.savedValue), spkiHash.toHexString())
        // 保存に失敗しても PC には登録が残る。入れ直しの案内を出す（X7-1-3）
        emit(if (keeper.save(draft, key)) PairingEvent.Completed(deviceId, target.name) else PairingEvent.DeviceExists)
    }

    private fun refusal(call: PairingCall<*>): PairingEvent = when (call) {
        PairingCall.Disconnected -> PairingEvent.Failed(ConnectFailedNotice)
        PairingCall.BadReply -> PairingEvent.Failed(BadReplyNotice)
        is PairingCall.Refused -> when {
            call.status == 401 -> PairingEvent.CodeMismatch
            call.status == 429 -> PairingEvent.Waiting
            call.status == 403 && call.detail == "pairing_locked" -> PairingEvent.AttemptsExhausted
            call.status == 403 && call.detail == "device_exists" -> PairingEvent.DeviceExists
            call.status == 403 && call.detail == "pairing_closed" -> PairingEvent.Failed(PairingClosedNotice)
            else -> PairingEvent.Failed(PcRefusedNotice)
        }
        is PairingCall.Ok -> error("成功は呼び出し側で扱う")
    }
}
