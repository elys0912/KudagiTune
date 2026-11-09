package playlistsyncfox.itunes.plist

import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadError
import playlistsyncfox.itunes.library.LibraryReadOutcome
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.LibraryTrack
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.ReadDiagnostics
import playlistsyncfox.itunes.library.TrackRef
import playlistsyncfox.itunes.library.UnidentifiedTrack
import playlistsyncfox.itunes.location.LocationFailure
import playlistsyncfox.itunes.location.TrackLocation
import playlistsyncfox.itunes.location.decodeFileUrl
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.format.DateTimeParseException
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants.END_ELEMENT
import javax.xml.stream.XMLStreamConstants.START_ELEMENT
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

private val trackKeys = setOf(
    "Persistent ID", "Track Type", "Location", "Total Time", "Name", "Artist", "Album",
    "Album Artist", "Track Number", "Disc Number", "Kind", "Protected",
)

private val persistentIdPattern = Regex("[0-9A-F]{16}")

private const val FILE_TRACK_TYPE = "File"

/** plist の値のうち、取る型だけを持つ。取らない型（dict、array、data、real）は [Other]。 */
private sealed interface Value {
    data class Text(val value: String) : Value
    data class Integer(val value: Long?) : Value
    data class Date(val value: Instant?) : Value
    data class Bool(val value: Boolean) : Value
    data object Other : Value
}

private class StructureException : Exception()

/** iTunes Music Library.xml のバイト列を、先頭から順に読む。 */
fun readLibrary(xml: ByteArray): LibraryReadOutcome =
    try {
        LibraryReadOutcome.Read(PlistLibraryReader(newStreamReader(xml)).read())
    } catch (e: XMLStreamException) {
        LibraryReadOutcome.Failed(LibraryReadError.InvalidSyntax)
    } catch (e: StructureException) {
        LibraryReadOutcome.Failed(LibraryReadError.InvalidStructure)
    }

private fun newStreamReader(xml: ByteArray): XMLStreamReader {
    val factory = XMLInputFactory.newFactory()
    // plist の DOCTYPE は外部の URL を指す。通信と実体の展開を起こさないため、DTD ごと読まない
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    // 1 つの要素の文字が複数の部分に分かれて届くのを、要素の終わりまで連結させる
    factory.setProperty(XMLInputFactory.IS_COALESCING, true)
    return factory.createXMLStreamReader(ByteArrayInputStream(xml))
}

private class PlistLibraryReader(private val xml: XMLStreamReader) {
    private var malformedEntries = 0
    private var libraryPersistentId: String? = null
    private var applicationVersion: String? = null
    private var xmlDate: Instant? = null
    private var tracksFound = false
    private var playlistsFound = false

    private val rawTracks = mutableListOf<Pair<String, Map<String, Value>>>()
    private val rawPlaylists = mutableListOf<RawPlaylist>()

    private class RawPlaylist(
        val persistentId: String?,
        val name: String?,
        val parentPersistentId: String?,
        val kind: PlaylistKind,
        val trackIds: List<Long>,
    )

    fun read(): LibraryReadResult {
        readTopLevel()
        if (!tracksFound || !playlistsFound) throw StructureException()
        return resolve()
    }

    private fun readTopLevel() {
        // DOCTYPE やコメントを読み飛ばして、最初の要素へ進む
        do { val event = xml.next() } while (event != START_ELEMENT)
        if (xml.localName != "plist") throw StructureException()
        if (xml.nextTag() != START_ELEMENT || xml.localName != "dict") throw StructureException()

        eachEntry { key ->
            when {
                key == "Tracks" && xml.localName == "dict" -> { tracksFound = true; readTracks() }
                key == "Playlists" && xml.localName == "array" -> { playlistsFound = true; readPlaylists() }
                key == "Library Persistent ID" -> libraryPersistentId = text(readValue())?.uppercase()
                key == "Application Version" -> applicationVersion = text(readValue())
                key == "Date" -> xmlDate = (readValue() as? Value.Date)?.value
                else -> skipElement()
            }
        }
    }

