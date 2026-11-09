package playlistsyncfox.itunes.plist

import org.junit.jupiter.api.Tag
import playlistsyncfox.itunes.library.LibraryPlaylist
import playlistsyncfox.itunes.library.LibraryReadError
import playlistsyncfox.itunes.library.LibraryReadOutcome
import playlistsyncfox.itunes.library.LibraryReadResult
import playlistsyncfox.itunes.library.LibraryTrack
import playlistsyncfox.itunes.library.PlaylistExclusion
import playlistsyncfox.itunes.library.PlaylistKind
import playlistsyncfox.itunes.library.TrackRef
import playlistsyncfox.itunes.library.UnidentifiedTrack
import playlistsyncfox.itunes.location.LocationFailure
import playlistsyncfox.itunes.location.TrackLocation
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

private const val ID_A = "AAAAAAAAAAAAAAAA"
private const val ID_B = "BBBBBBBBBBBBBBBB"
private const val ID_C = "CCCCCCCCCCCCCCCC"
private const val PLAYLIST_ID = "1111111111111111"
private const val MASTER_ID = "2222222222222222"

private fun str(key: String, value: String) = "<key>$key</key><string>$value</string>"
private fun int(key: String, value: String) = "<key>$key</key><integer>$value</integer>"
private fun bool(key: String, value: Boolean) = "<key>$key</key><$value/>"
private fun dict(key: String, vararg entries: String) = "<key>$key</key><dict>${entries.joinToString("")}</dict>"

private fun track(trackId: Int, vararg entries: String) = dict(trackId.toString(), *entries)

private fun fileTrack(trackId: Int, persistentId: String?, name: String, vararg more: String): String {
    val entries = listOfNotNull(
        int("Track ID", trackId.toString()),
        persistentId?.let { str("Persistent ID", it) },
        str("Track Type", "File"),
        str("Location", "file://localhost/C:/Music/$name.mp3"),
        str("Name", name),
    ) + more
    return track(trackId, *entries.toTypedArray())
}

private fun items(vararg trackIds: Int) =
    "<key>Playlist Items</key><array>" + trackIds.joinToString("") { "<dict>${int("Track ID", "$it")}</dict>" } + "</array>"

private fun playlist(persistentId: String?, name: String?, vararg more: String): String =
    "<dict>" + listOfNotNull(persistentId?.let { str("Playlist Persistent ID", it) }, name?.let { str("Name", it) }).joinToString("") +
        more.joinToString("") + "</dict>"

private fun document(
    tracks: String?,
    playlists: String?,
    playlistsFirst: Boolean = false,
    header: String = "",
    top: String = "",
): String {
    val tracksPart = tracks?.let { "<key>Tracks</key><dict>$it</dict>" } ?: ""
    val playlistsPart = playlists?.let { "<key>Playlists</key><array>$it</array>" } ?: ""
    val body = if (playlistsFirst) playlistsPart + tracksPart else tracksPart + playlistsPart
    return """<?xml version="1.0" encoding="UTF-8"?>
$header<plist version="1.0"><dict>$top$body</dict></plist>"""
}

private fun read(xml: String): LibraryReadResult = read(xml.toByteArray(Charsets.UTF_8))

private fun read(xml: ByteArray): LibraryReadResult {
    val outcome = readLibrary(xml)
    assertIs<LibraryReadOutcome.Read>(outcome)
    return outcome.result
}

private fun failure(xml: String): LibraryReadError {
    val outcome = readLibrary(xml.toByteArray(Charsets.UTF_8))
    assertIs<LibraryReadOutcome.Failed>(outcome)
    return outcome.error
}

private val standardTracks = fileTrack(1, ID_A, "a") + fileTrack(2, ID_B, "b")

private val standardPlaylists =
    playlist(PLAYLIST_ID, "一覧", items(2, 1, 2)) + playlist(MASTER_ID, "ライブラリ", bool("Master", true), items(1, 2))

// NFD（「ほ」＋結合の濁点）の名前。パスを正規化しないことを確かめるために使う
private const val NFD_NAME = "ほ\u3099"

