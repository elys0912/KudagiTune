package playlistsyncfox.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 配色（X7-1-1 の 7 の表）。名前は表の「用途」に合わせる。 */
class PsfoxColors(
    val background: Color,
    val band: Color,
    val card: Color,
    val logPanel: Color,
    val border: Color,
    val rowLine: Color,
    val buttonBorder: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val primary: Color,
    val success: Color,
    val warning: Color,
    val failure: Color,
    val barGroove: Color,
    val transferringRow: Color,
)

val DarkColors = PsfoxColors(
    background = Color(0xFF1E1E1E),
    band = Color(0xFF252526),
    card = Color(0xFF252526),
    logPanel = Color(0xFF181818),
    border = Color(0xFF2B2B2B),
    rowLine = Color(0xFF262626),
    buttonBorder = Color(0xFF5A5A5A),
    text = Color(0xFFCCCCCC),
    muted = Color(0xFF9D9D9D),
    accent = Color(0xFF3794FF),
    primary = Color(0xFF0E639C),
    success = Color(0xFF89D185),
    warning = Color(0xFFCCA700),
    failure = Color(0xFFF48771),
    barGroove = Color(0xFF3C3C3C),
    transferringRow = Color(0xFF04395E),
)

val LightColors = PsfoxColors(
    background = Color(0xFFFFFFFF),
    band = Color(0xFFF3F3F3),
    card = Color(0xFFF8F8F8),
    logPanel = Color(0xFFF8F8F8),
    border = Color(0xFFE5E5E5),
    rowLine = Color(0xFFEEEEEE),
    buttonBorder = Color(0xFFB5B5B5),
    text = Color(0xFF3B3B3B),
    muted = Color(0xFF616161),
    accent = Color(0xFF005FB8),
    primary = Color(0xFF005FB8),
    success = Color(0xFF388A34),
    warning = Color(0xFF895503),
    failure = Color(0xFFC72E0F),
    barGroove = Color(0xFFE5E5E5),
    transferringRow = Color(0xFFE4F0FB),
)

val LocalPsfoxColors = staticCompositionLocalOf { DarkColors }

/** OS のライト・ダークに従って配色を選び、配る（D20）。Material は入れない。 */
@Composable
fun PsfoxTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPsfoxColors provides if (dark) DarkColors else LightColors, content = content)
}
