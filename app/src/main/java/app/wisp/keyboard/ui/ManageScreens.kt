package app.wisp.keyboard.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wisp.keyboard.backup.BackupException
import app.wisp.keyboard.backup.BackupFormat
import app.wisp.keyboard.backup.BackupManager
import app.wisp.keyboard.backup.UserData
import app.wisp.keyboard.kbd.LayoutSlot
import app.wisp.keyboard.kbd.LayoutStore
import app.wisp.keyboard.kbd.SuggestionBarView
import app.wisp.keyboard.kbd.Toolbar
import app.wisp.keyboard.settings.WispSettings
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------------- layouts & toolbar

@Composable
fun LayoutsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LayoutSlot?>(null) }
    var pending by remember { mutableStateOf<LayoutSlot?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val slot = pending; pending = null
        if (uri != null && slot != null) {
            val text = runCatching { context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            message = if (text == null) "Could not read that file." else LayoutStore.save(context, slot, text)?.let { "Not imported. $it" } ?: "${slot.title} layout imported."
            revision++
        }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        val slot = pending; pending = null
        if (uri != null && slot != null) message = runCatching { context.contentResolver.openOutputStream(uri)!!.use { it.write(LayoutStore.currentText(context, slot).toByteArray()) }; "Exported." }.getOrElse { "Could not write that file." }
    }
    editing?.let { slot -> return LayoutEditor(slot, modifier, onDone = { m -> editing = null; if (m != null) message = m; revision++ }) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        message?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
        @Suppress("UNUSED_EXPRESSION") revision
        Text("Key layouts are plain text files. Edit one here, or export it, change it on a computer and import it again. A file with a mistake is refused with the line and the reason, and your current layout stays. The switch-keyboard key is always kept.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LayoutSlot.values().forEach { slot ->
            Section(slot.title) {
                Text(if (LayoutStore.isCustom(context, slot)) "Using your own layout" else "Using the built-in layout", style = MaterialTheme.typography.bodyMedium)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({ editing = slot }) { Text("Edit") }
                    OutlinedButton({ pending = slot; import.launch(arrayOf("application/json", "text/*", "*/*")) }) { Text("Import") }
                    OutlinedButton({ pending = slot; export.launch("wisp-layout-${slot.id}.json") }) { Text("Export") }
                    if (LayoutStore.isCustom(context, slot)) TextButton({ LayoutStore.reset(context, slot); message = "${slot.title} layout restored to the built-in one."; revision++ }) { Text("Use built-in") }
                }
            }
        }
        ToolbarEditor()
    }
}

@Composable
private fun LayoutEditor(slot: LayoutSlot, modifier: Modifier, onDone: (String?) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(LayoutStore.currentText(context, slot)) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${slot.title} layout", style = MaterialTheme.typography.titleMedium)
        Text("One row per line inside \"rows\". A key is a letter, or an object: {\"c\": \"e\", \"pop\": \"éè\"} holds é and è under a long press; {\"k\": \"space\", \"w\": 3} is a named key three units wide.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Surface(Modifier.weight(1f).fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            BasicTextField(text, { text = it; error = null }, Modifier.verticalScroll(rememberScrollState()).padding(8.dp).fillMaxWidth(),
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface), cursorBrush = SolidColor(MaterialTheme.colorScheme.primary))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ val problem = LayoutStore.save(context, slot, text); if (problem != null) error = problem else onDone("${slot.title} layout saved.") }) { Text("Save") }
            TextButton({ onDone(null) }) { Text("Cancel") }
        }
    }
}

