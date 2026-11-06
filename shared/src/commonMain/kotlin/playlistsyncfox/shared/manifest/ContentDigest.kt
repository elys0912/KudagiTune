package playlistsyncfox.shared.manifest

import playlistsyncfox.shared.crypto.CryptoProvider

/** `content_digest` の先頭の固定の文字列（V39）。 */
private const val DIGEST_PREFIX = "playlist-sync-fox/manifest-digest/v1"

/**
 * 3 つの一覧の内容の指紋を、小文字の 16 進 64 桁で返す（V40）。
 * `generated_at`、`library_id`、`xml_modified_at`、`excluded`、`content_digest` は含めない。
 * 一覧の並びに依らない。`track_ids` の順は m3u8 に効くため、そのまま符号化する。
 */
fun computeContentDigest(crypto: CryptoProvider, manifest: Manifest): String {
    val tracks = manifest.tracks
    val playlists = manifest.playlists
    val unresolvedPlaylists = manifest.unresolvedPlaylists

    val out = DigestBuffer()
    out.putString(DIGEST_PREFIX)
    out.putLong(manifest.formatVersion.toLong())

    out.putCount(tracks.size)
    for (track in tracks.sortedBy { it.persistentId }) {
        out.putString(track.persistentId)
        out.putLong(track.size)
        out.putLong(track.modifiedAt)
        out.putString(track.fileName)
        out.putString(track.extension)
        out.putOptionalString(track.name)
        out.putOptionalString(track.artist)
        out.putOptionalString(track.album)
        out.putOptionalString(track.albumArtist)
        out.putOptionalLong(track.trackNumber?.toLong())
        out.putOptionalLong(track.discNumber?.toLong())
        out.putOptionalLong(track.durationMs?.toLong())
        out.putOptionalString(track.kind)
        out.putBoolean(track.isProtected)
        out.putOptionalString(track.contentHash)
    }

    out.putCount(playlists.size)
    for (playlist in playlists.sortedBy { it.persistentId }) {
        out.putString(playlist.persistentId)
        out.putString(playlist.name)
        out.putCount(playlist.trackIds.size)
        playlist.trackIds.forEach { out.putString(it) }
    }

    out.putCount(unresolvedPlaylists.size)
    for (unresolved in unresolvedPlaylists.sortedBy { it.persistentId }) {
        out.putString(unresolved.persistentId)
        out.putString(unresolved.lastKnownName)
    }

    return crypto.sha256(out.toByteArray()).toHexString()
}

// commonMain に ByteArrayOutputStream が無いため、伸びるバイト列を自前で持つ
private class DigestBuffer {
    private var bytes = ByteArray(1024)
    private var size = 0

    fun toByteArray(): ByteArray = bytes.copyOf(size)

    fun putString(value: String) {
        val encoded = value.encodeToByteArray()
        putCount(encoded.size)
        putBytes(encoded)
    }

    fun putOptionalString(value: String?) {
        if (value == null) {
            putByte(0)
        } else {
            putByte(1)
            putString(value)
        }
    }

    fun putOptionalLong(value: Long?) {
        if (value == null) {
            putByte(0)
        } else {
            putByte(1)
            putLong(value)
        }
    }

    fun putBoolean(value: Boolean) = putByte(if (value) 1 else 0)

    /** 長さと件数。4 バイトのビッグエンディアン。 */
    fun putCount(value: Int) {
        for (shift in 24 downTo 0 step 8) putByte(value ushr shift)
    }

    /** 8 バイトのビッグエンディアンの符号付き。 */
    fun putLong(value: Long) {
        for (shift in 56 downTo 0 step 8) putByte((value ushr shift).toInt())
    }

    private fun putBytes(values: ByteArray) {
        ensureCapacity(values.size)
        values.copyInto(bytes, size)
        size += values.size
    }

    private fun putByte(value: Int) {
        ensureCapacity(1)
        bytes[size++] = value.toByte()
    }

    private fun ensureCapacity(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
}
