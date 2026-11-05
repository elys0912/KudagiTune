package playlistsyncfox.desktop

import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "playlist-sync-fox") {
        BasicText("playlist-sync-fox")
    }
}
