package app.wisp.keyboard.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.wisp.keyboard.downloads.AppNetwork
import app.wisp.keyboard.downloads.ModelDownloads
import app.wisp.keyboard.downloads.Transfer
import app.wisp.keyboard.settings.WispSettings
import app.wisp.keyboard.voice.engine.DeviceProfile
import app.wisp.keyboard.voice.model.*

private fun mb(bytes: Long) = if (bytes >= 1_000_000_000) "%.1f GB".format(bytes / 1e9) else "%.0f MB".format(bytes / 1e6)

@Composable
fun VoiceModelsScreen(modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(tab == 0, { tab = 0 }, text = { Text("Models") })
            Tab(tab == 1, { tab = 1 }, text = { Text("Downloads") })
        }
        if (tab == 0) ModelsTab() else DownloadsTab()
    }
}

@Composable
private fun ModelsTab() {
    val context = LocalContext.current
    val dl = remember { ModelDownloads.get(context) }
    val device = remember { DeviceFacts(DeviceProfile.totalRamMb(context), DeviceProfile.availableRamMb(context), DeviceProfile.performanceCores) }
    val bench = remember { ModelBench(context) }
    val recommended = remember { ModelAdvisor.recommend(device) }
    val settings = remember { WispSettings.get(context) }
    var idle by remember { mutableIntStateOf(settings.getInt("voice_idle_minutes", 3)) }
    var benchRevision by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_EXPRESSION") dl.revision
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> pendingImport?.let { spec -> if (uri != null) dl.importArchive(spec, uri) }; pendingImport = null }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("Recommended for this phone", style = MaterialTheme.typography.labelLarge)
                    Text(recommended.model.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(recommended.reason, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item { StorageMeter(dl) }
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Text("Free the model when idle", style = MaterialTheme.typography.titleMedium)
                Text(when (idle) { 0 -> "Right after each dictation"; in 1..60 -> "After $idle minute${if (idle == 1) "" else "s"} without voice use"; else -> "Never" }, style = MaterialTheme.typography.bodyMedium)
                Slider(value = idle.toFloat().coerceIn(0f, 11f).let { if (idle < 0) 11f else it }, onValueChange = { v -> idle = if (v >= 10.5f) -1 else v.toInt(); settings.putInt("voice_idle_minutes", idle) }, valueRange = 0f..11f, steps = 10)
                Text("The model is only loaded while you use voice; this is how long it stays in memory afterwards.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        items(ModelCatalog.speech, key = { it.id }) { spec ->
            ModelCard(spec, dl, device, recommended.model.id == spec.id, bench, running == spec.id, benchRevision,
                onBench = { running = spec.id; bench.run(spec) { running = null; benchRevision++ } },
                onImport = { pendingImport = spec; picker.launch(arrayOf("*/*")) })
        }
        item { RewriteHeader(dl) }
        items(ModelCatalog.rewrite, key = { it.id }) { spec ->
            ModelCard(spec, dl, device, false, bench, false, benchRevision, onBench = {}, onImport = {})
        }
    }
}

private var pendingImport: ModelSpec? = null