@Composable
private fun ToolbarEditor() {
    val context = LocalContext.current
    val settings = remember { WispSettings.get(context) }
    var tools by remember { mutableStateOf(Toolbar.load(settings)) }
    fun commit(list: List<SuggestionBarView.Tool>) { tools = list; Toolbar.save(settings, list) }
    Section("Toolbar") {
        Text("The strip above the keys when you are not typing a word. Move things around or switch them off.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val all = tools + SuggestionBarView.Tool.values().filter { it !in tools }
        all.forEach { tool ->
            val on = tool in tools
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(on, { v -> commit(if (v) tools + tool else tools - tool) })
                Spacer(Modifier.width(12.dp))
                Text(tool.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                if (on) {
                    val i = tools.indexOf(tool)
                    TextButton({ commit(tools.toMutableList().also { it.add(i - 1, it.removeAt(i)) }) }, enabled = i > 0) { Text("Up") }
                    TextButton({ commit(tools.toMutableList().also { it.add(i + 1, it.removeAt(i)) }) }, enabled = i < tools.lastIndex) { Text("Down") }
                }
            }
        }
        TextButton({ Toolbar.reset(settings); tools = Toolbar.DEFAULT }) { Text("Restore default toolbar") }
    }
}

// ---------------------------------------------------------------------------------------------------- backup & restore

@Composable
fun BackupScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val manager = remember { BackupManager(context) }
    var revision by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var schedule by remember { mutableStateOf(manager.schedule) }
    var keep by remember { mutableIntStateOf(manager.keep) }
    var includeLearned by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<BackupManager.Found>>(emptyList()) }
    var confirm by remember { mutableStateOf<Pair<Uri, BackupFormat.Parsed>?>(null) }
    var afterPick by remember { mutableStateOf(false) }

    LaunchedEffect(revision) { found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { manager.list() } }
    LaunchedEffect(Unit) { manager.runIfDue(); }

    fun inspect(uri: Uri) {
        try { confirm = uri to manager.inspect(uri) } catch (e: BackupException) { message = e.message } catch (e: Exception) { message = "Could not read that file." }
    }
    fun backUpNow() {
        busy = true
        Thread { val r = manager.backUp(manual = true); busy = false; message = r.message; revision++ }.start()
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { manager.chooseFolder(uri); revision++; if (afterPick) backUpNow() }
        afterPick = false
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) inspect(uri) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) message = manager.exportTo(uri, if (includeLearned) UserData.ALL else setOf(BackupFormat.Scope.SETTINGS, BackupFormat.Scope.LAYOUTS)).message
    }

    @Suppress("UNUSED_EXPRESSION") revision
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        message?.let { Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) } }
        Text("Backups are ordinary files in a folder you choose. Wisp never uploads them. They hold your learned words, swipe style, layouts and settings. No audio and no speech models.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Section("Backup folder") {
            Text(manager.folderLabel() ?: "No folder chosen yet", style = MaterialTheme.typography.bodyLarge)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickFolder.launch(null) }) { Text(if (manager.folder == null) "Choose folder" else "Change folder") }
                Button({ if (manager.folder == null) { afterPick = true; pickFolder.launch(null) } else backUpNow() }, enabled = !busy) { Text(if (busy) "Backing up…" else "Back up now") }
            }
            manager.lastMessage?.let { Text("Last: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Section("Automatic backups") {
            ChoiceRow("How often", listOf("off" to "Never", "daily" to "Every day", "weekly" to "Every week"), schedule) { v -> schedule = v; manager.schedule = v }
            if (schedule != "off") {
                SliderRow("Automatic backups to keep", keep, 1..30, "") { v -> keep = v; manager.keep = v }
                Text("Checked when the keyboard or this app opens, so nothing runs in the background. Backups you make by hand are never deleted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Section("Restore") {
            if (found.isEmpty()) Text(if (manager.folder == null) "Choose a folder to see its backups here." else "No backups in that folder yet.", style = MaterialTheme.typography.bodyMedium)
            found.take(8).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(f.name.removeSuffix(".zip"), style = MaterialTheme.typography.bodyMedium); Text(if (f.manual) "made by hand" else "automatic", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    TextButton({ inspect(f.uri) }) { Text("Restore") }
                }
            }
            OutlinedButton({ pickFile.launch(arrayOf("application/zip", "*/*")) }) { Text("Restore from a file…") }
        }
        Section("Move settings to another phone") {
            SwitchRow("Include learned words and swipe style", "Off: only settings, themes and layouts", includeLearned) { includeLearned = it }
            OutlinedButton({ exportFile.launch("wisp-settings.zip") }) { Text("Export to a file…") }
        }
    }
    confirm?.let { (uri, parsed) ->
        val what = buildList { if (BackupFormat.Scope.LEARNED in parsed.scopes) add("learned words and swipe style"); if (BackupFormat.Scope.LAYOUTS in parsed.scopes) add("layouts"); if (BackupFormat.Scope.SETTINGS in parsed.scopes) add("settings") }.joinToString(", ")
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text("Restore this backup?") },
            text = { Text("Made ${DateFormat.getDateTimeInstance().format(Date(parsed.createdAt))}. It replaces your current $what with the ones in the backup.") },
            confirmButton = { TextButton({ confirm = null; message = try { manager.restore(parsed); "Restored. Wisp uses it from now on." } catch (e: Exception) { e.message ?: "The restore failed. Nothing was changed." } }) { Text("Restore") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}
