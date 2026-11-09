package app.wisp.keyboard.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.wisp.keyboard.BuildConfig
import app.wisp.keyboard.settings.WispSettings

@Composable
fun HomeScreen(onOpen: (Target) -> Unit, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    val results = remember(query) { SettingsIndex.search(query) }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Wisp", Modifier.semantics { heading() }, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Search settings") }, singleLine = true) }
        if (query.isNotBlank()) {
            if (results.isEmpty()) item { Text("Nothing matches \"$query\".", style = MaterialTheme.typography.bodyMedium) }
            items(results) { e -> Row(e.title, e.summary) { onOpen(e.target) } }
        } else {
            item { Group("Keyboard") }
            item { Row("Look", "Theme, accent colour, key shape, swipe trail") { onOpen(Target.Screen(SettingsIndex.LOOK)) } }
            item { Row("Size and layout", "Height, spacing, number row, one-handed, split") { onOpen(Target.Screen(SettingsIndex.SIZE)) } }
            item { Row("Feedback", "Vibration and key sounds") { onOpen(Target.Screen(SettingsIndex.FEEDBACK)) } }
            item { Row("Layouts and toolbar", "Edit keys and long-press characters; arrange the toolbar") { onOpen(Target.Screen(SettingsIndex.LAYOUTS)) } }
            item { Group("Typing and learning") }
            item { Row("Practice swiping", "A few minutes a day teaches Wisp how you glide") { onOpen(Target.Open(app.wisp.keyboard.training.GestureTrainingActivity::class.java)) } }
            item { Row("What Wisp learned", "See, remove, export or erase your words") { onOpen(Target.Open(app.wisp.keyboard.personal.LearnedDataActivity::class.java)) } }
            item { Row("Apps to never learn from", "Nothing typed in the apps you pick is remembered") { onOpen(Target.Screen(SettingsIndex.EXCLUDED)) } }
            item { Row("Import a word list", "Add words to a language's dictionary") { onOpen(Target.ImportWords) } }
            item { Group("Voice") }
            item { Row("Voice typing", "Speech models, downloads, and when they are freed") { onOpen(Target.Screen(SettingsIndex.VOICE)) } }
            item { Group("Your data") }
            item { Row("Backup and restore", "To a folder you choose, now or on a schedule") { onOpen(Target.Screen(SettingsIndex.BACKUP)) } }
            item { Row("About and privacy", "What Wisp stores, and what it never does") { onOpen(Target.Screen(SettingsIndex.ABOUT)) } }
        }
    }
}

@Composable private fun Group(title: String) = Text(title, Modifier.padding(top = 10.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Row(title: String, subtitle: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------------------------------------------- first run

private fun isEnabled(context: Context): Boolean =
    context.getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == context.packageName }

private fun isSelected(context: Context): Boolean =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith(context.packageName + "/") == true

/** Three steps on one screen: turn Wisp on, switch to it, and read what it does with your data. */
@Composable
fun WelcomeScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(isEnabled(context)) }
    var selected by remember { mutableStateOf(isSelected(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) { enabled = isEnabled(context); selected = isSelected(context) } }
        owner.lifecycle.addObserver(obs); onDispose { owner.lifecycle.removeObserver(obs) }
    }
    Column(modifier.padding(16.dp).then(Modifier), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Wisp", Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Step(1, "Turn Wisp on", "Android keeps a list of keyboards. Switch Wisp on in it.", if (enabled) "Done" else "Open the list", enabled) { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        Step(2, "Switch to Wisp", "Choose it as the keyboard you type with. You can switch back any time with the globe key.", if (selected) "Done" else "Choose keyboard", selected) { context.getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("3. What Wisp does with your data", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Nothing leaves your phone. Typing, swiping and voice all run on the device, with no account and no analytics. Wisp learns your words and how you swipe, and keeps that in a private file you can see, edit or erase. The only time Wisp can use the internet is the Downloads tab for voice models and updates, and that is off until you switch it on.", style = MaterialTheme.typography.bodyMedium)
        } }
        Button(onDone, Modifier.fillMaxWidth()) { Text(if (enabled && selected) "Start using Wisp" else "Skip for now") }
    }
}

@Composable
private fun Step(n: Int, title: String, text: String, action: String, done: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$n. $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (done) Text("✓ Done", color = MaterialTheme.colorScheme.primary) else Button(onClick) { Text(action) }
    } }
}

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Section("What stays on your phone") {
            Text("Everything. The words Wisp learns, how you swipe, your settings, and any speech models. Nothing is sent anywhere, and Wisp has no analytics, ads or crash reporting.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("What Wisp can do on the network") {
            Text("Only one thing: download speech models and updates, from the Downloads tab. It is off until you turn it on, it never runs while you type or dictate, and it only contacts the servers listed there.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("Permissions") {
            Text("Microphone (only when you use voice typing), notifications (only to show a download), and installing updates (only when you ask). No contacts, location or storage.", style = MaterialTheme.typography.bodyMedium)
        }
        Section("Your control") {
            Text("See and erase what Wisp learned under “What Wisp learned”. Password fields, private mode and apps you exclude are never learned from. Audio is never kept.", style = MaterialTheme.typography.bodyMedium)
        }
        Text("Wisp ${BuildConfig.VERSION_NAME}. Free software under the GPL-3.0. Speech by sherpa-onnx; word engine from the AOSP keyboard via HeliBoard. See THIRD_PARTY.md.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
