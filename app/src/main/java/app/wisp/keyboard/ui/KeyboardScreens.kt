package app.wisp.keyboard.ui

import app.wisp.keyboard.kbd.Icons
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import app.wisp.keyboard.style.KeyboardStyle
import app.wisp.keyboard.style.PhotoBackground
import app.wisp.keyboard.style.Theme
import app.wisp.keyboard.style.Themes

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

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LookScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    val context = LocalContext.current
    PreviewAnd(modifier, style) {
        Section("App appearance", Icons.Icon.MOON) {
            var mode by remember { mutableStateOf(AppAppearance.current(context)) }
            ChoiceRow("The settings app", AppAppearance.options, mode) { mode = it; AppAppearance.set(context, it) }
        }
        Section("Theme", Icons.Icon.THEME) {
            // Equal-width columns that fill the card, as many as fit at about 100 dp each.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = maxOf(2, (maxWidth / 100.dp).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in Themes.all(context).chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in row) ThemeCard(t, selected = t.id == style.theme, Modifier.weight(1f)) { edit { it.copy(theme = t.id) } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            val current = Themes.get(context, style.theme)
            if (current.dynamic && KeyboardStyle.DYNAMIC_AVAILABLE) SwitchRow("Colour from wallpaper", "Material You colours from your wallpaper", style.wallpaperColours) { v -> edit { it.copy(wallpaperColours = v) } }
        }
        Section("Photo background", Icons.Icon.IMAGE) {
            val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
                if (uri != null) PhotoBackground.import(context, uri)?.let { path -> edit { it.copy(photo = path) } }
            }
            androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.Button(onClick = { picker.launch("image/*") }) { Text(if (style.photo.isEmpty()) "Choose a photo" else "Change photo") }
                if (style.photo.isNotEmpty()) androidx.compose.material3.OutlinedButton(onClick = { PhotoBackground.file(context).delete(); edit { it.copy(photo = "") } }) { Text("Remove") }
            }
            if (style.photo.isNotEmpty()) SliderRow("Dimming", style.photoDim, 0..80, "%") { v -> edit { it.copy(photoDim = v) } }
        }
        Section("Fine-tune", Icons.Icon.SLIDERS) {
            SwitchRow("Key borders", checked = style.keyBorders) { v -> edit { it.copy(keyBorders = v) } }
            SwitchRow("Key hints", "The small symbol on each key, typed with a long press", style.keyHints) { v -> edit { it.copy(keyHints = v) } }
        }
        Section("Swipe trail", Icons.Icon.SWIPE) {
            SwitchRow("Show the trail while swiping", checked = style.trailOn) { v -> edit { it.copy(trailOn = v) } }
            if (style.trailOn) {
                val options = listOf("accent" to Color(style.trailColorInt(context).let { if (style.trailColor == "accent") it else style.themeFor(context).accent.base() })) + KeyboardStyle.ACCENTS.map { (n, c) -> n to Color(c) }
                ColorSwatches(options, style.trailColor) { v -> edit { it.copy(trailColor = v) } }
                SliderRow("Thickness", style.trailWidthDp, 2..12, " dp") { v -> edit { it.copy(trailWidthDp = v) } }
            }
        }
    }
}

