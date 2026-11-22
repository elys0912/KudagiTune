package playlistsyncfox.desktop

import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg

/** 登録先（現在のユーザーの Run キー）の窓口。試験では偽の実体に差し替える。 */
interface RunKey {
    fun set(name: String, data: String)

    /** 無くても失敗にしない。 */
    fun delete(name: String)
}

/** `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。管理者権限が要らない。実環境の確認は C-13。 */
class WindowsRunKey : RunKey {
    override fun set(name: String, data: String) =
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, name, data)

    override fun delete(name: String) {
        if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, name)) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY_PATH, name)
        }
    }

    private companion object {
        const val RUN_KEY_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    }
}

/** 配布物でない実行（開発）用。何も登録しない。 */
object NoRunKey : RunKey {
    override fun set(name: String, data: String) = Unit

    override fun delete(name: String) = Unit
}

private const val RUN_VALUE_NAME = "playlist-sync-fox"
/** ログイン時の起動であることを示す引数。Run キーに書き、Main が読む。 */
internal const val BACKGROUND_FLAG = "--background"

/**
 * ログイン時の起動の登録（X5-6）。値は配布物の実行ファイルのパスと `--background`。
 * パスが変わったとき（更新、移動）に備え、起動のたびに現在のパスで書き直す。
 */
class Autostart(private val runKey: RunKey, private val executablePath: () -> String) {
    fun apply(enabled: Boolean) {
        if (enabled) runKey.set(RUN_VALUE_NAME, "\"${executablePath()}\" $BACKGROUND_FLAG") else runKey.delete(RUN_VALUE_NAME)
    }
}
