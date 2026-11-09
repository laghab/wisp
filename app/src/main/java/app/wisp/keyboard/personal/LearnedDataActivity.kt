package app.wisp.keyboard.personal

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import app.wisp.keyboard.Language
import app.wisp.keyboard.Languages
import java.io.File

/**
 * What Wisp has learned from you, with the controls: see the words, forget one, export them, reset how you swipe, or erase it all.
 * It never leaves the phone. (A plain screen for now; the real settings app, task group 8, will host it.)
 */
class LearnedDataActivity : Activity() {
    private val pal by lazy { app.wisp.keyboard.style.KeyboardStyle.load(this).palette(this) }
    private val plum get() = pal.background; private val cream get() = pal.text; private val muted get() = pal.textMuted; private val coral get() = pal.accent
    private lateinit var data: LearnedData
    private lateinit var list: ListView
    private lateinit var summary: TextView
    private var language: Language = Languages.ENGLISH_US
    private var shown: List<PersonalDictionary.WordInfo> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Learned words"
        data = LearnedData(File(filesDir, "personal"))
        summary = TextView(this).apply { textSize = 14f; setTextColor(muted); setPadding(32, 16, 32, 16) }
        list = ListView(this).apply {
            setBackgroundColor(plum); divider = null
            setOnItemClickListener { _, _, pos, _ -> shown.getOrNull(pos)?.let { confirmForget(it.word) } }
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        val langs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            for (l in Languages.all) addView(button(l.name) { language = l; refresh() })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(plum)
            addView(TextView(this@LearnedDataActivity).apply { text = "What Wisp learned from you"; textSize = 22f; setTextColor(coral); setPadding(32, 64, 32, 8) })
            addView(TextView(this@LearnedDataActivity).apply { text = "Only on this phone. Tap a word to forget it."; textSize = 13f; setTextColor(muted); setPadding(32, 0, 32, 8) })
            addView(langs); addView(summary)
            addView(list, ViewGroup.LayoutParams.MATCH_PARENT, 0); (list.layoutParams as LinearLayout.LayoutParams).weight = 1f
            addView(button("Export these words") { export() })
            addView(button("Reset how I swipe") { confirm("Reset how you swipe?", "Wisp forgets your swipe style and which practice words gave you trouble. Your learned words stay.") { data.resetSwipeStyle(); toast("Swipe style reset") } })
            addView(button("Erase everything learned") { confirm("Erase everything?", "All learned words, word pairs and your swipe style are deleted. Imported word lists stay.") { data.wipeEverything(); refresh(); toast("Erased") } })
        })
        refresh()
    }

    private fun refresh() {
        shown = data.words(language.tag)
        val rows = shown.map { "${it.word}      ${it.uses} use${if (it.uses == 1) "" else "s"}${if (it.promoted) "   - your own word" else ""}" }
        list.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, rows) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply { setTextColor(cream); textSize = 16f; setPadding(32, 16, 32, 16) }
        }
        summary.text = if (shown.isEmpty()) "${language.name}: nothing learned yet. Words you use again and again appear here." else "${language.name}: ${shown.size} words"
    }

    private fun confirmForget(word: String) = confirm("Forget \"$word\"?", "It will no longer be suggested or swiped unless the main dictionary has it.") { data.forget(language.tag, word); refresh() }

    private fun confirm(title: String, message: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Yes") { _, _ -> yes() }.setNegativeButton("Cancel", null).show()
    }

    private fun export() {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/tab-separated-values").putExtra(Intent.EXTRA_TITLE, "wisp-learned-${language.tag}.tsv"), REQUEST_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, result: Intent?) {
        super.onActivityResult(requestCode, resultCode, result)
        val uri = result?.data ?: return
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        try { contentResolver.openOutputStream(uri)!!.use { data.export(language.tag, it) }; toast("Exported") } catch (e: Exception) { toast("Could not write that file") }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private companion object { const val REQUEST_EXPORT = 11 }
}
