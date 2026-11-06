package playlistsyncfox.desktop.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** 設定の形の版（X5-4）。最初の版は 1。 */
const val SETTINGS_SCHEMA_VERSION = 1

/** V5：古いと警告する日数の既定。 */
const val DEFAULT_STALE_DAYS = 7

/** V16：サーバーの既定のポート。 */
const val DEFAULT_PORT = 47800

/** settings.json の全体（X5-4）。必須の項目が欠けた JSON は読めない扱いにする。 */
@Serializable
data class Settings(
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("xml_path") val xmlPath: String? = null,
    @SerialName("selected_playlist_ids") val selectedPlaylistIds: List<String>,
    @SerialName("stale_days") val staleDays: Int,
    @SerialName("launch_on_login") val launchOnLogin: Boolean,
    val port: Int,
    @SerialName("pc_id") val pcId: String,
    @SerialName("library_id_fallback") val libraryIdFallback: String? = null,
    val devices: List<PairedDevice>,
)

@Serializable
data class PairedDevice(
    @SerialName("device_id") val deviceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("registered_at") val registeredAt: String,
    @SerialName("last_sync") val lastSync: LastSync? = null,
)

/** P4 の本文であり、`last_sync` として保存する形（X5-1 の「P4 の本文」）。曲名とパスを含めない。 */
@Serializable
data class LastSync(
    @SerialName("started_at") val startedAt: String,
    @SerialName("finished_at") val finishedAt: String,
    val outcome: ReportOutcome,
    @SerialName("has_more") val hasMore: Boolean,
    val counts: ReportCounts,
    val capacity: ReportCapacity,
    @SerialName("failure_counts") val failureCounts: Map<String, Int>,
    @SerialName("excluded_count") val excludedCount: Int,
)

@Serializable
enum class ReportOutcome {
    @SerialName("success") SUCCESS,
    @SerialName("partial") PARTIAL,
    @SerialName("interrupted") INTERRUPTED,
    @SerialName("failed") FAILED,
}

@Serializable
data class ReportCounts(
    @SerialName("to_add") val toAdd: Int,
    @SerialName("to_replace") val toReplace: Int,
    @SerialName("to_remove") val toRemove: Int,
    val unchanged: Int,
    val added: Int,
    val replaced: Int,
    val removed: Int,
    val failed: Int,
)

@Serializable
data class ReportCapacity(
    @SerialName("diff_bytes") val diffBytes: Long,
    @SerialName("bytes_transferred") val bytesTransferred: Long,
    @SerialName("free_bytes_before") val freeBytesBefore: Long? = null,
    @SerialName("free_bytes_after") val freeBytesAfter: Long? = null,
)

/** 読み込みの結果。[firstRun] は設定が無かった初回、[rescuedAs] は読めずに退避したファイル。 */
class SettingsLoad(val settings: Settings, val firstRun: Boolean, val rescuedAs: Path?)

internal val settingsJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    prettyPrint = true
}

/** 設定の読み書きの窓口（X5-4）。書き込みは直列にする。 */
class SettingsStore(private val file: Path, private val newPcId: () -> String = { UUID.randomUUID().toString() }) {
    private val lock = Any()

    @Volatile
    var current: Settings = defaults()
        private set

    private fun defaults() = Settings(
        schemaVersion = SETTINGS_SCHEMA_VERSION,
        selectedPlaylistIds = emptyList(),
        staleDays = DEFAULT_STALE_DAYS,
        launchOnLogin = true,
        port = DEFAULT_PORT,
        pcId = newPcId(),
        devices = emptyList(),
    )

    fun load(): SettingsLoad = synchronized(lock) {
        if (!Files.exists(file)) {
            return SettingsLoad(defaults().also { current = it; write(it) }, firstRun = true, rescuedAs = null)
        }
        val parsed = try {
            settingsJson.decodeFromString(Settings.serializer(), Files.readString(file))
        } catch (_: SerializationException) {
            null
        }
        // 新しい版は読まない。書き戻して失う事故を防ぐ（X5-4 の読み込み 3）
        if (parsed != null && parsed.schemaVersion <= SETTINGS_SCHEMA_VERSION) {
            current = parsed
            return SettingsLoad(parsed, firstRun = false, rescuedAs = null)
        }
        val bad = file.resolveSibling(file.fileName.toString() + ".bad")
        Files.move(file, bad, StandardCopyOption.REPLACE_EXISTING)
        SettingsLoad(defaults().also { current = it; write(it) }, firstRun = false, rescuedAs = bad)
    }

    /** [change] を適用して保存する。保存に失敗したら例外を投げ、保持する値は変えない。 */
    fun update(change: (Settings) -> Settings): Settings = synchronized(lock) {
        val next = change(current)
        write(next)
        current = next
        next
    }

    private fun write(settings: Settings) =
        writeAtomically(file) { it.write(settingsJson.encodeToString(Settings.serializer(), settings).encodeToByteArray()) }
}
