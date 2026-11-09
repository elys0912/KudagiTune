package playlistsyncfox.android.ui

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import playlistsyncfox.android.db.SyncStore
import playlistsyncfox.android.net.PairingCall
import playlistsyncfox.android.net.PairingClient
import playlistsyncfox.android.sync.PairingDraft
import playlistsyncfox.android.sync.PairingKeeper
import playlistsyncfox.android.sync.SavedEndpoint
import playlistsyncfox.shared.auth.confirmPairing
import playlistsyncfox.shared.auth.finishPairing
import playlistsyncfox.shared.auth.startPairing
import playlistsyncfox.shared.crypto.CryptoProvider
import playlistsyncfox.shared.crypto.formatFingerprintOfHash
import java.util.UUID

private const val DEVICE_ID_KEY = "device_id"

private val ConnectFailed = Notice("PC に接続できません", "アドレスが正しいか、PC でアプリが起動しているかを確認して、やり直してください", "接続失敗", rePair = false)
private val BadReply = Notice("PC からの応答を読み取れません", "やり直してください", "応答の形", rePair = false)
private val PcRefused = Notice("PC がペアリングを受け付けませんでした", "PC の画面を確認して、やり直してください", "PC の拒否", rePair = false)
private val BadProof = Notice("PC の確認に失敗しました", "PC の画面の「端末」で解除してから、やり直してください", "確認の印", rePair = true)
private val CertificateChanged = Notice("接続の途中で PC の証明書が変わりました", "ネットワークを確認して、やり直してください", "証明書の変更", rePair = false)

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
        val deviceId = store.setting(DEVICE_ID_KEY) ?: UUID.randomUUID().toString().also { store.putSetting(DEVICE_ID_KEY, it) }
        val initiation = startPairing(crypto, deviceId, code)

        val reply = when (val call = client.start(deviceId, deviceName, initiation.x.encoded)) {
            is PairingCall.Ok -> call.value
            else -> return emit(refusal(call))
        }
        val spkiHash = client.spkiHash ?: return emit(Failed(ConnectFailed))
        emit(PairingEvent.Fingerprint(formatFingerprintOfHash(spkiHash)))

        val confirmation = confirmPairing(crypto, initiation, reply.pcId, reply.y, spkiHash) ?: return emit(Failed(BadReply))
        val macP = when (val call = client.confirm(deviceId, confirmation.macD)) {
            is PairingCall.Ok -> call.value
            else -> return emit(refusal(call))
        }
        // AAD の SPKI は P5 の接続で見たもの。P6 で別の証明書を見たら中間者の疑いがあるので保存しない
        if (!crypto.constantTimeEquals(spkiHash, client.spkiHash ?: spkiHash)) return emit(Failed(CertificateChanged))

        val key = finishPairing(crypto, confirmation, macP) ?: return emit(Failed(BadProof))
        val draft = PairingDraft(deviceId, reply.pcId, target.name, SavedEndpoint(target.kind, target.savedValue), spkiHash.toHexString())
        // 保存に失敗しても PC には登録が残る。入れ直しの案内を出す（X7-1-3）
        emit(if (keeper.save(draft, key)) PairingEvent.Completed(deviceId, target.name) else PairingEvent.DeviceExists)
    }

    private fun Failed(notice: Notice) = PairingEvent.Failed(notice)

    private fun refusal(call: PairingCall<*>): PairingEvent = when (call) {
        PairingCall.Disconnected -> Failed(ConnectFailed)
        PairingCall.BadReply -> Failed(BadReply)
        is PairingCall.Refused -> when {
            call.status == 401 -> PairingEvent.CodeMismatch
            call.status == 429 -> PairingEvent.Waiting
            call.status == 403 && call.detail == "pairing_locked" -> PairingEvent.AttemptsExhausted
            call.status == 403 && call.detail == "device_exists" -> PairingEvent.DeviceExists
            call.status == 403 && call.detail == "pairing_closed" -> Failed(PairingClosedNotice)
            else -> Failed(PcRefused)
        }
        is PairingCall.Ok -> error("成功は呼び出し側で扱う")
    }
}
