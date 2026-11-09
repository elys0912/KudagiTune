package playlistsyncfox.desktop.vault

import playlistsyncfox.desktop.MarkingCipher
import playlistsyncfox.desktop.MemorySecretStore
import playlistsyncfox.desktop.T0
import playlistsyncfox.desktop.settings.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val SAMPLE_A = ByteArray(32) { (0x11 + it).toByte() }
private val SAMPLE_B = ByteArray(32) { (0x71 + it).toByte() }

class VaultTest {
    private val dir: Path = createTempDirectory("psfox-vault")
    private val file = dir.resolve("vault.bin")

    @Test
    fun `TD-18 書き込みの途中で例外が起きても前のファイルが内容ごと残る`() {
        file.writeText("前の内容")

        assertFailsWith<IOException> {
            writeAtomically(file) {
                it.write("新しい内容の途中".encodeToByteArray())
                throw IOException("途中で失敗")
            }
        }

        assertEquals("前の内容", file.readText())
        assertFalse(Files.exists(dir.resolve("vault.bin.tmp")))
    }

    @Test
    fun `TD-19 壊れた暗号文は復号に失敗として返し、空として扱わない`() {
        file.writeText("壊れた暗号文")
        val store = FileSecretStore(file, MarkingCipher())

        assertFailsWith<SecretStoreException> { store.read("device/x") }
    }

    @Test
    fun `TD-20 端末 2 件を保存しても一時フォルダの全ファイルに平文の値が含まれない`() {
        val store = FileSecretStore(file, MarkingCipher())

        store.write("device/00000000-0000-4000-8000-000000000001", SAMPLE_A)
        store.write("device/00000000-0000-4000-8000-000000000002", SAMPLE_B)

        val everything = Files.walk(dir).use { s -> s.filter(Files::isRegularFile).map { it.readBytes() }.toList() }
        assertTrue(everything.isNotEmpty())
        for (content in everything) {
            val text = String(content, Charsets.ISO_8859_1)
            assertFalse(text.contains(String(SAMPLE_A, Charsets.ISO_8859_1)))
            assertFalse(text.contains(Base64.getEncoder().encodeToString(SAMPLE_A)))
            assertFalse(text.contains(Base64.getEncoder().encodeToString(SAMPLE_B)))
        }
    }

    @Test
    fun `TD-21 ファイルが無ければ空として読める`() {
        val store = FileSecretStore(file, MarkingCipher())

        assertNull(store.read("device/00000000-0000-4000-8000-000000000001"))
        assertNull(store.read("tls-key"))
    }

    @Test
    fun `TD-22 端末 0 件では保存の跡が無く、2 件を保存して読み直すと同じ内容になる`() {
        FileSecretStore(file, MarkingCipher()).delete("device/none")
        assertFalse(Files.exists(file))

        val first = FileSecretStore(file, MarkingCipher())
        first.write("device/a", SAMPLE_A)
        first.write("device/b", SAMPLE_B)
        first.write("tls-key", byteArrayOf(9, 8, 7))

        val reread = FileSecretStore(file, MarkingCipher())
        assertContentEquals(SAMPLE_A, reread.read("device/a"))
        assertContentEquals(SAMPLE_B, reread.read("device/b"))
        assertContentEquals(byteArrayOf(9, 8, 7), reread.read("tls-key"))
    }

    @Test
    fun `TD-22 消した名前は読めなくなり、ほかの名前は残る`() {
        val store = FileSecretStore(file, MarkingCipher())
        store.write("device/a", SAMPLE_A)
        store.write("device/b", SAMPLE_B)

        store.delete("device/a")

        assertNull(store.read("device/a"))
        assertContentEquals(SAMPLE_B, store.read("device/b"))
    }

    @Test
    fun `TD-17 鍵が無いと新しい鍵と証明書を作り、別の保管では別の SPKI になる`() {
        val first = loadOrCreateTls(MemorySecretStore(), dir.resolve("a.pem"), T0)
        val second = loadOrCreateTls(MemorySecretStore(), dir.resolve("b.pem"), T0)

        assertTrue(first.keyIsNew)
        assertContains(dir.resolve("a.pem").readText(), "BEGIN CERTIFICATE")
        assertNotEquals(first.spkiDer.toList(), second.spkiDer.toList())
    }

    @Test
    fun `TD-16 期限の残りが V44 ちょうどなら同じ SPKI で証明書を作り直す`() {
        val store = MemorySecretStore()
        val certFile = dir.resolve("tls-cert.pem")
        val created = loadOrCreateTls(store, certFile, T0)
        val before = certFile.readText()
        val notAfter = created.certificate.notAfter.toInstant()

        val renewed = loadOrCreateTls(store, certFile, notAfter.minus(CERTIFICATE_RENEW_THRESHOLD))

        assertFalse(renewed.keyIsNew)
        assertNotEquals(before, certFile.readText())
        assertContentEquals(created.spkiDer, renewed.spkiDer)
    }

    @Test
    fun `TD-16 期限の残りが V44 より 1 日多ければ証明書をそのまま使う`() {
        val store = MemorySecretStore()
        val certFile = dir.resolve("tls-cert.pem")
        val created = loadOrCreateTls(store, certFile, T0)
        val before = certFile.readText()
        val notAfter = created.certificate.notAfter.toInstant()

        loadOrCreateTls(store, certFile, notAfter.minus(CERTIFICATE_RENEW_THRESHOLD).minus(Duration.ofDays(1)))

        assertEquals(before, certFile.readText())
    }

    @Test
    fun `TD-17 鍵があって証明書が無ければ同じ鍵で作り直し SPKI は変わらない`() {
        val store = MemorySecretStore()
        val certFile = dir.resolve("tls-cert.pem")
        val created = loadOrCreateTls(store, certFile, T0)
        Files.delete(certFile)

        val again = loadOrCreateTls(store, certFile, T0)

        assertFalse(again.keyIsNew)
        assertContentEquals(created.spkiDer, again.spkiDer)
    }

    @Test
    fun `TD-17 FileSecretStore に保存した鍵は、開き直した保管から読まれ、鍵を作り直さず同じ SPKI になる`() {
        val certFile = dir.resolve("tls-cert.pem")
        val created = loadOrCreateTls(FileSecretStore(file, MarkingCipher()), certFile, T0)

        val reopened = loadOrCreateTls(FileSecretStore(file, MarkingCipher()), certFile, T0)

        assertTrue(created.keyIsNew)
        assertFalse(reopened.keyIsNew)
        assertContentEquals(created.spkiDer, reopened.spkiDer)
    }
}
