package playlistsyncfox.desktop.watch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import playlistsyncfox.desktop.EMPTY_LIBRARY_XML
import playlistsyncfox.desktop.MutableClock
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.emptyLibrary
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val XML_BYTES = EMPTY_LIBRARY_XML.encodeToByteArray()

class LibraryReloadTest {
    private val dir: Path = createTempDirectory("psfox-watch")
    private val xml: Path = dir.resolve("iTunes Music Library.xml").also { it.writeText(EMPTY_LIBRARY_XML) }
    private val clock = MutableClock(T0)

    private val stable = StabilityWaiter({ FileStamp(1, 1) })

    private fun holder(staleDays: Int = 7) = LibraryHolder(clock) { staleDays }

    @Test
    fun `TD-37 安定待ちの途中でサイズか更新日時が変わると、待ちを最初からやり直す`() = runTest {
        val stamps = ArrayDeque(listOf(FileStamp(1, 1), FileStamp(2, 2), FileStamp(2, 2), FileStamp(2, 2)))
        val waiter = StabilityWaiter({ stamps.removeFirst() })

        waiter.awaitStable()

        // 2 秒の待ちを 2 回
        assertEquals(4_000, currentTime)
    }

    @Test
    fun `TD-37 ファイルが無い瞬間も変化とみなして待ちをやり直す`() = runTest {
        val stamps = ArrayDeque(listOf(FileStamp(1, 1), null, FileStamp(1, 1), FileStamp(1, 1)))
        val waiter = StabilityWaiter({ stamps.removeFirst() })

        waiter.awaitStable()

        assertEquals(4_000, currentTime)
    }

    @Test
    fun `TD-38 読み取りの最中に通知が来ると、読み終えた後に 1 回多く読む`() = runTest {
        var reads = 0
        lateinit var refresher: LibraryRefresher
        val loader = LibraryLoader(xml) {
            reads++
            if (reads == 1) refresher.notifyChanged()
            XML_BYTES
        }
        refresher = LibraryRefresher(backgroundScope, loader, stable, holder(), { null })

        refresher.start()
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(2, reads)
    }

    @Test
    fun `TD-39 失敗が V6 の回数続くと回数どおりで止まり、諦めた状態になる`() = runTest {
        var reads = 0
        val loader = LibraryLoader(xml) {
            reads++
            throw IOException("読めない")
        }
        val holder = holder()

        holder.apply(loader.load())

        assertEquals(3, reads)
        assertEquals(XmlStatus.Unreadable(hasResult = false), holder.status.value)
        // 回数の間の待ちは 1 秒を 2 回
        assertEquals(2_000, currentTime)
    }

    @Test
    fun `TD-40 ファイルが無い読み取りが V6 の間隔の後に成功すると正常になる`() = runTest {
        var reads = 0
        val loader = LibraryLoader(xml) {
            if (++reads == 1) throw NoSuchFileException("無い")
            XML_BYTES
        }
        val holder = holder()

        holder.apply(loader.load())

        assertEquals(1_000, currentTime)
        assertEquals(XmlStatus.Normal, holder.status.value)
    }

    @Test
    fun `TD-42 要求の時に更新日時が保持値と違えば、再読してから応える`() = runTest {
        var reads = 0
        val holder = holder()
        val old = Instant.parse("2026-01-01T00:00:00Z")
        holder.apply(LoadOutcome.Loaded(emptyLibrary(), old))
        val refresher = LibraryRefresher(backgroundScope, LibraryLoader(xml) { reads++; XML_BYTES }, stable, holder, { old.plusSeconds(10) })

        refresher.refreshForRequest()

        assertEquals(1, reads)
    }

    @Test
    fun `TD-42 要求の時に更新日時が保持値と同じなら再読しない`() = runTest {
        var reads = 0
        val holder = holder()
        val old = Instant.parse("2026-01-01T00:00:00Z")
        holder.apply(LoadOutcome.Loaded(emptyLibrary(), old))
        val refresher = LibraryRefresher(backgroundScope, LibraryLoader(xml) { reads++; XML_BYTES }, stable, holder, { old })

        refresher.refreshForRequest()

        assertEquals(0, reads)
    }

