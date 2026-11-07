package playlistsyncfox.desktop.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.readByte
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import playlistsyncfox.desktop.DesktopValues
import playlistsyncfox.desktop.settings.LastSync
import playlistsyncfox.shared.auth.SignatureHeaderNames
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TRACK_BYTES = ByteArray(100) { it.toByte() }
private const val RAW_PATHS_MANIFEST = "/v1/manifest"

class ServerRoutesTest {
    private val dir: Path = createTempDirectory("psfox-server")
    private val fixture = ServerFixture(dir).also { it.addDevice() }
    private val trackFile = dir.resolve("track.mp3").also { it.writeBytes(TRACK_BYTES) }

    private val trackModified get() = Files.getLastModifiedTime(trackFile).toMillis()

    private fun trackUri(id: String = TRACK_ID, size: Long = TRACK_BYTES.size.toLong(), modified: Long = trackModified) =
        "/v1/tracks/$id?size=$size&modified_at=$modified"

    private suspend fun io.ktor.client.HttpClient.manifest() = signed(fixture, HttpMethod.Post, RAW_PATHS_MANIFEST, SELECTION)

    private suspend fun io.ktor.client.HttpClient.badSignature(count: Int) = repeat(count) {
        signed(fixture, HttpMethod.Post, RAW_PATHS_MANIFEST, SELECTION, key = ByteArray(32) { 9 })
    }

    // ---- 要求の検査の順（TD-01〜TD-04） ----

