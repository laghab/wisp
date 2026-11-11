package app.wisp.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import app.wisp.keyboard.kbd.Icons
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.gesture.GlideStats
import app.wisp.keyboard.style.KeyboardStyle
import app.wisp.keyboard.voice.model.ModelStore
import kotlinx.coroutines.delay

internal fun isEnabled(context: Context): Boolean =
    context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }

internal fun isSelected(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(context.packageName + "/") == true

/** Whether Wisp is enabled and selected, re-checked when the app comes back and every second while it is showing, so a step completes without a tap. */
@Composable
internal fun rememberSetupState(): Pair<Boolean, Boolean> {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isEnabled(context)) }
    var selected by remember { mutableStateOf(isSelected(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { enabled = isEnabled(context); selected = isSelected(context) } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); enabled = isEnabled(context); selected = isSelected(context) } }
    return enabled to selected
}

/** The Wisp mark: a ribbon on a rounded tile, with an amber dot. */
@Composable
private fun Logo(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.width / 32f
        drawRoundRect(Color(0xFF1A2033), size = this.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10f * s))
        val p = Path().apply {
            moveTo(7f * s, 21f * s); cubicTo(10.5f * s, 13f * s, 14f * s, 11.5f * s, 16.5f * s, 14.5f * s); cubicTo(19f * s, 17.5f * s, 21.5f * s, 18f * s, 24f * s, 11f * s)
        }
        drawPath(p, Color(0xFF8FA8FF), style = Stroke(2.6f * s, cap = StrokeCap.Round))
        drawCircle(Color(0xFFFFB86B), 2.6f * s, Offset(24f * s, 10f * s))
    }
}

private class Tile(val title: String, val sub: String, val target: Target, val chip: Color, val ink: Color, val icon: Icons.Icon)

