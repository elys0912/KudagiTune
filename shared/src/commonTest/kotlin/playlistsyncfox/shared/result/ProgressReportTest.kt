package playlistsyncfox.shared.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProgressReportTest {
    private fun body(doneTracks: String = "1", totalTracks: String = "3", doneBytes: String = "100", totalBytes: String = "300") =
        """{"stage":"transfer","done_tracks":$doneTracks,"total_tracks":$totalTracks,"done_bytes":$doneBytes,"total_bytes":$totalBytes}"""

    @Test
    fun `TS-182 正しい本文は読める`() {
        assertEquals(ProgressReport(ProgressStage.Transfer, 1, 3, 100, 300), readProgressReport(body()))
    }

    @Test
    fun `TS-183 数が Int や Long の範囲を超える本文は例外を投げず null になる`() {
        val outOfRange = listOf(
            body(doneTracks = "2147483648"),
            body(totalTracks = "-2147483649"),
            body(doneBytes = "9223372036854775808"),
            body(totalBytes = "99999999999999999999"),
        )

        for (text in outOfRange) {
            assertNull(readProgressReport(text), "本文: $text")
        }
    }
}
