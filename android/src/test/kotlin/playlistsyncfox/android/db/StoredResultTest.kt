package playlistsyncfox.android.db

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import playlistsyncfox.shared.result.Capacity
import playlistsyncfox.shared.result.Counts
import playlistsyncfox.shared.result.SyncOutcome
import playlistsyncfox.shared.result.SyncResult
import playlistsyncfox.shared.result.SyncTrigger
import playlistsyncfox.shared.result.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MISMATCH_KEY = "manifest_version_mismatch"

private fun sampleResult(manifestVersionMismatch: Boolean) = SyncResult(
    startedAt = "2026-11-09T00:00:00.000Z",
    finishedAt = "2026-11-09T00:00:01.000Z",
    trigger = SyncTrigger.Manual,
    transport = Transport.Lan,
    outcome = SyncOutcome.Failed,
    hasMore = false,
    counts = Counts(0, 0, 0, 0, 0, 0, 0, 0),
    capacity = Capacity(0, 0, null, null),
    errors = emptyList(),
    excluded = emptyList(),
    unplayable = emptyList(),
    libraryId = null,
    contentDigest = null,
    unresolvedPlaylists = emptyList(),
    duplicatesDropped = 0,
    removalSkipped = false,
    keyMismatch = null,
    errorDetail = null,
    manifestVersionMismatch = manifestVersionMismatch,
)

class StoredResultTest {
    @Test
    fun `版違いの印が真の結果は、保存して読み戻しても全体が同じで、キーは manifest_version_mismatch になる`() {
        val original = sampleResult(manifestVersionMismatch = true)

        val saved = encodeResult(original)

        assertEquals(original, decodeResult(saved))
        assertTrue(Json.parseToJsonElement(saved).jsonObject.getValue(MISMATCH_KEY).jsonPrimitive.boolean)
    }

    @Test
    fun `版違いの印が偽の結果は、保存して読み戻しても偽のまま`() {
        val original = sampleResult(manifestVersionMismatch = false)

        val restored = decodeResult(encodeResult(original))

        assertEquals(original, restored)
        assertFalse(restored.manifestVersionMismatch)
    }

    @Test
    fun `manifest_version_mismatch を持たない既存の行は、偽として読む`() {
        val saved = Json.parseToJsonElement(encodeResult(sampleResult(manifestVersionMismatch = true))).jsonObject
        val savedBeforeTheKeyExisted = JsonObject(saved - MISMATCH_KEY).toString()

        assertFalse(decodeResult(savedBeforeTheKeyExisted).manifestVersionMismatch)
    }
}
