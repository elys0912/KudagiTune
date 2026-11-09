package playlistsyncfox.desktop

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val isoMillis = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** UTC、ミリ秒まで、末尾 `Z`（X3-1）。マニフェストの時刻、端末の登録日時、ログの時刻に使う。 */
fun formatIsoMillis(instant: Instant): String = isoMillis.format(instant)