    private fun readTracks() {
        eachEntry { trackIdKey ->
            if (xml.localName == "dict") {
                rawTracks += trackIdKey to readTrackValues()
            } else {
                malformedEntries++
                skipElement()
            }
        }
    }

    private fun readTrackValues(): Map<String, Value> {
        val values = HashMap<String, Value>()
        eachEntry { key ->
            if (key in trackKeys) values[key] = readValue() else skipElement()
        }
        return values
    }

    private fun readPlaylists() {
        while (xml.nextTag() == START_ELEMENT) {
            if (xml.localName == "dict") {
                readPlaylist()
            } else {
                malformedEntries++
                skipElement()
            }
        }
    }

    private fun readPlaylist() {
        var persistentId: String? = null
        var name: String? = null
        var parentPersistentId: String? = null
        var isMaster = false
        var isFolder = false
        var hasDistinguishedKind = false
        var trackIds = emptyList<Long>()

        eachEntry { key ->
            when (key) {
                "Playlist Persistent ID" -> persistentId = text(readValue())?.uppercase()
                "Name" -> name = text(readValue())
                "Parent Persistent ID" -> parentPersistentId = text(readValue())?.uppercase()
                "Master" -> isMaster = flag(readValue())
                "Folder" -> isFolder = flag(readValue())
                "Distinguished Kind" -> { hasDistinguishedKind = true; skipElement() }
                "Playlist Items" -> {
                    if (xml.localName == "array") {
                        trackIds = readPlaylistItems()
                    } else {
                        malformedEntries++
                        skipElement()
                    }
                }
                else -> skipElement()
            }
        }

        val kind = when {
            isMaster -> PlaylistKind.Excluded(PlaylistExclusion.Master)
            hasDistinguishedKind -> PlaylistKind.Excluded(PlaylistExclusion.DistinguishedKind)
            isFolder -> PlaylistKind.Excluded(PlaylistExclusion.Folder)
            else -> PlaylistKind.Normal
        }
        rawPlaylists += RawPlaylist(persistentId, name, parentPersistentId, kind, trackIds)
    }

    private fun readPlaylistItems(): List<Long> {
        val trackIds = mutableListOf<Long>()
        while (xml.nextTag() == START_ELEMENT) {
            if (xml.localName != "dict") {
                malformedEntries++
                skipElement()
                continue
            }

            var trackId: Long? = null
            eachEntry { key ->
                // 値が不正でも、ここでは数えない。Track ID を取れない項目として、下で 1 回だけ数える
                if (key == "Track ID") trackId = (readValue() as? Value.Integer)?.value else skipElement()
            }
            val found = trackId
            if (found != null) trackIds += found else malformedEntries++
        }
        return trackIds
    }

