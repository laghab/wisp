package app.wisp.keyboard.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wisp.keyboard.style.KeyboardStyle

/** The style being edited. Every change is saved at once and shown in the preview at once. */
@Composable
private fun rememberEditableStyle(): Pair<KeyboardStyle, ((KeyboardStyle) -> KeyboardStyle) -> Unit> {
    val context = LocalContext.current
    var style by remember { mutableStateOf(KeyboardStyle.load(context)) }
    return style to { change -> style = change(style).also { it.save(context) } }
}

@Composable
private fun PreviewAnd(modifier: Modifier, style: KeyboardStyle, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier) {
        KeyboardPreview(style)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun LookScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    PreviewAnd(modifier, style) {
        Section("Theme") {
            ChoiceRow("Colours", listOf("system" to "Follow the phone", "light" to "Light", "dark" to "Dark"), style.theme) { v -> edit { it.copy(theme = v) } }
        }
        Section("Accent colour") {
            val options = KeyboardStyle.ACCENTS.map { (n, c) -> n to Color(c) } + if (KeyboardStyle.DYNAMIC_AVAILABLE) listOf("dynamic" to Color(KeyboardStyle.colorOf(LocalContext.current, "dynamic"))) else emptyList()
            ColorSwatches(options, style.accent) { v -> edit { it.copy(accent = v) } }
            if (KeyboardStyle.DYNAMIC_AVAILABLE) Text("The last circle follows your wallpaper.", style = MaterialTheme.typography.bodySmall)
        }
        Section("Keys") {
            ChoiceRow("Shape", listOf("square" to "Square", "round" to "Rounded", "pill" to "Pill"), style.shape) { v -> edit { it.copy(shape = v) } }
        }
        Section("Swipe trail") {
            SwitchRow("Show the trail while swiping", checked = style.trailOn) { v -> edit { it.copy(trailOn = v) } }
            if (style.trailOn) {
                val options = listOf("accent" to Color(style.accentColor(LocalContext.current))) + KeyboardStyle.ACCENTS.map { (n, c) -> n to Color(c) }
                ColorSwatches(options, style.trailColor) { v -> edit { it.copy(trailColor = v) } }
                SliderRow("Thickness", style.trailWidthDp, 2..12, " dp") { v -> edit { it.copy(trailWidthDp = v) } }
            }
        }
    }
}

@Composable
fun SizeScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    PreviewAnd(modifier, style) {
        Section("Size") {
            SliderRow("Height, portrait", style.heightPortrait, 70..130) { v -> edit { it.copy(heightPortrait = v) } }
            SliderRow("Height, landscape", style.heightLandscape, 60..130) { v -> edit { it.copy(heightLandscape = v) } }
            SliderRow("Gap below the keyboard", style.bottomGap, 0..80, " dp") { v -> edit { it.copy(bottomGap = v) } }
            SliderRow("Space between keys", style.gap, 40..200) { v -> edit { it.copy(gap = v) } }
        }
        Section("Layout") {
            SwitchRow("Number row", "Digits above the letters", style.numberRow) { v -> edit { it.copy(numberRow = v) } }
            SwitchRow("Floating keyboard", "A smaller keyboard you can drag by its handle; the app behind it is not pushed up", style.floating) { v -> edit { it.copy(floating = v) } }
            SwitchRow("Split keyboard", "A gap down the middle, for typing with two thumbs", style.split) { v -> edit { it.copy(split = v) } }
            ChoiceRow("One-handed mode", listOf("off" to "Off", "left" to "Left hand", "right" to "Right hand"), style.oneHanded) { v -> edit { it.copy(oneHanded = v) } }
        }
    }
}

@Composable
fun FeedbackScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Feedback") {
            ChoiceRow("Vibration on key press", listOf("off" to "Off", "light" to "Light", "strong" to "Strong"), style.haptic) { v -> edit { it.copy(haptic = v) } }
            SwitchRow("Key click sound", "Uses the phone's own click sound and volume", style.sound) { v -> edit { it.copy(sound = v) } }
        }
        Text("Try it: open any text field and type.", style = MaterialTheme.typography.bodySmall)
    }
}
