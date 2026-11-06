package playlistsyncfox.itunes.location

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 1 トラックの場所の結果（X2-2）。 */
sealed interface TrackLocation {
    /** 復号に成功した Windows のパス。 */
    data class Found(val windowsPath: String) : TrackLocation

    /** `Location` が無い、または `Track Type` が `File` でない。 */
    data object Absent : TrackLocation

    data class Undecodable(val failure: LocationFailure) : TrackLocation
}

/** 復号不能の細目。診断とログに使う。 */
enum class LocationFailure {
    InvalidFormat,
    UnresolvableHost,
    InvalidEncoding,
    NoDriveLetter,
    ControlCharacter,
}

private const val FILE_SCHEME = "file://"

private val driveLetterPath = Regex("^/[A-Za-z]:/.*", RegexOption.DOT_MATCHES_ALL)

/** 戻した後の `Location` の文字列を Windows のパスに直す。ファイルの API は呼ばない。 */
fun decodeFileUrl(location: String): TrackLocation {
    if (!location.startsWith(FILE_SCHEME, ignoreCase = true)) return failure(LocationFailure.InvalidFormat)

    val afterScheme = location.substring(FILE_SCHEME.length)
    val slash = afterScheme.indexOf('/')
    if (slash < 0) return failure(LocationFailure.InvalidFormat)

    val authority = afterScheme.substring(0, slash)
    val path = percentDecode(afterScheme.substring(slash)) ?: return failure(LocationFailure.InvalidEncoding)
    if (path.any { it.code < 0x20 || it.code == 0x7F }) return failure(LocationFailure.ControlCharacter)

    val isLocal = authority.isEmpty() || authority.equals("localhost", ignoreCase = true)
    if (isLocal) {
        if (!driveLetterPath.matches(path)) return failure(LocationFailure.NoDriveLetter)
        return TrackLocation.Found(path.substring(1).replace('/', '\\'))
    }

    val shareName = path.removePrefix("/").substringBefore('/')
    if (shareName.isEmpty()) return failure(LocationFailure.UnresolvableHost)
    return TrackLocation.Found("\\\\" + authority + path.replace('/', '\\'))
}

private fun failure(reason: LocationFailure) = TrackLocation.Undecodable(reason)

/** UTF-8 として厳密に復号する。`+` はそのまま残す。失敗なら null。 */
private fun percentDecode(encoded: String): String? {
    val bytes = ByteArrayOutputStream()
    var i = 0
    while (i < encoded.length) {
        if (encoded[i] == '%') {
            if (i + 2 >= encoded.length) return null
            val high = hexValue(encoded[i + 1])
            val low = hexValue(encoded[i + 2])
            if (high < 0 || low < 0) return null
            bytes.write(high * 16 + low)
            i += 3
        } else {
            val next = encoded.indexOf('%', i).let { if (it < 0) encoded.length else it }
            bytes.write(encoded.substring(i, next).toByteArray(Charsets.UTF_8))
            i = next
        }
    }

    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}
