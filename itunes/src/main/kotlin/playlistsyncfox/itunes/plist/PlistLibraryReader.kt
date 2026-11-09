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
import playlistsyncfox.shared.manifest.isPersistentId
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
    private var duplicatePersistentIds = 0
    private var droppedTrackRefs = 0
    private val locationFailures = HashMap<LocationFailure, Int>()
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

    /** 曲の解決の結果。[refsByTrackId] は、プレイリストの `Track ID` から曲への参照を引く表。 */
    private class ResolvedTracks(
        val tracks: List<LibraryTrack>,
        val unidentifiedTracks: List<UnidentifiedTrack>,
        val refsByTrackId: Map<String, TrackRef>,
    )

    fun read(): LibraryReadResult {
        readTopLevel()
        if (!tracksFound || !playlistsFound) throw StructureException()
        return resolve()
    }

    private fun readTopLevel() {
        // DOCTYPE やコメントを読み飛ばして、最初の要素へ進む
        while (xml.eventType != START_ELEMENT) xml.next()
        if (xml.localName != "plist") throw StructureException()
        if (xml.nextTag() != START_ELEMENT || xml.localName != "dict") throw StructureException()

        eachEntry { key ->
            when {
                key == "Tracks" && xml.localName == "dict" -> {
                    tracksFound = true
                    readTracks()
                }
                key == "Playlists" && xml.localName == "array" -> {
                    playlistsFound = true
                    readPlaylists()
                }
                key == "Library Persistent ID" -> libraryPersistentId = textOrCountMalformed(readValue())?.uppercase()
                key == "Application Version" -> applicationVersion = textOrCountMalformed(readValue())
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
                "Playlist Persistent ID" -> persistentId = textOrCountMalformed(readValue())?.uppercase()
                "Name" -> name = textOrCountMalformed(readValue())
                "Parent Persistent ID" -> parentPersistentId = textOrCountMalformed(readValue())?.uppercase()
                "Master" -> isMaster = flagOrCountMalformed(readValue())
                "Folder" -> isFolder = flagOrCountMalformed(readValue())
                "Distinguished Kind" -> {
                    hasDistinguishedKind = true
                    skipElement()
                }
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
        val resolvedTracks = resolveTracks()
        val playlists = resolvePlaylists(resolvedTracks.refsByTrackId)

        return LibraryReadResult(
            libraryPersistentId = libraryPersistentId,
            tracks = resolvedTracks.tracks,
            unidentifiedTracks = resolvedTracks.unidentifiedTracks,
            playlists = playlists,
            diagnostics = ReadDiagnostics(
                trackCount = resolvedTracks.tracks.size,
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

    private fun resolveTracks(): ResolvedTracks {
        val tracks = mutableListOf<LibraryTrack>()
        val unidentifiedTracks = mutableListOf<UnidentifiedTrack>()
        val seenPersistentIds = HashSet<String>()
        val refsByTrackId = HashMap<String, TrackRef>()

        for ((trackId, values) in rawTracks) {
            val persistentId = textOrCountMalformed(values["Persistent ID"])?.uppercase()?.takeIf(::isPersistentId)
            if (persistentId == null) {
                refsByTrackId[trackId] = TrackRef.Unidentified(unidentifiedTracks.size)
                unidentifiedTracks += UnidentifiedTrack(name = textOrCountMalformed(values["Name"]), artist = textOrCountMalformed(values["Artist"]))
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

        return ResolvedTracks(tracks, unidentifiedTracks, refsByTrackId)
    }

    private fun resolvePlaylists(refsByTrackId: Map<String, TrackRef>): List<LibraryPlaylist> {
        val playlists = mutableListOf<LibraryPlaylist>()
        for (raw in rawPlaylists) {
            if (raw.persistentId == null || !isPersistentId(raw.persistentId)) {
                malformedEntries++
                continue
            }
            val items = raw.trackIds.mapNotNull { id ->
                val ref = refsByTrackId[id.toString()]
                if (ref == null) droppedTrackRefs++
                ref
            }
            playlists += LibraryPlaylist(raw.persistentId, raw.name ?: "", raw.parentPersistentId, raw.kind, items)
        }
        return playlists
    }

    private fun buildTrack(persistentId: String, values: Map<String, Value>): LibraryTrack {
        val trackType = textOrCountMalformed(values["Track Type"])
        val rawLocation = textOrCountMalformed(values["Location"])
        val location = if (rawLocation == null || trackType != FILE_TRACK_TYPE) {
            TrackLocation.Absent
        } else {
            decodeFileUrl(rawLocation)
        }
        return LibraryTrack(
            persistentId = persistentId,
            location = location,
            trackType = trackType,
            name = textOrCountMalformed(values["Name"]),
            artist = textOrCountMalformed(values["Artist"]),
            album = textOrCountMalformed(values["Album"]),
            albumArtist = textOrCountMalformed(values["Album Artist"]),
            trackNumber = integerOrCountMalformed(values["Track Number"]),
            discNumber = integerOrCountMalformed(values["Disc Number"]),
            totalTimeMs = integerOrCountMalformed(values["Total Time"]),
            kind = textOrCountMalformed(values["Kind"]),
            isProtected = flagOrCountMalformed(values["Protected"]),
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
        "true" -> {
            // 終了要素まで読み進める
            xml.elementText
            Value.Bool(true)
        }
        "false" -> {
            // 終了要素まで読み進める
            xml.elementText
            Value.Bool(false)
        }
        else -> {
            skipElement()
            Value.Other
        }
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

    /** 文字列の値を返す。キーが無ければ null。型が違うときは [malformedEntries] に数えて null を返す。 */
    private fun textOrCountMalformed(value: Value?): String? = when (value) {
        null -> null
        is Value.Text -> value.value
        else -> {
            malformedEntries++
            null
        }
    }

    /** 整数の値を返す。キーが無ければ null。型が違う、または整数に読めないときは [malformedEntries] に数えて null を返す。 */
    private fun integerOrCountMalformed(value: Value?): Long? = when (value) {
        null -> null
        is Value.Integer -> {
            if (value.value == null) malformedEntries++
            value.value
        }
        else -> {
            malformedEntries++
            null
        }
    }

    /** 真偽の値を返す。キーが無ければ false。型が違うときは [malformedEntries] に数えて false を返す。 */
    private fun flagOrCountMalformed(value: Value?): Boolean = when (value) {
        null -> false
        is Value.Bool -> value.value
        else -> {
            malformedEntries++
            false
        }
    }
}