// iTunes 12.x の実物の形を写した文書。DOCTYPE、CRLF、組み込みのプレイリスト、NFD のパス、文字参照、文書の先頭の項目を持つ
private fun realLibraryDocument(): String {
    val tracks = fileTrack(1, ID_A, "a", bool("Protected", false)).replace(
        "file://localhost/C:/Music/a.mp3", "file://localhost/C:/Music/$NFD_NAME.mp3",
    ) +
        track(2, int("Track ID", "2"), str("Persistent ID", ID_B), str("Track Type", "File"), str("Name", "R &#38; B")) +
        fileTrack(3, ID_C, "c", bool("Protected", false))
    val builtIns = playlist(MASTER_ID, "ライブラリ", bool("Master", true), "<key>Visible</key><false/>", items(1, 2, 3)) +
        listOf(4, 2, 3, 10, 5, 65, 66, 67, 26).joinToString("") {
            playlist("00000000000000$it".takeLast(16), "組み込み $it", int("Distinguished Kind", "$it"), items(1))
        } +
        playlist("3333333333333333", "スマート組み込み", int("Distinguished Kind", "4"), "<key>Smart Info</key><data>AQID</data>")
    val header = """<!DOCTYPE plist PUBLIC "-//Apple Computer//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">""" + "\r\n"
    return document(
        tracks, builtIns + playlist(PLAYLIST_ID, "普通", items(1)) + playlist("4444444444444444", "空の普通の一覧"),
        header = header,
        top = "<key>Application Version</key><string>12.13.7.1</string>" +
            "<key>Date</key><date>2026-11-06T01:02:03Z</date>" + str("Library Persistent ID", "9f8e7d6c5b4a3921"),
    ).replace("\n", "\r\n")
}

class PlistLibraryReaderTest {
    @Test
    fun `全キーを持つ標準の文書を読むと、表のとおりの項目が取れる`() {
        val tracks = track(
            1,
            int("Track ID", "1"),
            str("Persistent ID", ID_A),
            str("Track Type", "File"),
            str("Location", "file://localhost/C:/Music/a.mp3"),
            int("Size", "1234"),
            "<key>Date Modified</key><date>2026-01-02T03:04:05Z</date>",
            int("Total Time", "180000"),
            str("Name", "曲名"),
            str("Artist", "歌手"),
            str("Album", "盤"),
            str("Album Artist", "盤の歌手"),
            int("Track Number", "3"),
            int("Disc Number", "2"),
            str("Kind", "MPEG オーディオファイル"),
            bool("Protected", true),
        )
        val playlists = playlist(
            PLAYLIST_ID, "一覧", str("Parent Persistent ID", MASTER_ID), items(1),
        ) + playlist(MASTER_ID, "ライブラリ", bool("Master", true), items(1))

        val result = read(document(tracks, playlists))

        assertEquals(
            listOf(
                LibraryTrack(
                    persistentId = ID_A,
                    location = TrackLocation.Found("C:\\Music\\a.mp3"),
                    trackType = "File",
                    name = "曲名",
                    artist = "歌手",
                    album = "盤",
                    albumArtist = "盤の歌手",
                    trackNumber = 3,
                    discNumber = 2,
                    totalTimeMs = 180000,
                    kind = "MPEG オーディオファイル",
                    isProtected = true,
                ),
            ),
            result.tracks,
        )
        assertEquals(
            listOf(
                LibraryPlaylist(PLAYLIST_ID, "一覧", MASTER_ID, PlaylistKind.Normal, listOf(TrackRef.Identified(ID_A))),
                LibraryPlaylist(
                    MASTER_ID, "ライブラリ", null, PlaylistKind.Excluded(PlaylistExclusion.Master),
                    listOf(TrackRef.Identified(ID_A)),
                ),
            ),
            result.playlists,
        )
    }