@Composable
fun HomeScreen(onOpen: (Target) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (enabled, selected) = rememberSetupState()
    var style by remember { mutableStateOf(KeyboardStyle.load(context)) }
    fun edit(change: (KeyboardStyle) -> KeyboardStyle) { style = change(style).also { it.save(context) } }
    val glided = remember { GlideStats.thisWeek(context) }
    val voice = remember { ModelStore(context).active() }
    val primary = MaterialTheme.colorScheme.primary; val amber = MaterialTheme.colorScheme.secondary; val green = MaterialTheme.colorScheme.tertiary
    val tiles = listOf(
        Tile("Typing", "Glide, gestures, autocorrect, shortcuts", Target.Screen(SettingsIndex.TYPING), Color(0xFF1A2033), Color(0xFFB5C5FF), Icons.Icon.KEYBOARD),
        Tile("Themes", "Fifteen themes, light and dark", Target.Screen(SettingsIndex.LOOK), Color(0xFF2A1F33), Color(0xFFD9B5FF), Icons.Icon.THEME),
        Tile("Layouts", "Keys, long-press characters, toolbar", Target.Screen(SettingsIndex.LAYOUTS), Color(0xFF13201B), Color(0xFF6FD3A6), Icons.Icon.GRID),
        Tile("Models", "Speech and writing assistant, on this phone", Target.Screen(SettingsIndex.VOICE), Color(0xFF2A2418), Color(0xFFFFB86B), Icons.Icon.SPARKLE),
        Tile("Learned data", "See, remove, export or erase", Target.Open(app.wisp.keyboard.personal.LearnedDataActivity::class.java), Color(0xFF1B2A2E), Color(0xFF8FD6E8), Icons.Icon.DATABASE),
        Tile("Backup", "To a folder you choose", Target.Screen(SettingsIndex.BACKUP), Color(0xFF2E2A1B), Color(0xFFE8D68F), Icons.Icon.ARCHIVE),
        Tile("Practice swiping", "A few minutes teaches Wisp your style", Target.Open(app.wisp.keyboard.training.GestureTrainingActivity::class.java), Color(0xFF2E1B22), Color(0xFFF28DB0), Icons.Icon.SWIPE),
        Tile("Feedback", "Vibration and key sounds", Target.Screen(SettingsIndex.FEEDBACK), Color(0xFF1B2433), Color(0xFF8FB0E8), Icons.Icon.VIBRATE),
        Tile("Position and size", "Set on the keyboard itself", Target.Screen(SettingsIndex.SIZE), Color(0xFF241B33), Color(0xFFB59BE8), Icons.Icon.CROP),
        Tile("Privacy", "What Wisp stores, and what it never does", Target.Screen(SettingsIndex.ABOUT), Color(0xFF13201B), Color(0xFF6FD3A6), Icons.Icon.SHIELD),
    )
    LazyColumn(modifier, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Logo(34.dp)
                Text("Wisp", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
                IconButton({ onOpen(Target.Screen(SettingsIndex.WELCOME)) }, Modifier.size(44.dp)) { Text("?", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        item {
            Card(shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    val ok = enabled && selected
                    Row(Modifier.clickable(enabled = !ok) { onOpen(Target.Screen(SettingsIndex.WELCOME)) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) green else amber))
                        Text(when { ok -> "Wisp is your keyboard"; enabled -> "Wisp is on. Choose it to type with it"; else -> "Wisp is not turned on yet" }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Stat("$glided", "words glided this week", MaterialTheme.colorScheme.onSurface, Modifier.weight(1f))
                        Stat(voice?.name?.removePrefix("Whisper ")?.removePrefix("Moonshine ")?.removePrefix("Parakeet ") ?: "None", if (voice != null) "voice model ready" else "no voice model yet", amber, Modifier.weight(1f))
                        Stat("0 KB", "sent off this phone", green, Modifier.weight(1f))
                    }
                    var test by remember { mutableStateOf("") }
                    OutlinedTextField(test, { test = it }, Modifier.fillMaxWidth(), label = { Text("Test area") }, placeholder = { Text("Type or glide here to try your settings") }, singleLine = true, shape = RoundedCornerShape(12.dp))
                }
            }
        }
        item {
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Quick("Glide typing", style.glide) { v -> edit { it.copy(glide = v) } }
                Quick("Number row", style.numberRow) { v -> edit { it.copy(numberRow = v) } }
                Quick("Swipe to delete", style.wordSwipe) { v -> edit { it.copy(wordSwipe = v) } }
                Quick("Space cursor", style.cursorSlide) { v -> edit { it.copy(cursorSlide = v) } }
                Quick("Autocorrect", style.autoCorrect) { v -> edit { it.copy(autoCorrect = v) } }
                Quick("Haptics", style.haptic != "off") { v -> edit { it.copy(haptic = if (v) "light" else "off") } }
                Quick("Key sound", style.sound) { v -> edit { it.copy(sound = v) } }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        for (t in pair) TileCard(t, Modifier.weight(1f)) { onOpen(t.target) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, caption: String, color: Color, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 1)
        Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A switch the size of a chip, as on the handoff's Home screen. */
@Composable
private fun Quick(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
            .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .border(1.dp, if (on) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(22.dp))
            .clickable { onChange(!on) }
            .semantics { stateDescription = if (on) "On" else "Off" }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant) }
}

@Composable
private fun TileCard(t: Tile, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick, modifier.heightIn(min = 124.dp), shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(t.chip), contentAlignment = Alignment.Center) { WispIcon(t.icon, t.ink, 22.dp) }
            Text(t.title, style = MaterialTheme.typography.titleMedium)
            Text(t.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- first run

/** Three steps, each completed by what the phone reports: turn Wisp on, switch to it, and (optionally) choose a voice model. */
@Composable
fun WelcomeScreen(onDone: () -> Unit, onOpen: (Target) -> Unit = {}, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val (enabled, selected) = rememberSetupState()
    val hasModel = remember(enabled, selected) { ModelStore(context).active() != null }
    // The step to work on now is the first one not done.
    val current = when { !enabled -> 1; !selected -> 2; else -> 3 }
    Column(modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Logo(26.dp)
            Text("Wisp", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            Text("Step $current of 3", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(when (current) { 1 -> "Turn Wisp on"; 2 -> "Switch to Wisp"; else -> "Add a voice model" }, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium)
        Text(when (current) {
            1 -> "Android keeps a list of keyboards. Switch Wisp on in it. Nothing you type is sent anywhere: Wisp has no internet permission for typing."
            2 -> "Choose Wisp as the keyboard you type with. You can switch back any time with the globe key."
            else -> "Voice typing and rewrite run on this phone, so each needs a small model. Pick one under Models. You can also skip this and add one later."
        }, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepRow(1, "Turn Wisp on", "In Android's keyboard list", enabled, current == 1)
            StepRow(2, "Choose Wisp as your keyboard", "The keyboard picker", selected, current == 2)
            StepRow(3, "Download a voice model (optional)", "Voice typing runs on the phone", hasModel, current == 3)
        }
        Spacer(Modifier.weight(1f))
        when (current) {
            1 -> Button({ context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Open keyboard list") }
            2 -> Button({ context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Choose keyboard") }
            else -> {
                if (!hasModel) Button({ onDone(); onOpen(Target.Screen(SettingsIndex.VOICE)) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp)) { Text("Choose a model") }
                TextButton(onDone, Modifier.fillMaxWidth()) { Text(if (hasModel) "Start using Wisp" else "Skip for now") }
            }
        }
        if (current < 3) TextButton(onDone, Modifier.fillMaxWidth()) { Text("Skip for now") }
        Text("Wisp has no internet permission for typing. What you type stays on this phone.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StepRow(n: Int, label: String, sub: String, done: Boolean, current: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .border(1.dp, if (current) primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp)).padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { stateDescription = if (done) "Done" else if (current) "Current step" else "Not done" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.tertiary else if (current) primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(if (done) "\u2713" else "$n", style = MaterialTheme.typography.labelLarge, color = if (done || current) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("What stays on your phone", Icons.Icon.SHIELD) {
            Text("Everything. The words Wisp learns, how you swipe, your settings, and any speech models. Nothing is sent anywhere, and Wisp has no analytics, ads or crash reporting.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("What Wisp can do on the network", Icons.Icon.GLOBE) {
            Text("Only one thing: download speech models and updates, from the Downloads tab. It is off until you turn it on, it never runs while you type or dictate, and it only contacts the servers listed there.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("Permissions", Icons.Icon.LOCK) {
            Text("Microphone (only when you use voice typing), notifications (only to show a download), and installing updates (only when you ask). No contacts, location or storage.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("Your control", Icons.Icon.SLIDERS) {
            Text("See and erase what Wisp learned under “What Wisp learned”. Password fields, private mode and apps you exclude are never learned from. Audio is never kept.", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Wisp ${BuildConfig.VERSION_NAME}. Free software under the GPL-3.0. Speech by sherpa-onnx; the word list comes from the AOSP keyboard dictionaries. See THIRD_PARTY.md.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