    @Test
    fun `TD-01 署名ヘッダが無い要求は署名が要るルートのどれでも 400 で E を付けず、失敗として数えない`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)
        val unsigned = listOf(
            client.get("/v1/manifest"),
            client.get("/v1/digest"),
            client.get(trackUri()),
            client.postJson("/v1/reports", "{}"),
        )

        assertEquals(List(4) { HttpStatusCode.BadRequest }, unsigned.map { it.status })
        assertTrue(unsigned.all { it.bodyAsText().isEmpty() })
        // 400 は署名失敗に数えない。繰り返しても 429 にならない
        repeat(DesktopValues.SIGNATURE_FAILURE_LIMIT) { client.get("/v1/manifest") }
        assertEquals(HttpStatusCode.OK, client.manifest().status)
    }

    @Test
    fun `TD-01 タイムスタンプが 10 進の整数でない要求は 400`() = withServer(fixture) { client ->
        val response = client.signed(fixture, HttpMethod.Post, "/v1/digest", SELECTION) { headers.remove("X-Psfox-Timestamp"); header("X-Psfox-Timestamp", "12ab") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `TD-02 未登録の端末は時刻が許容の内なら 401 で detail を付けない`() = withServer(fixture) { client ->
        val response = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION, device = OTHER_DEVICE)

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("""{"error":"E2"}""", response.bodyAsText())
    }

    @Test
    fun `TD-02 署名の誤りは未登録の端末と同じ応答になる`() = withServer(fixture) { client ->
        val unregistered = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION, device = OTHER_DEVICE).bodyAsText()
        val wrongKey = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION, key = ByteArray(32) { 9 }).bodyAsText()

        assertEquals(wrongKey, unregistered)
    }

    @Test
    fun `TD-02 未登録の端末も時刻が許容の外なら登録済みと同じ clock_skew`() = withServer(fixture) { client ->
        val late = fixture.clock.instant().epochSecond - 301

        val unregistered = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION, device = OTHER_DEVICE, timestamp = late)
        val registered = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION, timestamp = late)

        assertEquals(HttpStatusCode.Unauthorized, unregistered.status)
        assertEquals("clock_skew", unregistered.detail())
        assertEquals(registered.bodyAsText(), unregistered.bodyAsText())
    }

    @Test
    fun `TD-03 本文が V31 を超え署名も無い要求は、署名の検査でなく 413 になる`() = withServer(fixture) { client ->
        val response = client.postJson("/v1/reports", "x".repeat(DesktopValues.MAX_BODY_BYTES + 1))

        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().isEmpty())
    }

    @Test
    fun `TD-03 署名失敗の待ち中で署名も無い要求は、ヘッダの検査でなく 429 になる`() = withServer(fixture) { client ->
        client.badSignature(DesktopValues.SIGNATURE_FAILURE_LIMIT)

        val response = client.get("/v1/manifest")

        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("\"E2\"", response.json().getValue("error").toString())
    }

    @Test
    fun `TD-04 本文が V31 ちょうどなら次の検査へ進む`() = withServer(fixture) { client ->
        val response = client.postJson("/v1/reports", "x".repeat(DesktopValues.MAX_BODY_BYTES))

        // 署名ヘッダが無いので、413 でなくヘッダの検査の 400
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `TD-04 本文が V31 を 1 バイト超えると 413`() = withServer(fixture) { client ->
        assertEquals(HttpStatusCode.PayloadTooLarge, client.postJson("/v1/reports", "x".repeat(DesktopValues.MAX_BODY_BYTES + 1)).status)
    }

    @Test
    fun `知らないパスは 404 で E を付けない`() = withServer(fixture) { client ->
        val response = client.get("/v1/unknown")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().isEmpty())
    }

    // ---- 署名失敗の数え方（TD-05〜TD-07、TD-55） ----

    @Test
    fun `TD-05 署名の失敗が V43 回に満たなければ、続く正しい要求は通る`() = withServer(fixture) { client ->
        client.badSignature(DesktopValues.SIGNATURE_FAILURE_LIMIT - 1)

        // 429 でなく通常の検査へ進む（XML が無いため 503）
        assertEquals(HttpStatusCode.ServiceUnavailable, client.manifest().status)
    }

    @Test
    fun `TD-05 署名の失敗が V43 回に達した次の要求から 429`() = withServer(fixture) { client ->
        client.badSignature(DesktopValues.SIGNATURE_FAILURE_LIMIT)

        assertEquals(HttpStatusCode.TooManyRequests, client.manifest().status)
    }

    @Test
    fun `TD-06 429 は V32 の直前まで続き、経過後は通常の検査へ戻る`() = withServer(fixture) { client ->
        client.badSignature(DesktopValues.SIGNATURE_FAILURE_LIMIT)

        fixture.elapse(DesktopValues.SIGNATURE_BLOCK.minusSeconds(1))
        assertEquals(HttpStatusCode.TooManyRequests, client.manifest().status)

        fixture.elapse(Duration.ofSeconds(1))
        // 通常の検査へ戻る（時刻の許容の内なので署名は通り、XML が無いため 503）
        assertEquals(HttpStatusCode.ServiceUnavailable, client.manifest().status)
    }

    @Test
    fun `TD-07 署名の失敗と P5 を合わせて V43 回で 429`() = withServer(fixture) { client ->
        client.badSignature(5)
        repeat(DesktopValues.SIGNATURE_FAILURE_LIMIT - 5) { client.postJson("/v1/pairing/start", "{}") }

        assertEquals(HttpStatusCode.TooManyRequests, client.manifest().status)
        assertEquals(HttpStatusCode.TooManyRequests, client.postJson("/v1/pairing/start", "{}").status)
    }

    // ---- P1・P3 ----

    @Test
    fun `TD-50 読めない状態の P1 と P3 は 503 の E4`() = withServer(fixture) { client ->
        val p1 = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION)
        val p3 = client.signed(fixture, HttpMethod.Post, "/v1/digest", SELECTION)

        assertEquals(HttpStatusCode.ServiceUnavailable, p1.status)
        assertEquals("""{"error":"E4"}""", p1.bodyAsText())
        assertEquals(HttpStatusCode.ServiceUnavailable, p3.status)
        assertEquals("""{"error":"E4"}""", p3.bodyAsText())
    }

    @Test
    fun `TD-14 P1 は gzip を受けるときだけ圧縮する`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)

        val gzip = client.signed(fixture, HttpMethod.Post, "/v1/manifest", SELECTION) { header(HttpHeaders.AcceptEncoding, "gzip") }
        val plain = client.manifest()

        assertEquals("gzip", gzip.headers[HttpHeaders.ContentEncoding])
        assertNull(plain.headers[HttpHeaders.ContentEncoding])
        assertTrue(plain.bodyAsText().contains("content_digest"))
    }

    @Test
    fun `TD-14 P2 は gzip を受けるヘッダがあっても圧縮しない`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)
        client.manifest()

        val response = client.signed(fixture, HttpMethod.Get, trackUri()) { header(HttpHeaders.AcceptEncoding, "gzip") }

        assertNull(response.headers[HttpHeaders.ContentEncoding])
        assertContentEquals(TRACK_BYTES, response.readRawBytes())
    }

    // ---- P2（TD-08、TD-10〜TD-13、TD-46、TD-59） ----

    private suspend fun io.ktor.client.HttpClient.prepareTable() {
        fixture.useLibrary(trackFile)
        assertEquals(HttpStatusCode.OK, manifest().status)
    }

    @Test
    fun `TD-08 16 桁の ID は P2 の処理へ進み、ヘッダに大きさと更新日時が付く`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.signed(fixture, HttpMethod.Get, trackUri())

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(TRACK_BYTES, response.readRawBytes())
        assertEquals("100", response.headers["X-Psfox-Size"])
        assertEquals(trackModified.toString(), response.headers["X-Psfox-Modified-At"])
        assertEquals("bytes", response.headers[HttpHeaders.AcceptRanges])
    }

    @Test
    fun `TD-08 15 桁・ドットを含む・バックスラッシュを含む ID は 400 でファイルを開かない`() = withServer(fixture) { client ->
        client.prepareTable()

        val statuses = listOf("100000000000001", "....AAAA....BBBB", "AAAAAAAA%5CAAAAAA").map {
            client.signed(fixture, HttpMethod.Get, trackUri(id = it)).status
        }

        assertEquals(List(3) { HttpStatusCode.BadRequest }, statuses)
    }

    @Test
    fun `TD-08 スラッシュを含む ID はルートに当たらず 404 で E を付けない`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.signed(fixture, HttpMethod.Get, "/v1/tracks/1000000000/000001?size=100&modified_at=1")

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().isEmpty())
    }

    @Test
    fun `TD-10 曲の表に無い ID は 404 の E5`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.signed(fixture, HttpMethod.Get, trackUri(id = "1000000000000009"))

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"E5"}""", response.bodyAsText())
    }

    @Test
    fun `TD-10 表にあって実ファイルが無ければ 404 の E5`() = withServer(fixture) { client ->
        client.prepareTable()
        val query = trackUri()
        Files.delete(trackFile)

        val response = client.signed(fixture, HttpMethod.Get, query)

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"E5"}""", response.bodyAsText())
    }

    @Test
    fun `TD-10 曲の表が空（P1 より前や再起動後）のときは 404 でなく 503 の E4`() = withServer(fixture) { client ->
        val response = client.signed(fixture, HttpMethod.Get, trackUri())

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("""{"error":"E4"}""", response.bodyAsText())
    }

    @Test
    fun `TD-11 問い合わせの size か modified_at が曲の表と違えば 409 の E8 で本文を送らない`() = withServer(fixture) { client ->
        client.prepareTable()

        val wrongSize = client.signed(fixture, HttpMethod.Get, trackUri(size = 99))
        val wrongTime = client.signed(fixture, HttpMethod.Get, trackUri(modified = trackModified + 1))

        for (response in listOf(wrongSize, wrongTime)) {
            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("""{"error":"E8"}""", response.bodyAsText())
        }
    }

    @Test
    fun `TD-11 実ファイルの大きさが表の size と違えば 409 の E8`() = withServer(fixture) { client ->
        client.prepareTable()
        val query = trackUri()
        trackFile.writeBytes(ByteArray(101))

        val response = client.signed(fixture, HttpMethod.Get, query)

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals("""{"error":"E8"}""", response.bodyAsText())
    }

    @Test
    fun `TD-12 Range の始まりが大きさと同じなら 416`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.signed(fixture, HttpMethod.Get, trackUri()) { header(HttpHeaders.Range, "bytes=100-") }

        assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, response.status)
    }

    private suspend fun io.ktor.client.HttpClient.range(spec: String): HttpResponse =
        signed(fixture, HttpMethod.Get, trackUri()) { header(HttpHeaders.Range, spec) }

    @Test
    fun `TD-13 先頭の範囲は 206 と Content-Range で、本文が範囲と一致する`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.range("bytes=0-9")

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 0-9/100", response.headers[HttpHeaders.ContentRange])
        assertContentEquals(TRACK_BYTES.copyOfRange(0, 10), response.readRawBytes())
    }

    @Test
    fun `TD-13 途中の範囲は 206 で、本文が範囲と一致する`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.range("bytes=40-49")

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertContentEquals(TRACK_BYTES.copyOfRange(40, 50), response.readRawBytes())
    }

    @Test
    fun `TD-13 末尾（大きさ - 1 まで）の範囲は 206 で、本文が範囲と一致する`() = withServer(fixture) { client ->
        client.prepareTable()

        val response = client.range("bytes=95-")

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 95-99/100", response.headers[HttpHeaders.ContentRange])
        assertContentEquals(TRACK_BYTES.copyOfRange(95, 100), response.readRawBytes())
    }

    @Test
    fun `TD-46 取得の間に XML が読めなくなっても、P1 で作った曲の表で P2 を引ける`() = withServer(fixture) { client ->
        client.prepareTable()
        fixture.library = null

        val response = client.signed(fixture, HttpMethod.Get, trackUri())

        assertEquals(HttpStatusCode.OK, response.status)
        assertContentEquals(TRACK_BYTES, response.readRawBytes())
    }

    @Test
    fun `TD-59 P3 は content_digest だけを返し、再計測の値を反映する`() = withServer(fixture) { client ->
        client.prepareTable()
        val p1Digest = fixture.manifests.forManifest(listOf(PLAYLIST_ID))!!.contentDigest
        trackFile.writeBytes(ByteArray(150))

        val response = client.signed(fixture, HttpMethod.Post, "/v1/digest", SELECTION)

        assertEquals(setOf("content_digest"), response.json().keys)
        assertTrue(response.json().getValue("content_digest").toString().trim('"') != p1Digest)
    }

    @Test
    fun `TD-59 P3 の後でも P2 の曲の表は P1 のままで、P1 の値のまま P2 が通る`() = withServer(fixture) { client ->
        client.prepareTable()
        val query = trackUri()
        trackFile.writeBytes(ByteArray(150))
        client.signed(fixture, HttpMethod.Post, "/v1/digest", SELECTION)
        // 実ファイルを P1 の時の大きさに戻す。P3 の再計測の値が表を置き換えていれば、P1 の size では 409 になる
        trackFile.writeBytes(TRACK_BYTES)
        Files.setLastModifiedTime(trackFile, java.nio.file.attribute.FileTime.fromMillis(trackModified))

        val response = client.signed(fixture, HttpMethod.Get, query)

        assertEquals(HttpStatusCode.OK, response.status)
    }

    // ---- 選択（TD-78〜TD-80） ----

    private fun ids(vararg id: String) = """{"playlist_ids":[${id.joinToString(",") { "\"$it\"" }}]}""".encodeToByteArray()

    @Test
    fun `TD-78 P1 は本文の ID の曲だけを配り、選んでいない曲は P2 で 404`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)

        val selected = client.signed(fixture, HttpMethod.Post, "/v1/manifest", ids(PLAYLIST_ID))
        assertTrue(selected.bodyAsText().contains(TRACK_ID))
        assertEquals(HttpStatusCode.OK, client.signed(fixture, HttpMethod.Get, trackUri()).status)

        val none = client.signed(fixture, HttpMethod.Post, "/v1/manifest", ids())
        assertEquals(HttpStatusCode.OK, none.status)
        assertTrue(!none.bodyAsText().contains(TRACK_ID))
        val response = client.signed(fixture, HttpMethod.Get, trackUri())
        assertEquals(HttpStatusCode.NotFound, response.status)
        assertEquals("""{"error":"E5"}""", response.bodyAsText())
    }

    @Test
    fun `TD-79 P1 と P3 の本文の形と ID の誤りは 400 で、空の配列と上限ちょうどは通る`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)
        val bad = listOf(
            "not json", "{}", """{"playlist_ids":"x"}""", """{"playlist_ids":[1]}""",
            """{"playlist_ids":["a000000000000001"]}""", """{"playlist_ids":["${PLAYLIST_ID.dropLast(1)}"]}""",
        )
        for (path in listOf("/v1/manifest", "/v1/digest")) {
            for (body in bad) {
                assertEquals(HttpStatusCode.BadRequest, client.signed(fixture, HttpMethod.Post, path, body.encodeToByteArray()).status, "$path $body")
            }
            assertEquals(HttpStatusCode.OK, client.signed(fixture, HttpMethod.Post, path, ids()).status, path)
        }
        val atLimit = ids(*Array(DesktopValues.MAX_PLAYLIST_IDS) { "%016X".format(it) })
        val overLimit = ids(*Array(DesktopValues.MAX_PLAYLIST_IDS + 1) { "%016X".format(it) })
        assertEquals(HttpStatusCode.OK, client.signed(fixture, HttpMethod.Post, "/v1/digest", atLimit).status)
        assertEquals(HttpStatusCode.BadRequest, client.signed(fixture, HttpMethod.Post, "/v1/digest", overLimit).status)
    }

    @Test
    fun `TD-79 署名の後に本文を差し替えた P1 は 401`() = withServer(fixture) { client ->
        fixture.useLibrary(trackFile)
        val headers = sign(fixture, HttpMethod.Post, "/v1/manifest", ids(PLAYLIST_ID))

        val response = client.request("/v1/manifest") {
            method = HttpMethod.Post
            header(SignatureHeaderNames.DEVICE, headers.device)
            header(SignatureHeaderNames.TIMESTAMP, headers.timestamp)
            header(SignatureHeaderNames.NONCE, headers.nonce)
            header(SignatureHeaderNames.SIGNATURE, headers.signature)
            setBody(ids())
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `TD-80 P7 は木を返し、読めない状態は 503 の E4、曲の表は置き換えない`() = withServer(fixture) { client ->
        assertEquals(HttpStatusCode.ServiceUnavailable, client.signed(fixture, HttpMethod.Get, "/v1/playlists").status)

        client.prepareTable()
        val response = client.signed(fixture, HttpMethod.Get, "/v1/playlists")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(PLAYLIST_ID))
        // P1 の表のまま P2 が通る
        assertEquals(HttpStatusCode.OK, client.signed(fixture, HttpMethod.Get, trackUri()).status)
    }

    // ---- P4（TD-60） ----

    private val report = """
        {"started_at":"2026-01-01T00:00:00.000Z","finished_at":"2026-01-01T00:01:00.000Z","outcome":"success","has_more":false,
         "counts":{"to_add":1,"to_replace":0,"to_remove":0,"unchanged":2,"added":1,"replaced":0,"removed":0,"failed":0},
         "capacity":{"diff_bytes":100,"bytes_transferred":100},"failure_counts":{"E5":1},"excluded_count":3}
    """.trimIndent()

    private fun lastSync(): LastSync? = fixture.settings.current.devices.single().lastSync

    @Test
    fun `TD-60 8 項目がそろう報告は全項目を last_sync に保存して 204`() = withServer(fixture) { client ->
        val response = client.signed(fixture, HttpMethod.Post, "/v1/reports", report.encodeToByteArray())

        assertEquals(HttpStatusCode.NoContent, response.status)
        val saved = lastSync()!!
        assertEquals(3, saved.excludedCount)
        assertEquals(mapOf("E5" to 1), saved.failureCounts)
        assertEquals(100, saved.capacity.bytesTransferred)
    }

    @Test
    fun `TD-60 必須項目が 1 つ欠けた報告は 400 で保存しない`() = withServer(fixture) { client ->
        val missing = report.replace(""""excluded_count":3""", """"unrelated":3""")

        val response = client.signed(fixture, HttpMethod.Post, "/v1/reports", missing.encodeToByteArray())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(lastSync())
    }

    @Test
    fun `TD-60 型の違う報告は 400 で保存しない`() = withServer(fixture) { client ->
        val wrongType = report.replace(""""has_more":false""", """"has_more":"no"""")

        val response = client.signed(fixture, HttpMethod.Post, "/v1/reports", wrongType.encodeToByteArray())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(lastSync())
    }

    @Test
    fun `TD-60 outcome が 4 値以外の報告は 400 で保存しない`() = withServer(fixture) { client ->
        val notStarted = report.replace(""""outcome":"success"""", """"outcome":"not_started"""")

        val response = client.signed(fixture, HttpMethod.Post, "/v1/reports", notStarted.encodeToByteArray())

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(lastSync())
    }

    // ---- P8 と送信中の曲（TD-82、TD-83） ----

    private val progressBody = """{"stage":"transfer","done_tracks":9,"total_tracks":17,"done_bytes":100,"total_bytes":200}"""

    @Test
    fun `TD-82 正しい P8 は 204 で progress に入り、範囲外の stage と負の数と JSON でない本文は 400 で変えない`() = withServer(fixture) { client ->
        assertEquals(HttpStatusCode.NoContent, client.signed(fixture, HttpMethod.Post, "/v1/progress", progressBody.encodeToByteArray()).status)
        val saved = fixture.progress.current()
        assertEquals(9, saved?.doneTracks)

        val bad = listOf(
            progressBody.replace("transfer", "finished"),
            progressBody.replace(""""done_tracks":9""", """"done_tracks":-1"""),
            "これは JSON ではない",
        )
        for (body in bad) {
            assertEquals(HttpStatusCode.BadRequest, client.signed(fixture, HttpMethod.Post, "/v1/progress", body.encodeToByteArray()).status)
            assertEquals(saved, fixture.progress.current())
        }
    }

    @Test
    fun `TD-82 P4 を受けると progress が消える`() = withServer(fixture) { client ->
        client.signed(fixture, HttpMethod.Post, "/v1/progress", progressBody.encodeToByteArray())

        assertEquals(HttpStatusCode.NoContent, client.signed(fixture, HttpMethod.Post, "/v1/reports", report.encodeToByteArray()).status)

        assertNull(fixture.progress.current())
    }

    @Test
    fun `TD-82 progress は最後の P8 から V56 ちょうどまで残り、1 秒超で消える`() = withServer(fixture) { client ->
        client.signed(fixture, HttpMethod.Post, "/v1/progress", progressBody.encodeToByteArray())

        fixture.elapse(DesktopValues.PROGRESS_TTL)
        assertNotNull(fixture.progress.current())

        fixture.elapse(Duration.ofSeconds(1))
        assertNull(fixture.progress.current())
    }

    @Test
    fun `TD-83 P2 を読み終えると sending が無くなり、送り終えた行が欄に入る`() = withServer(fixture) { client ->
        client.prepareTable()

        assertContentEquals(TRACK_BYTES, client.signed(fixture, HttpMethod.Get, trackUri()).readRawBytes())

        withTimeout(5_000) { while (fixture.activity.sendingTrack() != null) delay(10) }
        assertTrue(fixture.log.lines.value.any { it.message == "P2 を送り終えた name=track size=100" })
    }

    @Test
    fun `TD-60 保存済みより古い開始時刻の報告は上書きせず 204`() = withServer(fixture) { client ->
        client.signed(fixture, HttpMethod.Post, "/v1/reports", report.encodeToByteArray())
        val older = report.replace("2026-01-01T00:00:00.000Z", "2025-12-31T00:00:00.000Z").replace(""""excluded_count":3""", """"excluded_count":9""")

        val response = client.signed(fixture, HttpMethod.Post, "/v1/reports", older.encodeToByteArray())

        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals(3, lastSync()!!.excludedCount)
    }
}
