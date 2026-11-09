package app.wisp.keyboard.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.wisp.keyboard.settings.WispSettings

/** Apps Wisp never learns from: nothing typed or swiped in them is remembered. */
@Composable
fun ExcludedAppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { WispSettings.get(context) }
    var excluded by remember { mutableStateOf(settings.getStringSet("excluded_apps")) }
    var query by remember { mutableStateOf("") }
    val apps = remember {
        val pm = context.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }.filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    val shown = remember(query, excluded) { apps.filter { query.isBlank() || it.second.contains(query, true) } }
    Column(modifier.padding(horizontal = 16.dp)) {
        Text("Nothing you type or swipe in these apps is learned. Password fields and Private mode are never learned from either.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(vertical = 8.dp), label = { Text("Search apps") }, singleLine = true)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(shown, key = { it.first }) { (pkg, label) ->
                val on = pkg in excluded
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.bodyLarge); Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Switch(on, { v -> excluded = if (v) excluded + pkg else excluded - pkg; settings.putStringSet("excluded_apps", excluded) })
                }
            }
        }
    }
}
