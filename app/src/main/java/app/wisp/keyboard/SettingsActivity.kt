package app.wisp.keyboard

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.wisp.keyboard.backup.BackupManager
import app.wisp.keyboard.settings.WispSettings
import app.wisp.keyboard.ui.*
import app.wisp.keyboard.ui.Target
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.wisp.keyboard.dict.WordListImporter
import app.wisp.keyboard.ui.HomeScreen
import app.wisp.keyboard.ui.TypingScreen
import app.wisp.keyboard.ui.VoiceModelsScreen
import app.wisp.keyboard.ui.WispTheme

/** The settings app. It runs in its own process (:settings) so its UI memory never counts against the keyboard. */
class SettingsActivity : ComponentActivity() {
    private var importInto: Language? = null
    private var screen by mutableStateOf(SettingsIndex.HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = WispSettings.get(this)
        screen = intent.getStringExtra(EXTRA_SCREEN) ?: if (settings.getBoolean("first_run_done", false)) SettingsIndex.HOME else SettingsIndex.WELCOME
        BackupManager(this).runIfDue()          // a scheduled backup that came due while the keyboard was closed
        setContent {
            WispTheme {
                Surface(Modifier.fillMaxSize()) {
                    BackHandler(screen != SettingsIndex.HOME) { screen = SettingsIndex.HOME }
                    Column(Modifier.statusBarsPadding()) {
                        if (screen != SettingsIndex.HOME && screen != SettingsIndex.WELCOME) Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ screen = SettingsIndex.HOME }) { Text("‹ Back") }
                            Text(SettingsIndex.titles[screen] ?: "", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                        }
                        val fill = Modifier.fillMaxSize()
                        when (screen) {
                            SettingsIndex.WELCOME -> WelcomeScreen(onDone = { settings.putBoolean("first_run_done", true); screen = SettingsIndex.HOME }, onOpen = ::open, modifier = fill)
                            SettingsIndex.TYPING -> TypingScreen(fill)
                            SettingsIndex.LOOK -> LookScreen(fill)
                            SettingsIndex.SIZE -> SizeScreen(fill)
                            SettingsIndex.FEEDBACK -> FeedbackScreen(fill)
                            SettingsIndex.LAYOUTS -> LayoutsScreen(fill)
                            SettingsIndex.VOICE -> VoiceModelsScreen(fill)
                            SettingsIndex.BACKUP -> BackupScreen(fill)
                            SettingsIndex.ABOUT -> AboutScreen(fill)
                            SettingsIndex.EXCLUDED -> ExcludedAppsScreen(fill)
                            else -> HomeScreen(onOpen = ::open, modifier = fill)
                        }
                    }
                }
            }
        }
    }

    private fun open(t: Target) = when (t) {
        is Target.Screen -> screen = t.id
        is Target.Open -> startActivity(Intent(this, t.activity))
        Target.ImportWords -> chooseLanguageThenFile()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_SCREEN)?.let { screen = it }
    }

    private fun chooseLanguageThenFile() {
        val langs = Languages.all
        AlertDialog.Builder(this).setTitle("Import words into")
            .setItems(langs.map { it.name }.toTypedArray()) { _, i ->
                importInto = langs[i]
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQUEST_IMPORT)
            }.show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        val lang = importInto ?: return
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return
        val message = try {
            val r = contentResolver.openInputStream(uri)!!.use { WordListImporter.import(this, lang, it) }
            "Added ${r.read} words to ${lang.name} (${r.total} imported in all)"
        } catch (e: Exception) { e.message ?: "Could not read that file" }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REQUEST_IMPORT = 7
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_VOICE = SettingsIndex.VOICE
    }
}