    @Test
    fun `TD-48 短い間に同じファイルの通知が続けて届いても、読み直しは 1 回`() = runTest {
        var reads = 0
        val refresher = LibraryRefresher(backgroundScope, LibraryLoader(xml) { reads++; XML_BYTES }, stable, holder(), { null })

        repeat(3) { refresher.notifyChanged() }
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(1, reads)
    }

    @Test
    fun `TD-49 要求の時に読み直しが進行中なら、待って同じ結果を使い二重に読まない`() = runTest {
        var reads = 0
        val holder = holder()
        val refresher = LibraryRefresher(backgroundScope, LibraryLoader(xml) { reads++; XML_BYTES }, stable, holder, { T0 })

        val first = async { refresher.refreshForRequest() }
        val second = async { refresher.refreshForRequest() }
        first.await()
        second.await()

        assertEquals(1, reads)
        assertEquals(XmlStatus.Normal, holder.status.value)
    }

    @Test
    fun `TD-51 登録が無効になると V18 の間隔で再登録し、戻ったら 1 回読む。OVERFLOW は取りこぼしとして通知する`() = runTest {
        val openedAt = mutableListOf<Long>()
        var calls = 0
        val opener = DirectoryEventsOpener {
            openedAt += currentTime
            when (++calls) {
                1 -> events(listOf(DirEvent.Overflow))
                2 -> null
                else -> events(null, hang = true)
            }
        }
        var changed = 0
        var overflow = 0

        LibraryWatcher(backgroundScope, xml, opener, { changed++ }, { overflow++ }).start()
        advanceTimeBy(4_001)
        runCurrent()

        assertEquals(listOf(0L, 2_000L, 4_000L), openedAt)
        assertEquals(1, overflow)
        assertEquals(1, changed)
    }

    @Test
    fun `TD-53 ファイル名は大文字小文字だけ違う名前を通知し、別名は通知しない`() = runTest {
        var changed = 0
        val opener = DirectoryEventsOpener {
            events(
                listOf(
                    DirEvent.Touched("ITUNES MUSIC LIBRARY.XML"),
                    DirEvent.Touched("other.xml"),
                    DirEvent.Touched("iTunes Music Library.xml.tmp"),
                ),
                hang = true,
            )
        }

        LibraryWatcher(backgroundScope, xml, opener, { changed++ }, {}).start()
        runCurrent()

        assertEquals(1, changed)
    }

    @Test
    fun `TD-52 XML の更新日時と現在の差が V5 ちょうどなら正常、1 日超なら古い`() {
        val holder = holder(staleDays = 7)
        val modified = Instant.parse("2025-12-01T00:00:00Z")

        clock.now = modified.plus(Duration.ofDays(7))
        holder.apply(LoadOutcome.Loaded(emptyLibrary(), modified))
        assertEquals(XmlStatus.Normal, holder.status.value)

        clock.now = modified.plus(Duration.ofDays(8))
        holder.apply(LoadOutcome.Loaded(emptyLibrary(), modified))
        assertEquals(XmlStatus.Stale(8), holder.status.value)
    }

    @Test
    fun `TD-65 結果がある状態で諦めると読めない（結果あり）、一度も読めずに諦めると読めない（結果なし）、成功すれば正常に戻る`() {
        val withoutResult = holder()
        withoutResult.apply(LoadOutcome.GaveUp)
        assertEquals(XmlStatus.Unreadable(hasResult = false), withoutResult.status.value)

        val holder = holder()
        val loaded = LoadOutcome.Loaded(emptyLibrary(), T0)
        holder.apply(loaded)
        val kept = holder.held.value
        holder.apply(LoadOutcome.GaveUp)
        assertEquals(XmlStatus.Unreadable(hasResult = true), holder.status.value)
        // 最後に読めた結果は残す
        assertSame(kept, holder.held.value)

        holder.apply(loaded)
        assertEquals(XmlStatus.Normal, holder.status.value)
        assertTrue(holder.held.value !== kept)
    }
}

/** 通知を 1 回返す偽の登録。[hang] が真なら、その後は通知を待ち続ける。偽でなければ登録が無効になる（null）。 */
private fun events(batch: List<DirEvent>?, hang: Boolean = false): DirectoryEvents = object : DirectoryEvents {
    private var served = false

    override suspend fun next(): List<DirEvent>? {
        if (!served && batch != null) {
            served = true
            return batch
        }
        if (hang) awaitCancellation()
        return null
    }

    override fun close() = Unit
}
