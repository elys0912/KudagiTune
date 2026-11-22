package playlistsyncfox.android.storage

import java.io.IOException

/** m3u8 のファイル名の拡張子。曲と m3u8 を名前で見分けるのに使う。 */
internal const val M3U8_EXTENSION = ".m3u8"

/** 書き込みの開き方。`Append` は `"wa"`、`Truncate` は `"wt"`（X6-2）。 */
enum class OpenMode { Append, Truncate }

/** MediaStore から読み戻した 1 件。[relativePath] は `Music/<V22>/…/` の形（末尾に `/`）。[dateModified] は秒。 */
class StoredFile(
    val uri: String,
    val displayName: String,
    val relativePath: String,
    val size: Long,
    val dateModified: Long,
)

/** 書き込み中の容量不足（`IOException` のうち空きの不足）。 */
class OutOfSpaceException : IOException("空き容量が足りない")

interface MediaSink : AutoCloseable {
    fun write(buffer: ByteArray, length: Int)

    fun flush()
}

/**
 * OS の MediaStore に触る部分だけ（X6-2）。手順（pending の作り方、T5、読み戻しの判定）は [MediaWriter] が持ち、
 * ここは 1 回の呼び出しで 1 つのことをする。ブロックする呼び出しなので、IO の文脈で呼ぶ。
 */
interface MediaStoreFiles {
    /** `MediaStore.Audio.Media` に pending（`IS_PENDING`＝1）の行を作る。作れなければ null。 */
    fun insertPendingTrack(displayName: String, mimeType: String, relativePath: String): String?

    /** `MediaStore.Files` に pending の m3u8 の行を作る（J18）。作れなければ null。 */
    fun insertPendingPlaylist(displayName: String, mimeType: String, relativePath: String): String?

    /** pending の行も含めて、行が今あるか。 */
    fun exists(uri: String): Boolean

    /** 書き込み済みのファイルの大きさ。行が無ければ null。 */
    fun size(uri: String): Long?

    /** 開けなければ null。 */
    fun open(uri: String, mode: OpenMode): MediaSink?

    /** `IS_PENDING`＝0 にして `DISPLAY_NAME`、`RELATIVE_PATH`、`SIZE`、`DATE_MODIFIED` を読み戻す。行が無ければ null。 */
    fun publish(uri: String): StoredFile?

    /** 行を消す。行が既に無くても何もしない。権限が無ければ [SecurityException]。 */
    fun delete(uri: String)

    /** 自分が持ち主で `Music/<V22>/` の下にある行（pending を含む）の、`Music/<V22>/` を除いた相対パス。 */
    fun ownedTrackPaths(): Set<String>

    /** 自分の pending の m3u8 の URI。 */
    fun ownedPendingPlaylistUris(): List<String>

    /**
     * `Music/<V22>/` の下にある曲と m3u8（pending でないもの）。持ち主で絞らない（X6-2）。
     * 入れ直しで持ち主が外れた行も入る。消すときに利用者の同意が要る行は、呼び出し側が OS の削除の確認で扱う。
     */
    fun listUnderRoot(): List<StoredFile>
}

/** `Music/<V22>/` を除いた相対パス（フォルダ部分とファイル名をつなげたもの）。 */
fun StoredFile.pathUnderRoot(root: String): String = relativePath.removePrefix(root) + displayName
