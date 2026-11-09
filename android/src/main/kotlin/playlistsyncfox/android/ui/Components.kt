package playlistsyncfox.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 全画面が使う部品（配色は Theme.kt）。

@Composable
internal fun Label(text: String, bold: Boolean = false, color: Color = LocalPsfoxColors.current.text, mono: Boolean = false) =
    BasicText(
        text,
        style = TextStyle(
            color = color,
            fontSize = if (mono) 13.sp else 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        ),
    )

/** 枠のボタン。 */
@Composable
internal fun Action(label: String, onClick: () -> Unit) = ActionCell(label, Modifier.fillMaxWidth(), onClick)

@Composable
private fun ActionCell(label: String, modifier: Modifier, onClick: () -> Unit) {
    val c = LocalPsfoxColors.current
    Box(
        modifier.heightIn(min = 44.dp).border(1.dp, c.buttonBorder, RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = c.text, fontSize = 14.sp, textAlign = TextAlign.Center))
    }
}

/** 主ボタン（白文字）。 */
@Composable
internal fun PrimaryAction(label: String, onClick: () -> Unit) {
    val c = LocalPsfoxColors.current
    Box(
        Modifier.fillMaxWidth().height(48.dp).background(c.primary, RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}

/** 枠のボタンを 2 列に並べる（S1）。 */
@Composable
internal fun ActionGrid(actions: List<Pair<String, () -> Unit>>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        actions.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, onClick) -> ActionCell(label, Modifier.weight(1f), onClick) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun Card(content: @Composable () -> Unit) {
    val c = LocalPsfoxColors.current
    Column(
        Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(6.dp)).border(1.dp, c.border, RoundedCornerShape(6.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** 棒。[fraction] は 0〜1。 */
@Composable
internal fun Bar(fraction: Float, height: Int) {
    val c = LocalPsfoxColors.current
    Box(Modifier.fillMaxWidth().height(height.dp).background(c.barGroove, RoundedCornerShape((height / 2).dp))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height.dp).background(c.accent, RoundedCornerShape((height / 2).dp)))
    }
}

@Composable
internal fun Field(value: String, onChange: (String) -> Unit) {
    val c = LocalPsfoxColors.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 15.sp),
        cursorBrush = SolidColor(c.text),
        modifier = Modifier.fillMaxWidth().border(1.dp, c.buttonBorder, RoundedCornerShape(4.dp)).padding(10.dp),
    )
}

@Composable
internal fun Confirm(text: String, ok: String, onOk: () -> Unit, onCancel: () -> Unit, cancel: String = "取り消す") {
    val c = LocalPsfoxColors.current
    Column(
        Modifier.fillMaxWidth().border(2.dp, c.accent, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Label(text)
        Action(ok, onOk)
        Action(cancel, onCancel)
    }
}

/** 押すと確認を出し、「ok」で [onConfirmed] を行うボタン。確認を出しているかは中で持つ。 */
@Composable
internal fun ConfirmedAction(label: String, prompt: String, ok: String, onConfirmed: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    Action(label) { asking = true }
    if (asking) {
        Confirm(prompt, ok, { asking = false; onConfirmed() }, { asking = false })
    }
}

@Composable
internal fun Back(model: AppViewModel) = Action("戻る", model::back)
