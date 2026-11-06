package playlistsyncfox.desktop.settings

import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * 同じフォルダの一時ファイル（`<名前>.tmp`）へ書いて閉じ、置き換える（X5-2、X5-4）。
 * 書き込みか置き換えに失敗したら一時ファイルを消し、前のファイルは残る。
 */
fun writeAtomically(target: Path, write: (OutputStream) -> Unit) {
    val temp = target.resolveSibling(target.fileName.toString() + ".tmp")
    try {
        Files.createDirectories(target.parent)
        Files.newOutputStream(temp).use(write)
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        Files.deleteIfExists(temp)
        throw e
    }
}
