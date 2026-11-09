package playlistsyncfox.android.storage

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

class StorageVolumesTest {
    @Test
    fun `getAllocatableBytes が使えるときは、その値を返す`() {
        val free = primaryFreeBytes(allocatable = { 700L }, statFs = { 300L })

        assertEquals(700L, free)
    }

    @Test
    fun `getAllocatableBytes が使えないときは、StatFs の空きを返す`() {
        val free = primaryFreeBytes(allocatable = { throw IOException("測れない") }, statFs = { 300L })

        assertEquals(300L, free)
    }
}