    @Test
    fun `Persistent ID と Location と Name と Playlist Items を欠く文書を読むと、補わずに空で持つ`() {
        val tracks = fileTrack(1, ID_A, "a") +
            track(2, int("Track ID", "2"), str("Persistent ID", ID_B), str("Track Type", "File")) +
            fileTrack(3, null, "名無しの ID")
        val playlists = playlist(PLAYLIST_ID, null)

        val result = read(document(tracks, playlists))

        assertEquals(listOf(UnidentifiedTrack(name = "名無しの ID", artist = null)), result.unidentifiedTracks)
        val withoutLocation = result.tracks.single { it.persistentId == ID_B }
        assertEquals(TrackLocation.Absent, withoutLocation.location)
        assertNull(withoutLocation.name)
        assertEquals(LibraryPlaylist(PLAYLIST_ID, "", null, PlaylistKind.Normal, emptyList()), result.playlists.single())
    }

    @Test
    fun `トラックとプレイリストに入れ子の未知のキーを足すと、標準と同じ結果になる`() {
        val unknown = "<key>Unknown</key><dict><key>x</key><array><dict><key>y</key><integer>1</integer></dict></array></dict>" +
            "<key>Other</key><array><string>z</string></array>"
        val withUnknownTracks = fileTrack(1, ID_A, "a", unknown) + fileTrack(2, ID_B, "b", unknown)
        val withUnknownPlaylists = playlist(PLAYLIST_ID, "一覧", unknown, items(2, 1, 2)) +
            playlist(MASTER_ID, "ライブラリ", bool("Master", true), unknown, items(1, 2))

        assertEquals(
            read(document(standardTracks, standardPlaylists)),
            read(document(withUnknownTracks, withUnknownPlaylists)),
        )
    }

    @Test
    fun `スマートプレイリストを読むと、条件は読まず展開済みの並びだけを使う`() {
        val smart = playlist(
            PLAYLIST_ID, "スマート",
            "<key>Smart Info</key><data>AQID</data>",
            "<key>Smart Criteria</key><data>BAUG</data>",
            items(2, 1),
        )

        val playlist = read(document(standardTracks, smart)).playlists.single()

        assertEquals(PlaylistKind.Normal, playlist.kind)
        assertEquals(listOf(TrackRef.Identified(ID_B), TrackRef.Identified(ID_A)), playlist.items)
    }

    @Test
    fun `Tracks に無い Track ID を含む並びを読むと、その参照を落として件数を数える`() {
        val result = read(document(standardTracks, playlist(PLAYLIST_ID, "一覧", items(1, 99, 2))))

        assertEquals(listOf(TrackRef.Identified(ID_A), TrackRef.Identified(ID_B)), result.playlists.single().items)
        assertEquals(1, result.diagnostics.droppedTrackRefs)
    }

    @Test
    fun `Persistent ID が重複するトラックを読むと、先のトラックを残して件数を数える`() {
        val tracks = fileTrack(1, ID_A, "先") + fileTrack(2, ID_A, "後")

        val result = read(document(tracks, playlist(PLAYLIST_ID, "一覧")))

        assertEquals(listOf("先"), result.tracks.map { it.name })
        assertEquals(1, result.diagnostics.duplicatePersistentIds)
    }

    @Test
    fun `TS-170 重複で落としたトラックを参照する並びを読むと、残したトラックへの参照として扱う`() {
        val tracks = fileTrack(1, ID_A, "先") + fileTrack(2, ID_A, "後")

        val result = read(document(tracks, playlist(PLAYLIST_ID, "一覧", items(1, 2))))

        assertEquals(listOf(TrackRef.Identified(ID_A), TrackRef.Identified(ID_A)), result.playlists.single().items)
        assertEquals(0, result.diagnostics.droppedTrackRefs)
    }

    @Test
    fun `同じ曲を 2 回含む並びを読むと、2 回とも残る`() {
        val result = read(document(standardTracks, playlist(PLAYLIST_ID, "一覧", items(1, 2, 1))))

        assertEquals(
            listOf(TrackRef.Identified(ID_A), TrackRef.Identified(ID_B), TrackRef.Identified(ID_A)),
            result.playlists.single().items,
        )
    }

