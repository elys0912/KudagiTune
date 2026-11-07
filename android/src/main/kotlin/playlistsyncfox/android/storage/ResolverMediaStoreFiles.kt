package playlistsyncfox.android.storage

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.OsConstants
import playlistsyncfox.android.sync.SyncValues
import java.io.IOException
import java.io.OutputStream

/** [MediaStoreFiles] の実体。`ContentResolver` 経由（X6-2）。[volume] は保存先のボリューム名（D16）。実機で確かめる項目は C-2、C-7、C-12、C-15。 */
class ResolverMediaStoreFiles(context: Context, volume: String) : MediaStoreFiles {
    private val resolver: ContentResolver = context.contentResolver
    private val packageName: String = context.packageName
    private val audioUri: Uri = MediaStore.Audio.Media.getContentUri(volume)
    private val filesUri: Uri = MediaStore.Files.getContentUri(volume)

    override fun insertPendingTrack(displayName: String, mimeType: String, relativePath: String): String? =
        insertPending(audioUri, displayName, mimeType, relativePath)

    override fun insertPendingPlaylist(displayName: String, mimeType: String, relativePath: String): String? =
        insertPending(filesUri, displayName, mimeType, relativePath)

    override fun exists(uri: String): Boolean =
        resolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), pendingArgs(MediaStore.MATCH_INCLUDE), null)
            ?.use { it.moveToFirst() } ?: false

    override fun size(uri: String): Long? = try {
        resolver.openFileDescriptor(Uri.parse(uri), "r")?.use { it.statSize }
    } catch (_: IOException) {
        null
    }

    override fun open(uri: String, mode: OpenMode): MediaSink? = try {
        val stream = resolver.openOutputStream(Uri.parse(uri), if (mode == OpenMode.Append) "wa" else "wt")
        stream?.let(::StreamSink)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun publish(uri: String): StoredFile? {
        val target = Uri.parse(uri)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        if (resolver.update(target, values, null, null) == 0) return null
        return resolver.query(target, STORED_COLUMNS, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.toStored(target) else null
        }
    }

    override fun delete(uri: String): DeleteOutcome =
        if (resolver.delete(Uri.parse(uri), pendingArgs(MediaStore.MATCH_INCLUDE)) > 0) DeleteOutcome.Deleted else DeleteOutcome.Missing

    override fun ownedTrackPaths(): Set<String> =
        queryOwned(MediaStore.MATCH_INCLUDE).filterNot { it.displayName.endsWith(".m3u8") }
            .mapTo(HashSet()) { it.pathUnderRoot(SyncValues.MEDIA_ROOT) }

    override fun ownedPendingPlaylistUris(): List<String> =
        queryOwned(MediaStore.MATCH_ONLY).filter { it.displayName.endsWith(".m3u8") }.map { it.uri }

    override fun listOwned(): List<StoredFile> = queryOwned(MediaStore.MATCH_EXCLUDE)

    private fun insertPending(collection: Uri, displayName: String, mimeType: String, relativePath: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return try {
            resolver.insert(collection, values)?.toString()
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 持ち主が自分で `Music/<V22>/` の下の行。絞り方（OWNER_PACKAGE_NAME）は実機で確かめる（C-7）。 */
    private fun queryOwned(pendingMode: Int): List<StoredFile> {
        val args = pendingArgs(pendingMode).apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf("${SyncValues.MEDIA_ROOT}%", packageName))
        }
        val found = mutableListOf<StoredFile>()
        resolver.query(filesUri, STORED_COLUMNS, args, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                found += cursor.toStored(ContentUris.withAppendedId(filesUri, cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))))
            }
        }
        return found
    }

    private fun pendingArgs(mode: Int) = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_PENDING, mode) }

    /** [item] は行そのものの URI（ID まで付いたもの）。ここで ID を足さない。 */
    private fun android.database.Cursor.toStored(item: Uri) = StoredFile(
        uri = item.toString(),
        displayName = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)).orEmpty(),
        relativePath = getString(getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)).orEmpty(),
        size = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
        dateModified = getLong(getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)),
    )

    private class StreamSink(private val stream: OutputStream) : MediaSink {
        override fun write(buffer: ByteArray, length: Int) {
            try {
                stream.write(buffer, 0, length)
            } catch (e: IOException) {
                throw if ((e.cause as? ErrnoException)?.errno == OsConstants.ENOSPC) OutOfSpaceException() else e
            }
        }

        override fun flush() = stream.flush()

        override fun close() = stream.close()
    }

    private companion object {
        val STORED_COLUMNS = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        )
    }
}
