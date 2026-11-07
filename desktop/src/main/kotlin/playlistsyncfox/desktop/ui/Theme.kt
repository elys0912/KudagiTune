package playlistsyncfox.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// 配色は OS の設定に従う（X5-5）。値は設計書の表。Material3 の ColorScheme に入らないものは [AppColors] に持つ。

/** 表のうち Material3 の役に収まらない色。 */
@Immutable
class AppColors(
    /** 横の欄。 */
    val side: Color,
    val border: Color,
    val muted: Color,
    /** アクセント・棒。 */
    val accent: Color,
    val success: Color,
    val caution: Color,
    /** 棒の溝。 */
    val track: Color,
    /** 状態の帯。文字は白。 */
    val statusBar: Color,
)

private val darkApp = AppColors(
    side = Color(0xFF252526), border = Color(0xFF2B2B2B), muted = Color(0xFF9D9D9D), accent = Color(0xFF3794FF),
    success = Color(0xFF89D185), caution = Color(0xFFCCA700), track = Color(0xFF3C3C3C), statusBar = Color(0xFF007ACC),
)

private val lightApp = AppColors(
    side = Color(0xFFF3F3F3), border = Color(0xFFE5E5E5), muted = Color(0xFF616161), accent = Color(0xFF005FB8),
    success = Color(0xFF388A34), caution = Color(0xFF895503), track = Color(0xFFE5E5E5), statusBar = Color(0xFF005FB8),
)

private val darkScheme = darkColorScheme(
    background = Color(0xFF1E1E1E), surface = Color(0xFF1E1E1E), onBackground = Color(0xFFCCCCCC), onSurface = Color(0xFFCCCCCC),
    primary = Color(0xFF0E639C), onPrimary = Color.White, outline = Color(0xFF5A5A5A), outlineVariant = Color(0xFF2B2B2B),
    onSurfaceVariant = Color(0xFF9D9D9D), error = Color(0xFFF48771),
)

private val lightScheme = lightColorScheme(
    background = Color(0xFFFFFFFF), surface = Color(0xFFFFFFFF), onBackground = Color(0xFF3B3B3B), onSurface = Color(0xFF3B3B3B),
    primary = Color(0xFF005FB8), onPrimary = Color.White, outline = Color(0xFFB5B5B5), outlineVariant = Color(0xFFE5E5E5),
    onSurfaceVariant = Color(0xFF616161), error = Color(0xFFC72E0F),
)

// 書体は OS の既定。窓の文字を小さめにそろえる（見本は 13px）
private val typography = Typography(
    headlineSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 13.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 12.sp),
)

private val LocalAppColors = staticCompositionLocalOf { darkApp }

val MaterialTheme.appColors: AppColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColors.current

@Composable
fun PsfoxTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppColors provides if (dark) darkApp else lightApp) {
        MaterialTheme(colorScheme = if (dark) darkScheme else lightScheme, typography = typography, content = content)
    }
}
