package playlistsyncfox.desktop.server

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** P1 で返した曲の値と実ファイルのパス（X2-4 の 9）。 */
class TrackEntry(val size: Long, val modifiedAt: Long, val path: Path)

/** P1 のたびに置き換える曲の表（J31）。メモリだけ。null は P1 が無い（起動直後、再起動後）。 */
class TrackTable {
    private val table = AtomicReference<Map<String, TrackEntry>?>(null)

    fun replace(entries: Map<String, TrackEntry>) = table.set(entries)

    fun snapshot(): Map<String, TrackEntry>? = table.get()
}