/** The writing assistant: nothing is installed by default, and it can be turned off. */
@Composable
private fun RewriteHeader(dl: ModelDownloads) {
    @Suppress("UNUSED_EXPRESSION") dl.revision
    val off = dl.store.rewriteId == "off"
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Writing assistant", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Rewrites your text (polish, friendlier, formal, shorter, bullets, Hindi) with a small AI model that runs on this phone. Nothing is installed by default.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = !off, onCheckedChange = { dl.setRewriteOff(!it) })
            }
            if (off) Text("Off: the rewrite button explains how to turn it on.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** How much space the installed models take, against what is left on the phone. */
@Composable
private fun StorageMeter(dl: ModelDownloads) {
    val context = LocalContext.current
    @Suppress("UNUSED_EXPRESSION") dl.revision
    val used = dl.store.installed().sumOf { it.installedBytes }
    val free = context.noBackupFilesDir.usableSpace
    fun mb(b: Long) = if (b >= 1_000_000_000L) "%.1f GB".format(b / 1e9) else "${b / 1_000_000L} MB"
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Storage", style = MaterialTheme.typography.titleMedium)
            Text("${mb(used)} used by speech models \u00B7 ${mb(free)} free on this phone", style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { if (used + free == 0L) 0f else used.toFloat() / (used + free) }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ModelCard(spec: ModelSpec, dl: ModelDownloads, device: DeviceFacts, recommended: Boolean, bench: ModelBench, benchRunning: Boolean, benchRevision: Int, onBench: () -> Unit, onImport: () -> Unit) {
    @Suppress("UNUSED_EXPRESSION") dl.revision
    val installed = dl.store.isInstalled(spec)
    val active = if (spec.kind == ModelKind.REWRITE) dl.store.activeRewrite()?.id == spec.id else dl.store.active()?.id == spec.id
    val assessment = ModelAdvisor.assess(spec, device)
    val notes = ModelInfo.notesFor(spec.id)
    val transfer = dl.transfers[spec.id] ?: Transfer.Idle
    var open by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    Card(onClick = { open = !open }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${spec.languages} · ${mb(spec.source.downloadBytes)} download", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (active) AssistChip(onClick = {}, label = { Text("In use") })
                else if (recommended) AssistChip(onClick = {}, label = { Text("Recommended") })
            }
            Text(spec.description, style = MaterialTheme.typography.bodyMedium)
            if (assessment.fit != Fit.GOOD) Text(assessment.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (open) {
                notes.pros.forEach { Text("＋ $it", style = MaterialTheme.typography.bodySmall) }
                notes.cons.forEach { Text("－ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (assessment.fit == Fit.GOOD) Text(assessment.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                @Suppress("UNUSED_EXPRESSION") benchRevision
                bench.result(spec.id)?.let { Text("Measured here: %.1f× faster than real time, %.1f s to load".format(it.timesRealTime, it.loadMs / 1000f), style = MaterialTheme.typography.bodySmall) }
            }
            when (transfer) {
                is Transfer.Downloading -> { LinearProgressIndicator(progress = { if (transfer.total > 0) transfer.done.toFloat() / transfer.total else 0f }, Modifier.fillMaxWidth()); Row { Text("${mb(transfer.done)} of ${mb(transfer.total)}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); TextButton({ dl.cancel(spec) }) { Text("Cancel") } } }
                is Transfer.Installing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Unpacking…", style = MaterialTheme.typography.bodySmall) }
                is Transfer.Failed -> Row(verticalAlignment = Alignment.CenterVertically) { Text(transfer.message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error); TextButton({ dl.dismiss(spec) }) { Text("OK") } }
                Transfer.Idle -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (installed) {
                        if (!active) FilledTonalButton({ dl.select(spec) }) { Text("Use") }
                        if (spec.kind == ModelKind.SPEECH) OutlinedButton(onBench, enabled = !benchRunning) { Text(if (benchRunning) "Measuring…" else "Test speed") }
                        TextButton({ confirmRemove = true }) { Text("Remove") }
                    } else {
                        Button({ dl.download(spec) }) { Text("Download") }
                        if (spec.source is ModelSource.Archive) OutlinedButton(onImport) { Text("From file") }
                    }
                }
            }
        }
    }
    if (confirmRemove) AlertDialog(onDismissRequest = { confirmRemove = false },
        title = { Text("Remove ${spec.name}?") }, text = { Text("Frees ${mb(spec.installedBytes)}. You can download it again later.") },
        confirmButton = { TextButton({ dl.remove(spec); confirmRemove = false }) { Text("Remove") } },
        dismissButton = { TextButton({ confirmRemove = false }) { Text("Keep") } })
}

@Composable
private fun DownloadsTab() {
    val context = LocalContext.current
    val dl = remember { ModelDownloads.get(context) }
    var on by remember { mutableStateOf(AppNetwork.isOn(context)) }
    var url by remember { mutableStateOf(dl.manifestUrl) }
    @Suppress("UNUSED_EXPRESSION") dl.revision
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card { Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Allow Wisp to use the internet", style = MaterialTheme.typography.titleMedium)
                        Text("Off by default. Only this tab ever connects, and only to download models or updates. Typing and voice never use the network.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { on = it; AppNetwork.set(context, it) })
                }
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Installed models", style = MaterialTheme.typography.titleMedium)
                val installed = dl.store.installed()
                if (installed.isEmpty()) Text("None yet. Pick one on the Models tab.", style = MaterialTheme.typography.bodyMedium)
                installed.forEach { spec ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(spec.name); Text(mb(spec.installedBytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (dl.modelUpdates.containsKey(spec.id)) Button({ dl.updateModel(spec) }, enabled = !dl.isBusy(spec)) { Text("Update") }
                    }
                }
                Text("Servers contacted when downloading: ${ModelCatalog.all.flatMap { dl.hostsFor(it) }.toSortedSet().joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        item {
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Updates", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(url, { url = it; dl.manifestUrl = it }, Modifier.fillMaxWidth(), label = { Text("Update list address") }, singleLine = true)
                Button({ dl.checkForUpdates() }, enabled = !dl.checking) { Text(if (dl.checking) "Checking…" else "Check for updates") }
                dl.updateMessage?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                dl.wispUpdate?.let { a ->
                    Text("Wisp ${a.version} is available${if (a.notes.isNotBlank()) ": ${a.notes}" else ""}", style = MaterialTheme.typography.bodyMedium)
                    when (val t = dl.wispTransfer) {
                        is Transfer.Downloading -> LinearProgressIndicator(progress = { if (t.total > 0) t.done.toFloat() / t.total else 0f }, Modifier.fillMaxWidth())
                        is Transfer.Failed -> Text(t.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> {}
                    }
                    Button({ dl.updateWisp() }) { Text("Download and install") }
                    Text("Android will ask you to confirm the installation.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } }
        }
    }
}