    @Test
    fun `Playlists が Tracks より前の文書を読むと、標準と同じ結果になる`() {
        assertEquals(
            read(document(standardTracks, standardPlaylists)),
            read(document(standardTracks, standardPlaylists, playlistsFirst = true)),
        )
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、Library Persistent ID が大文字で取れる`() {
        assertEquals("9F8E7D6C5B4A3921", read(realLibraryDocument()).libraryPersistentId)
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、普通の一覧だけが除外の印なしで残る`() {
        val result = read(realLibraryDocument())

        assertEquals(
            listOf("普通", "空の普通の一覧"),
            result.playlists.filter { it.kind == PlaylistKind.Normal }.map { it.name },
        )
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、NFD のパスは変わらない`() {
        val result = read(realLibraryDocument())

        assertEquals(TrackLocation.Found("C:\\Music\\$NFD_NAME.mp3"), result.tracks.first().location)
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、名前の文字参照が戻る`() {
        val result = read(realLibraryDocument())

        assertEquals("R & B", result.tracks.single { it.persistentId == ID_B }.name)
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、Application Version が診断に入る`() {
        assertEquals("12.13.7.1", read(realLibraryDocument()).diagnostics.applicationVersion)
    }

    @Test
    fun `TS-86 実物の形を写した文書を読むと、Date が診断に入る`() {
        assertEquals(Instant.parse("2026-11-06T01:02:03Z"), read(realLibraryDocument()).diagnostics.xmlDate)
    }

    @Test
    fun `TS-178 Folder が真のプレイリストを読むと、Folder の除外の印が付く`() {
        val folder = playlist(PLAYLIST_ID, "フォルダ", bool("Folder", true))

        val result = read(document(standardTracks, folder))

        assertEquals(PlaylistKind.Excluded(PlaylistExclusion.Folder), result.playlists.single().kind)
    }

    @Test
    fun `TS-179 除外の条件が重なるプレイリストを読むと、Master、Distinguished Kind、Folder の順に優先して印が付く`() {
        val allThree = playlist(PLAYLIST_ID, "三つ", bool("Master", true), int("Distinguished Kind", "4"), bool("Folder", true))
        val kindAndFolder = playlist(MASTER_ID, "種別とフォルダ", int("Distinguished Kind", "4"), bool("Folder", true))

        val result = read(document(standardTracks, allThree + kindAndFolder))

        assertEquals(
            listOf(PlaylistKind.Excluded(PlaylistExclusion.Master), PlaylistKind.Excluded(PlaylistExclusion.DistinguishedKind)),
            result.playlists.map { it.kind },
        )
    }

    @Test
    fun `Persistent ID が小文字の 16 桁のトラックを読むと、大文字に揃う`() {
        val result = read(document(fileTrack(1, "abcdef0123456789", "a"), playlist(PLAYLIST_ID, "一覧")))

        assertEquals("ABCDEF0123456789", result.tracks.single().persistentId)
    }

    @Test
    fun `Persistent ID が 15 桁と 17 桁と非 16 進のトラックを読むと、ID 無しになる`() {
        val tracks = fileTrack(1, "ABCDEF012345678", "十五") +
            fileTrack(2, "ABCDEF01234567890", "十七") +
            fileTrack(3, "GBCDEF0123456789", "非十六進")

        val result = read(document(tracks, playlist(PLAYLIST_ID, "一覧")))

        assertEquals(emptyList(), result.tracks)
        assertEquals(listOf("十五", "十七", "非十六進"), result.unidentifiedTracks.map { it.name })
    }

    @Test
    fun `TS-167 Track Type が File でないトラックを読むと、Location があっても場所なしになる`() {
        val tracks = track(
            1,
            int("Track ID", "1"),
            str("Persistent ID", ID_A),
            str("Track Type", "URL"),
            str("Location", "file://localhost/C:/Music/a.mp3"),
        )

        val track = read(document(tracks, playlist(PLAYLIST_ID, "一覧"))).tracks.single()

        assertEquals(TrackLocation.Absent, track.location)
        assertEquals("URL", track.trackType)
    }

    @Test
    fun `TS-169 Playlist Persistent ID が無いプレイリストを読むと、落として不正な項目に数える`() {
        val playlists = playlist(null, "ID なし", items(1)) + playlist(PLAYLIST_ID, "一覧")

        val result = read(document(standardTracks, playlists))

        assertEquals(listOf("一覧"), result.playlists.map { it.name })
        assertEquals(1, result.diagnostics.malformedEntries)
    }

    @Test
    fun `TS-169 Playlist Persistent ID が 15 桁と 17 桁と非 16 進のプレイリストを読むと、落として不正な項目に数える`() {
        val playlists = playlist("111111111111111", "十五") +
            playlist("11111111111111111", "十七") +
            playlist("G111111111111111", "非十六進") +
            playlist(PLAYLIST_ID, "一覧")

        val result = read(document(standardTracks, playlists))

        assertEquals(listOf("一覧"), result.playlists.map { it.name })
        assertEquals(3, result.diagnostics.malformedEntries)
        assertEquals(1, result.diagnostics.playlistCount)
    }

    @Test
    fun `TS-169 Playlist Persistent ID が小文字の 16 桁のプレイリストを読むと、大文字に揃う`() {
        val result = read(document(standardTracks, playlist("abcdef0123456789", "一覧")))

        assertEquals("ABCDEF0123456789", result.playlists.single().persistentId)
    }

    @Test
    fun `TS-171 Track ID が整数でない並びの要素を読むと、不正な項目を 1 回だけ数える`() {
        val brokenItem = "<key>Playlist Items</key><array><dict><key>Track ID</key><integer>x</integer></dict></array>"

        val result = read(document(standardTracks, playlist(PLAYLIST_ID, "一覧", brokenItem)))

        assertTrue(result.playlists.single().items.isEmpty())
        assertEquals(1, result.diagnostics.malformedEntries)
    }

    @Test
    fun `TS-172 重複で落としたトラックと ID 無しのトラックと ID の無いプレイリストを含む文書を読むと、診断の件数は残した分だけになる`() {
        val tracks = fileTrack(1, ID_A, "先") + fileTrack(2, ID_A, "後") + fileTrack(3, null, "ID 無し") + fileTrack(4, ID_B, "b")
        val playlists = playlist(null, "ID なし") + playlist(PLAYLIST_ID, "一覧")

        val diagnostics = read(document(tracks, playlists)).diagnostics

        assertEquals(2, diagnostics.trackCount)
        assertEquals(1, diagnostics.playlistCount)
    }

    @Test
    fun `integer が 64 ビットを超えるとき、その項目は空になる`() {
        val tracks = fileTrack(1, ID_A, "a", int("Total Time", "9223372036854775808"), int("Track Number", "7"))

        val track = read(document(tracks, playlist(PLAYLIST_ID, "一覧"))).tracks.single()

        assertNull(track.totalTimeMs)
        assertEquals(7, track.trackNumber)
    }

    @Test
    fun `date が日付でないとき、その項目は空になる`() {
        val xml = document(standardTracks, playlist(PLAYLIST_ID, "一覧"), top = "<key>Date</key><date>日付ではない</date>")

        assertNull(read(xml).diagnostics.xmlDate)
    }

    @Test
    fun `終了タグの無い文書を読むと、構文の不正になる`() {
        val unclosed = document(standardTracks, standardPlaylists).substringBefore("<key>Playlists</key>")

        assertEquals(LibraryReadError.InvalidSyntax, failure(unclosed))
    }

    @Test
    fun `0 バイトの文書を読むと、構文の不正になる`() {
        assertEquals(LibraryReadError.InvalidSyntax, failure(""))
    }

    @Test
    fun `BOM 付きの文書を読むと、標準と同じ結果になる`() {
        val xml = document(standardTracks, standardPlaylists).toByteArray(Charsets.UTF_8)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

        assertEquals(read(xml), read(bom + xml))
    }

    @Test
    fun `外部の DTD を指す DOCTYPE の文書を読むと、取りに行かず成功する`() {
        // 取りに行けば example.invalid の名前解決に失敗する
        val header = """<!DOCTYPE plist PUBLIC "-//Example//DTD//EN" "http://example.invalid/plist.dtd">""" + "\n"

        val result = read(document(standardTracks, standardPlaylists, header = header))

        assertEquals(2, result.tracks.size)
    }

    @Test
    fun `名前と Location に文字参照を含む文書を読むと、戻した後の文字列で復号する`() {
        val tracks = track(
            1,
            str("Persistent ID", ID_A),
            str("Track Type", "File"),
            str("Name", "A &amp; B &#38; C &lt; D"),
            str("Location", "file://localhost/C:/Music/x%20&amp;%20y&#38;z.mp3"),
        )

        val track = read(document(tracks, playlist(PLAYLIST_ID, "一覧"))).tracks.single()

        assertEquals("A & B & C < D", track.name)
        assertEquals(TrackLocation.Found("C:\\Music\\x & y&z.mp3"), track.location)
    }

    @Test
    fun `取らないキーの値に深さ 50 の入れ子があるとき、読み捨てて後続のキーを読める`() {
        val deep = "<array>".repeat(50) + "<string>x</string>" + "</array>".repeat(50)
        val tracks = fileTrack(1, ID_A, "a", "<key>Deep</key>$deep", str("Album", "後続"))

        val track = read(document(tracks, playlist(PLAYLIST_ID, "一覧"))).tracks.single()

        assertEquals("後続", track.album)
    }

    @Test
    fun `Tracks が無い文書を読むと、構造の違いになる`() {
        assertEquals(LibraryReadError.InvalidStructure, failure(document(null, standardPlaylists)))
    }

    @Test
    fun `Playlists が無い文書を読むと、構造の違いになる`() {
        assertEquals(LibraryReadError.InvalidStructure, failure(document(standardTracks, null)))
    }

    @Test
    fun `最上位が dict でない文書を読むと、構造の違いになる`() {
        assertEquals(LibraryReadError.InvalidStructure, failure("""<plist version="1.0"><array></array></plist>"""))
    }

    @Test
    fun `ID 無しのトラックが混ざる並びを読むと、識別なしの参照が元の位置に残り位置の番号が一致する`() {
        val tracks = fileTrack(1, ID_A, "a") + fileTrack(2, null, "無一") + fileTrack(3, ID_B, "b") + fileTrack(4, null, "無二")

        val result = read(document(tracks, playlist(PLAYLIST_ID, "一覧", items(4, 1, 2, 3))))

        assertEquals(
            listOf(TrackRef.Unidentified(1), TrackRef.Identified(ID_A), TrackRef.Unidentified(0), TrackRef.Identified(ID_B)),
            result.playlists.single().items,
        )
        assertEquals(listOf("無一", "無二"), result.unidentifiedTracks.map { it.name })
    }

    @Test
    fun `復号不能の Location を持つトラックを読むと、細目ごとの件数に数える`() {
        val tracks = fileTrack(1, ID_A, "a").replace("file://localhost/C:/Music/a.mp3", "http://example.invalid/a.mp3")

        val result = read(document(tracks, playlist(PLAYLIST_ID, "一覧")))

        assertEquals(mapOf(LocationFailure.InvalidFormat to 1), result.diagnostics.locationFailures)
    }

    // 壁時計のしきい値を持つため、通常の test から外す。`./gradlew :itunes:performanceTest` で回す
    @Tag("performance")
    @Test
    fun `数万トラックとプレイリスト数百本の文書を読むと、目標の時間内に完了する`() {
        val trackCount = 30_000
        val tracks = StringBuilder()
        for (n in 1..trackCount) {
            tracks.append(fileTrack(n, "%016X".format(n), "t$n", str("Artist", "歌手"), int("Total Time", "1000")))
        }
        val playlists = StringBuilder()
        for (p in 1..300) {
            playlists.append(playlist("%016X".format(p + 0x100000L), "p$p", items(*IntArray(100) { (p * 97 + it) % trackCount + 1 })))
        }
        val xml = document(tracks.toString(), playlists.toString()).toByteArray(Charsets.UTF_8)

        val elapsed = measureTime { read(xml) }

        // 目標は 5 秒（V7）。手元の機械差に余裕を持たせて 2 倍
        assertTrue(elapsed < 10.seconds, "所要: $elapsed")
    }
}
