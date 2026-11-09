package app.wisp.keyboard.gesture

import app.wisp.keyboard.kbd.CompiledLayout
import app.wisp.keyboard.kbd.LayoutGuard
import app.wisp.keyboard.kbd.LayoutParser
import java.io.File

object GestureTestSupport {
    private fun res(name: String): File = listOf(File("src/main/res/raw/$name"), File("app/src/main/res/raw/$name")).first { it.isFile }

    /** The shipped QWERTY layout at a phone-like width (1080 px wide, rows of ~140 px). */
    fun layout(name: String = "layout_qwerty.json", width: Float = 1080f): CompiledLayout {
        val spec = LayoutGuard.ensureSwitchKey(LayoutParser.parse(res(name).readText()))
        return CompiledLayout(spec, width, 4 * 140f + 40f, 8f, 12f, 16f)
    }

    fun evalFile(name: String): File? = listOf(File("../eval/$name"), File("eval/$name")).firstOrNull { it.isFile }

    fun readWords(file: File): Sequence<Pair<String, Int>> = file.useLines { lines ->
        lines.mapNotNull { l -> val t = l.indexOf('\t'); if (t < 0) null else l.substring(0, t) to l.substring(t + 1).toInt() }.toList()
    }.asSequence()

    fun tempFile(prefix: String): File = File.createTempFile("wisp-$prefix", ".idx").also { it.deleteOnExit() }
}
