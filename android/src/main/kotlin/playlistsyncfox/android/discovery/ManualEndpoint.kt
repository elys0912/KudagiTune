package playlistsyncfox.android.discovery

import playlistsyncfox.android.sync.PairingKeeper

/** 手入力の宛先（X7-2-3）。[host] は IPv4 か名前。 */
data class ManualEndpoint(val host: String, val port: Int) {
    private val hostIsIpv4 get() = host.all { it.isDigit() || it == '.' }

    /** 保存する列の方式（X7-2-4）。名前は `tailscale`、IPv4 は `usb`。ネットワークの種別では決めない。 */
    val kind: String get() = if (hostIsIpv4) PairingKeeper.USB else PairingKeeper.TAILSCALE

    /** T4 の列に書く値。 */
    val savedValue: String get() = "$host:$port"
}

enum class ManualReason(val text: String) {
    Empty("入力が空"),
    BadChar("使えない文字がある"),
    BadShape("形が違う"),
    BadPort("ポートが違う"),
    BadIp("IP の形が違う"),
    BadName("名前の形が違う"),
}

sealed interface ManualCheck {
    data class Valid(val endpoint: ManualEndpoint) : ManualCheck

    data class Invalid(val reason: ManualReason) : ManualCheck
}

/** V16 サーバーの既定のポート。 */
const val DEFAULT_PORT = 47800

/** V49 ホスト名の 1 ラベルの上限。 */
const val MAX_LABEL_LENGTH = 63

/** V50 ホスト名の全体の上限。 */
const val MAX_HOST_LENGTH = 253

/** 形だけの検査。最初に外れた理由を返す。到達できるかは接続の試行で分かる。 */
fun checkManualEndpoint(input: String): ManualCheck {
    val text = input.trim()
    if (text.isEmpty()) return invalid(ManualReason.Empty)
    if (!text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' || it == ':' }) {
        return invalid(ManualReason.BadChar)
    }
    val colons = text.count { it == ':' }
    if (colons >= 2) return invalid(ManualReason.BadShape)

    val host: String
    val port: Int
    if (colons == 1) {
        host = text.substringBefore(':')
        val portText = text.substringAfter(':')
        port = portText.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.toInt()
            ?.takeIf { it in 1..65535 }
            ?: return invalid(ManualReason.BadPort)
        if (host.isEmpty()) return invalid(ManualReason.BadShape)
    } else {
        host = text
        port = DEFAULT_PORT
    }

    val reason = if (host.all { it.isDigit() || it == '.' }) ipv4Problem(host) else nameProblem(host)
    return if (reason == null) ManualCheck.Valid(ManualEndpoint(host, port)) else invalid(reason)
}

private fun invalid(reason: ManualReason) = ManualCheck.Invalid(reason)

private fun ipv4Problem(host: String): ManualReason? {
    val parts = host.split('.')
    val ok = parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.toInt() in 0..255 }
    return if (ok) null else ManualReason.BadIp
}

private fun nameProblem(host: String): ManualReason? {
    if (host.first() in ".-" || host.last() in ".-" || host.length > MAX_HOST_LENGTH) return ManualReason.BadName
    val labels = host.split('.')
    return if (labels.all { it.length in 1..MAX_LABEL_LENGTH }) null else ManualReason.BadName
}