    /** `Track ID` を `Persistent ID` に置き換える。文書の最後まで読んだ後でないと対応が揃わない。 */
    private fun resolve(): LibraryReadResult {
        val tracks = mutableListOf<LibraryTrack>()
        val unidentifiedTracks = mutableListOf<UnidentifiedTrack>()
        val seenPersistentIds = HashSet<String>()
        val refsByTrackId = HashMap<String, TrackRef>()
        val locationFailures = HashMap<LocationFailure, Int>()
        var duplicatePersistentIds = 0

        for ((trackId, values) in rawTracks) {
            val persistentId = text(values["Persistent ID"])?.uppercase()?.takeIf { persistentIdPattern.matches(it) }
            if (persistentId == null) {
                refsByTrackId[trackId] = TrackRef.Unidentified(unidentifiedTracks.size)
                unidentifiedTracks += UnidentifiedTrack(name = text(values["Name"]), artist = text(values["Artist"]))
                continue
            }
            // 落とした重複の Track ID も、残した曲への参照として扱う
            refsByTrackId[trackId] = TrackRef.Identified(persistentId)
            if (!seenPersistentIds.add(persistentId)) {
                duplicatePersistentIds++
                continue
            }

            val track = buildTrack(persistentId, values)
            (track.location as? TrackLocation.Undecodable)?.let { locationFailures.merge(it.failure, 1, Int::plus) }
            tracks += track
        }

        var droppedTrackRefs = 0
        val playlists = mutableListOf<LibraryPlaylist>()
        for (raw in rawPlaylists) {
            if (raw.persistentId == null || !persistentIdPattern.matches(raw.persistentId)) {
                malformedEntries++
                continue
            }
            val items = raw.trackIds.mapNotNull { id ->
                refsByTrackId[id.toString()] ?: run { droppedTrackRefs++; null }
            }
            playlists += LibraryPlaylist(raw.persistentId, raw.name ?: "", raw.parentPersistentId, raw.kind, items)
        }

        return LibraryReadResult(
            libraryPersistentId = libraryPersistentId,
            tracks = tracks,
            unidentifiedTracks = unidentifiedTracks,
            playlists = playlists,
            diagnostics = ReadDiagnostics(
                trackCount = tracks.size,
                playlistCount = playlists.size,
                droppedTrackRefs = droppedTrackRefs,
                duplicatePersistentIds = duplicatePersistentIds,
                malformedEntries = malformedEntries,
                locationFailures = locationFailures,
                applicationVersion = applicationVersion,
                xmlDate = xmlDate,
            ),
        )
    }

    private fun buildTrack(persistentId: String, values: Map<String, Value>): LibraryTrack {
        val trackType = text(values["Track Type"])
        val rawLocation = text(values["Location"])
        val location = if (rawLocation == null || trackType != FILE_TRACK_TYPE) {
            TrackLocation.Absent
        } else {
            decodeFileUrl(rawLocation)
        }
        return LibraryTrack(
            persistentId = persistentId,
            location = location,
            trackType = trackType,
            name = text(values["Name"]),
            artist = text(values["Artist"]),
            album = text(values["Album"]),
            albumArtist = text(values["Album Artist"]),
            trackNumber = integer(values["Track Number"]),
            discNumber = integer(values["Disc Number"]),
            totalTimeMs = integer(values["Total Time"]),
            kind = text(values["Kind"]),
            isProtected = flag(values["Protected"]),
        )
    }

    /**
     * 辞書の中身（`key` と値の組）を終わりまで読む。辞書の開始の要素に居る状態で呼ぶ。
     * [handle] は値の開始の要素に居る状態で呼ばれ、その値を読み切って終了の要素で返すこと。
     */
    private fun eachEntry(handle: (key: String) -> Unit) {
        while (xml.nextTag() == START_ELEMENT) {
            val key = xml.elementText
            xml.nextTag()
            handle(key)
        }
    }

    private fun readValue(): Value = when (xml.localName) {
        "string" -> Value.Text(xml.elementText)
        "integer" -> Value.Integer(xml.elementText.trim().toLongOrNull())
        "date" -> Value.Date(parseInstant(xml.elementText.trim()))
        "true" -> { xml.elementText; Value.Bool(true) }
        "false" -> { xml.elementText; Value.Bool(false) }
        else -> { skipElement(); Value.Other }
    }

    /** 開始の要素に居る状態から、対応する終了の要素まで深さを数えて読み捨てる。 */
    private fun skipElement() {
        var depth = 1
        while (depth > 0) {
            when (xml.next()) {
                START_ELEMENT -> depth++
                END_ELEMENT -> depth--
            }
        }
    }

    private fun parseInstant(text: String): Instant? =
        try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }

    private fun text(value: Value?): String? = when (value) {
        null -> null
        is Value.Text -> value.value
        else -> { malformedEntries++; null }
    }

    private fun integer(value: Value?): Long? = when (value) {
        null -> null
        is Value.Integer -> value.value ?: run { malformedEntries++; null }
        else -> { malformedEntries++; null }
    }

    private fun flag(value: Value?): Boolean = when (value) {
        null -> false
        is Value.Bool -> value.value
        else -> { malformedEntries++; false }
    }
}