/** A small preview of a theme: its board, three keys and its accent. */
@Composable
private fun ThemeCard(t: Theme, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = { v: Int -> Color(v) }
    Column(
        modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(10.dp))
                .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)),
        ) {
            drawRect(c(t.board.first().base() or (0xFF shl 24)))
            val key = c(t.key.base()); val mod = c(t.mod.base()); val acc = c(t.accent.base())
            val kw = size.width / 6f; val kh = size.height / 4f
            val r = androidx.compose.ui.geometry.CornerRadius(minOf(t.radius[0], 6f) * density, minOf(t.radius[0], 6f) * density)
            for (row in 0..2) for (col in 0..4) drawRoundRect(if (row == 2 && col == 0) mod else if (row == 2 && col == 4) acc else key, androidx.compose.ui.geometry.Offset(kw * (col + 0.5f), kh * (row + 0.5f)), androidx.compose.ui.geometry.Size(kw * 0.85f, kh * 0.8f), r)
        }
        Text(t.name, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun SizeScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    PreviewAnd(modifier, style) {
        Section("Size and position", Icons.Icon.CROP) {
            Text("Resize, raise, one-handed, floating and split are set on the keyboard itself: open the keyboard and tap the resize button on its toolbar.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("Keys", Icons.Icon.KEYBOARD) {
            SliderRow("Space between keys", style.gap, 40..200) { v -> edit { it.copy(gap = v) } }
            SwitchRow("Number row", "Digits above the letters", style.numberRow) { v -> edit { it.copy(numberRow = v) } }
        }
    }
}

@Composable
fun FeedbackScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("Feedback", Icons.Icon.VIBRATE) {
            ChoiceRow("Vibration on key press", listOf("off" to "Off", "light" to "Light", "strong" to "Strong"), style.haptic) { v -> edit { it.copy(haptic = v) } }
            SwitchRow("Key click sound", "Uses the phone's own click sound and volume", style.sound) { v -> edit { it.copy(sound = v) } }
        }
        Text("Try it: open any text field and type.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TypingScreen(modifier: Modifier = Modifier) {
    val (style, edit) = rememberEditableStyle()
    PreviewAnd(modifier, style) {
        Section("Swiping and gestures", Icons.Icon.SWIPE) {
            SwitchRow("Glide typing", "Draw a path across the letters to type a word", style.glide) { v -> edit { it.copy(glide = v) } }
            SwitchRow("Swipe to delete words", "Swipe left from the backspace key", style.wordSwipe) { v -> edit { it.copy(wordSwipe = v) } }
            SwitchRow("Slide on space to move the cursor", checked = style.cursorSlide) { v -> edit { it.copy(cursorSlide = v) } }
            SliderRow("Long-press delay", style.longPressMs, 150..700, " ms") { v -> edit { it.copy(longPressMs = v) } }
        }
        Section("Writing", Icons.Icon.PEN) {
            SwitchRow("Autocorrect", "Fixes likely typos when you type a space; backspace undoes it", style.autoCorrect) { v -> edit { it.copy(autoCorrect = v) } }
            SwitchRow("Auto-capitalise", "A capital letter at the start of a sentence", style.autoCaps) { v -> edit { it.copy(autoCaps = v) } }
            SwitchRow("Double space makes a full stop", "Two spaces after a word type \". \"", style.doubleSpacePeriod) { v -> edit { it.copy(doubleSpacePeriod = v) } }
            val context = LocalContext.current
            var punct by remember { mutableStateOf(app.wisp.keyboard.settings.WispSettings.get(context).getBoolean("voice_auto_punct", true)) }
            SwitchRow("Punctuate dictation", "Voice typing adds capitals and punctuation; off types the words as spoken", punct) { v -> punct = v; app.wisp.keyboard.settings.WispSettings.get(context).putBoolean("voice_auto_punct", v) }
        }
        Section("Feedback", Icons.Icon.VIBRATE) {
            ChoiceRow("Vibration", listOf("off" to "Off", "light" to "Light", "strong" to "Strong"), style.haptic) { v -> edit { it.copy(haptic = v) } }
            SwitchRow("Key sound", checked = style.sound) { v -> edit { it.copy(sound = v) } }
        }
        Section("Text shortcuts", Icons.Icon.BOLT) {
            Text("One per line, as word=expansion. Typing the word and a space types the expansion.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(style.shortcuts, { v -> edit { it.copy(shortcuts = v) } }, Modifier.fillMaxWidth(), placeholder = { Text("omw=On my way") }, minLines = 3)
        }
    }
}
