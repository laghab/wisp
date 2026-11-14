package app.wisp.keyboard.ui

import app.wisp.keyboard.kbd.Icons
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
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
            ChoiceRow("Keyboard colours", listOf("system" to "System", "light" to "Light", "dark" to "Dark"), style.colors) { v -> edit { it.copy(colors = v) } }
            Text("Themes that come with both a light and a dark look switch between them here; the other themes keep their one look.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Equal-width columns that fill the card, as many as fit at about 150 dp each.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = maxOf(2, (maxWidth / 150.dp).toInt())
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (row in Themes.all(context, style.wantsDark(context)).chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in row) ThemeCard(t, selected = t.id == style.activeTheme, favorite = t.id in style.favorites, Modifier.weight(1f),
                            onFavorite = { edit { s -> val f = if (t.id in s.favorites) s.favorites - t.id else s.favorites + t.id; s.copy(favorites = f, shuffle = if (f.size < 2) "off" else s.shuffle) } }) { edit { it.copy(theme = t.id, shuffle = "off") } }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            ChoiceRow("Shuffle my favourites", listOf("off" to "Off", "daily" to "Every day", "weekly" to "Every week"), style.shuffle) { v -> edit { it.copy(shuffle = if (style.favorites.size < 2) "off" else v) } }
            Text(
                when {
                    style.favorites.size < 2 -> "Tap the heart on two or more themes to make them favourites, then Wisp can change between them by itself."
                    style.shuffle == "off" -> "${style.favorites.size} favourites. Turn shuffle on and the keyboard changes theme among them. Picking a theme yourself turns shuffle off."
                    else -> "Today: ${Themes.get(context, style.activeTheme, style.wantsDark(context)).name}. It changes ${if (style.shuffle == "weekly") "every Monday" else "every day"}, among your ${style.favorites.size} favourites."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val current = Themes.get(context, style.activeTheme, style.wantsDark(context))
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
            if (Themes.get(context, style.activeTheme, style.wantsDark(context)).art != null) ChoiceRow("Space-bar art", listOf("off" to "Off", "still" to "Still", "subtle" to "Subtle", "full" to "Full"), style.spaceArt) { v -> edit { it.copy(spaceArt = v) } }
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

/** A theme's real look (see [ThemeThumbnail]), with the name under it and a ring when it is the one in use. */
@Composable
private fun ThemeCard(t: Theme, selected: Boolean, favorite: Boolean, modifier: Modifier = Modifier, onFavorite: () -> Unit, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))) {
            ThemeThumbnail(t.id, t.dark)
            // The heart marks a favourite (the themes shuffle picks from).
            Text(if (favorite) "♥" else "♡", color = if (favorite) Color(0xFFFF5C7A) else Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).background(Color(0x66000000), RoundedCornerShape(50)).clickable(onClickLabel = if (favorite) "Remove from favourites" else "Add to favourites", onClick = onFavorite).padding(horizontal = 8.dp, vertical = 2.dp))
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
            ChoiceRow("Vibration on key press", listOf("off" to "Off", "system" to "System", "custom" to "Custom"), style.haptic) { v -> edit { it.copy(haptic = v) } }
            if (style.haptic == "custom") SliderRow("Vibration strength", style.hapticLevel, 1..100) { v -> edit { it.copy(hapticLevel = v) } }
            // Every tap on a sound, and every move of the volume, plays it, so the choice is made by ear.
            val context = LocalContext.current
            val sample = remember { app.wisp.keyboard.kbd.KeySound(context) }
            DisposableEffect(Unit) { onDispose { sample.release() } }
            ChoiceRow("Key sound", listOf("off" to "Off") + app.wisp.keyboard.kbd.KeySound.CHOICES, style.sound) { v -> edit { it.copy(sound = v) }; sample.play(v, style.soundVolume, 'a'.code, whenReady = true) }
            if (style.sound != "off") SliderRow("Volume", style.soundVolume, 5..300) { v -> edit { it.copy(soundVolume = v) }; sample.play(style.sound, v, 'a'.code, whenReady = true) }
            if (style.sound != "off") Text("Tap a sound again to hear it. Above 100% is a boost. Silent in password fields and when the phone is on silent.", style = MaterialTheme.typography.bodySmall)
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
        Section("Text shortcuts", Icons.Icon.BOLT) {
            Text("One per line, as word=expansion. Typing the word and a space types the expansion.", style = MaterialTheme.typography.bodySmall)
            androidx.compose.material3.OutlinedTextField(style.shortcuts, { v -> edit { it.copy(shortcuts = v) } }, Modifier.fillMaxWidth(), placeholder = { Text("omw=On my way") }, minLines = 3)
        }
    }
}
